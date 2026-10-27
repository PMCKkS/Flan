package top.pmckk.flan.config;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;

public final class Messages {
    private static final Pattern TOKEN = Pattern.compile("[{]([^{}]+)}");
    private final YamlConfiguration config;
    private final String locale;

    private Messages(YamlConfiguration config, String locale) { this.config = config; this.locale = locale; }

    public static Messages load(Path directory, String locale) throws Exception {
        if (locale == null || !locale.matches("[a-zA-Z0-9_-]{1,32}")) throw new MessageException("errors.language-name", "language", String.valueOf(locale));
        Files.createDirectories(directory);
        for (String builtIn : List.of("zh_CN", "en_US")) {
            Path target = directory.resolve(builtIn + ".yml");
            if (!Files.exists(target)) {
                try (var stream = Objects.requireNonNull(Messages.class.getResourceAsStream("/lang/" + builtIn + ".yml"))) { Files.copy(stream, target); }
                // Import old custom messages once; leave the original file intact.
                Path legacy = directory.getParent().resolve("messages.yml");
                if (builtIn.equals("zh_CN") && Files.exists(legacy)) {
                    var old = new YamlConfiguration(); old.load(legacy.toFile());
                    var migrated = new YamlConfiguration(); migrated.load(target.toFile());
                    for (String key : old.getKeys(true)) if (old.isString(key) && migrated.isString(key)) migrated.set(key, old.getString(key));
                    if ("&6[Flan] &r".equals(old.getString("prefix"))) migrated.set("prefix", bundled().getString("prefix"));
                    migrated.save(target.toFile());
                }
            }
        }
        Path file = directory.resolve(locale + ".yml");
        if (!Files.isRegularFile(file)) throw new MessageException("errors.language-missing", "language", locale);
        var selected = new YamlConfiguration(); selected.load(file.toFile());
        var fallback = bundled();
        for (String key : fallback.getKeys(true)) {
            if (fallback.isConfigurationSection(key)) continue;
            if (!selected.contains(key)) { selected.set(key, fallback.get(key)); continue; }
            if (fallback.isString(key) && !selected.isString(key) || fallback.isList(key) && (!selected.isList(key) || selected.getList(key).stream().anyMatch(v -> !(v instanceof String))))
                throw new MessageException("errors.language-value", "key", key);
        }
        return new Messages(selected, locale);
    }

    private static YamlConfiguration bundled() throws Exception {
        var defaults = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(Messages.class.getResourceAsStream("/lang/zh_CN.yml")), StandardCharsets.UTF_8)) { defaults.load(reader); }
        return defaults;
    }

    public String locale() { return locale; }
    public static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private String replace(String template, Object... args) {
        if (args.length % 2 != 0) throw new MessageException("errors.language-arguments");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) values.put(String.valueOf(args[i]), String.valueOf(args[i + 1]));
        Matcher matcher = TOKEN.matcher(template); StringBuilder output = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(output, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), matcher.group())));
        matcher.appendTail(output); return output.toString();
    }
    public String text(String key, Object... args) {
        String value = config.getString(key);
        if (value == null) throw new MessageException("errors.language-key", "key", key);
        return replace(value, args);
    }
    public String formatted(String key, Object... args) { return color(text(key, args)); }
    public String format(String template, Object... args) { return color(replace(template, args)); }
    public void requireText(String key) {
        if (!config.isString(key)) throw new MessageException("errors.language-value", "key", key);
    }
    public void requireLines(String key) {
        if (!config.isList(key) || config.getList(key).stream().anyMatch(value -> !(value instanceof String)))
            throw new MessageException("errors.language-value", "key", key);
    }
    public List<String> lines(String key, Object... args) {
        return config.getStringList(key).stream().map(line -> color(replace(line, args))).toList();
    }
    public String permission(top.pmckk.flan.model.ClaimPermission p) { return text("permissions." + p.name().toLowerCase(Locale.ROOT)); }
    public String error(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MessageException localized) return text(localized.key(), localized.arguments());
            if (cause instanceof java.sql.SQLException && cause.getMessage() != null
                    && cause.getMessage().contains("Public Key Retrieval is not allowed"))
                return text("errors.mysql-public-key");
            if (cause instanceof NumberFormatException || cause instanceof ArithmeticException) return text("errors.number");
        }
        return text("errors.unexpected");
    }
    public void sendError(CommandSender sender, Throwable error) { send(sender, "error", "message", error(error)); }
    public String notification(String key, Object... args) { return color(text("prefix") + text(key, args)); }
    public void send(CommandSender sender, String key, Object... args) { sender.sendMessage(notification(key, args)); }
    public void sendLines(CommandSender sender, String key, Object... args) {
        for (String line : lines(key, args)) sender.sendMessage(color(text("prefix")) + line);
    }
    public void log(Logger logger, Level level, String key, Throwable error, Object... args) {
        logger.log(level, ChatColor.stripColor(notification(key, args)), error);
    }
}
