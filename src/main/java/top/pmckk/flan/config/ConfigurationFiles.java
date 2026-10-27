package top.pmckk.flan.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Splits legacy settings once, keeping a backup and composing the same internal keys. */
public final class ConfigurationFiles {
    private ConfigurationFiles() {}
    private static final Map<String, List<String>> FILES = Map.of(
            "storage.yml", List.of("storage"), "claims.yml", List.of("claims"),
            "permissions.yml", List.of("groups", "defaults"), "tool.yml", List.of("tool"), "teleport.yml", List.of("teleport"));
    public record Loaded(YamlConfiguration main, YamlConfiguration effective) {}

    public static Loaded load(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path mainFile = directory.resolve("config.yml");
        if (!Files.exists(mainFile)) copyResource("config.yml", mainFile);
        var main = new YamlConfiguration(); main.load(mainFile.toFile());
        boolean legacy = FILES.values().stream().flatMap(Collection::stream).anyMatch(main::contains);
        if (legacy && !Files.exists(directory.resolve("config.legacy.yml"))) Files.copy(mainFile, directory.resolve("config.legacy.yml"));
        Map<String, YamlConfiguration> parts = new HashMap<>();
        for (var entry : FILES.entrySet()) {
            Path path = directory.resolve(entry.getKey());
            if (!Files.exists(path)) {
                YamlConfiguration defaults = resource(entry.getKey());
                for (String root : entry.getValue()) if (main.contains(root)) {
                    if (!main.isConfigurationSection(root)) throw new MessageException("errors.config-sections");
                    defaults.set(root, null); copySection(main.getConfigurationSection(root), defaults.createSection(root));
                }
                if (entry.getKey().equals("claims.yml") && main.contains("claims")) defaults.set("rules-version", 2);
                defaults.save(path.toFile());
            }
            var config = new YamlConfiguration(); config.load(path.toFile());
            for (String root : entry.getValue()) if (!config.isConfigurationSection(root)) throw new MessageException("errors.config-sections");
            if (entry.getKey().equals("claims.yml") && (config.getInt("rules-version", 0) < 3 || config.contains("claims.disabled-dimensions"))) {
                if (config.getInt("rules-version", 0) < 3) {
                    String list = "claims.disabled-worlds";
                    if (config.contains(list) && (!config.isList(list) || config.getList(list).stream().anyMatch(value -> !(value instanceof String))))
                        throw new MessageException("errors.world-rules");
                    config.set("claims.disabled-worlds", config.getStringList("claims.disabled-worlds").stream()
                            .map(key -> key.matches("[a-z0-9._/-]+") ? "minecraft:" + key : key).toList());
                }
                WorldRules.read(config);
                Path backup = directory.resolve("claims.legacy.yml");
                if (!Files.exists(backup)) Files.copy(path, backup);
                config.set("claims.disabled-dimensions", null);
                config.set("rules-version", 3); config.save(path.toFile());
            }
            parts.put(entry.getKey(), config);
        }
        var effective = new YamlConfiguration();
        for (var entry : FILES.entrySet()) for (String root : entry.getValue())
            copySection(parts.get(entry.getKey()).getConfigurationSection(root), effective.createSection(root));
        effective.set("Lang", main.getString("Lang", "zh_CN"));
        if (legacy) {
            for (var roots : FILES.values()) for (String root : roots) main.set(root, null);
            main.set("config-version", 2); main.save(mainFile.toFile());
        }
        return new Loaded(main, effective);
    }
    public static void storageType(Path directory, String type) throws Exception {
        Path file = directory.resolve("storage.yml");
        var config = new YamlConfiguration(); config.load(file.toFile()); config.set("storage.type", type); config.save(file.toFile());
    }
    public static YamlConfiguration resource(String name) throws Exception {
        var yaml = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(ConfigurationFiles.class.getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) { yaml.load(reader); }
        return yaml;
    }
    private static void copyResource(String name, Path file) throws Exception {
        try (var stream = Objects.requireNonNull(ConfigurationFiles.class.getResourceAsStream("/" + name))) { Files.copy(stream, file); }
    }
    private static void copySection(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            if (from.isConfigurationSection(key)) copySection(from.getConfigurationSection(key), to.createSection(key));
            else to.set(key, from.get(key));
        }
    }
}
