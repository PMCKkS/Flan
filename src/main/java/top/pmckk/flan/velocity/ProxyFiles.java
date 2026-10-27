package top.pmckk.flan.velocity;

import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import top.pmckk.flan.network.TeleportWire.Entry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.*;

/** Proxy configuration and discovery cache; no Bukkit or claim database access. */
public final class ProxyFiles {
    private final Path directory;
    private final Map<String, Object> language, fallback;
    private final String secret;
    private final int timeout;
    public ProxyFiles(Path directory) throws IOException {
        this.directory = directory; Files.createDirectories(directory);
        Path config = directory.resolve("velocity.yml");
        if (!Files.exists(config)) {
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            String source = resource("velocity.yml").replace("secret: ''", "secret: '" + HexFormat.of().formatHex(random) + "'");
            Files.writeString(config, source, StandardCharsets.UTF_8);
        }
        Map<String, Object> settings = load(Files.readString(config)); Map<String, Object> network = mapping(settings.get("network"));
        Object secretValue = network.get("secret"), timeoutValue = network.get("timeout-seconds");
        if (!(secretValue instanceof String s) || s.length() < 32 || s.length() > 256 || !(timeoutValue instanceof Number number)
                || number.doubleValue() != number.intValue() || number.intValue() < 5 || number.intValue() > 60) throw new IOException("velocity.yml: network");
        secret = s; timeout = number.intValue();
        Files.createDirectories(directory.resolve("lang"));
        for (String locale : List.of("zh_CN", "en_US")) {
            Path file = directory.resolve("lang/" + locale + ".yml");
            if (!Files.exists(file)) Files.writeString(file, resource("lang/" + locale + ".yml"), StandardCharsets.UTF_8);
        }
        String locale = Objects.toString(settings.getOrDefault("Lang", "zh_CN"));
        if (!locale.matches("[a-zA-Z0-9_-]{1,32}")) throw new IOException("velocity.yml: Lang");
        fallback = load(resource("lang/zh_CN.yml"));
        language = load(Files.readString(directory.resolve("lang/" + locale + ".yml")));
    }
    public String secret() { return secret; }
    public int timeout() { return timeout; }
    public String text(String key, Object... args) {
        Object value = get(language, key); if (!(value instanceof String)) value = get(fallback, key);
        String template = value instanceof String string ? string : key;
        Map<String, String> values = new HashMap<>(); for (int i = 0; i + 1 < args.length; i += 2) values.put(String.valueOf(args[i]), String.valueOf(args[i + 1]));
        Matcher matcher = Pattern.compile("[{]([^{}]+)}").matcher(template); StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), matcher.group())));
        matcher.appendTail(result); return result.toString();
    }
    public String notification(String key, Object... args) { return text("prefix") + text(key, args); }
    private static Object get(Map<String, Object> map, String key) {
        Object value = map;
        for (String part : key.split("[.]")) { if (!(value instanceof Map<?, ?> section)) return null; value = section.get(part); }
        return value;
    }
    private static String resource(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(ProxyFiles.class.getResourceAsStream("/" + name))) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions(); options.setMaxAliasesForCollections(0); options.setCodePointLimit(32 * 1024 * 1024);
        options.setAllowDuplicateKeys(false); return new Yaml(new SafeConstructor(options));
    }
    private static Map<String, Object> load(String source) throws IOException {
        try { return mapping(yaml().load(source)); } catch (RuntimeException error) { throw new IOException("yaml", error); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> mapping(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw new IOException("mapping");
        return (Map<String, Object>) map;
    }
    public Map<String, List<Entry>> catalog() throws IOException {
        Path path = directory.resolve("teleport-catalog.yml"); if (!Files.exists(path)) return Map.of();
        Map<String, Object> root = load(Files.readString(path)); Map<String, List<Entry>> result = new HashMap<>();
        for (var section : root.entrySet()) {
            if (!(section.getValue() instanceof List<?> list) || list.size() > 100000) throw new IOException("catalog");
            List<Entry> entries = new ArrayList<>(); Set<UUID> ids = new HashSet<>();
            for (Object value : list) {
                Map<String, Object> data = mapping(value);
                try {
                    Entry entry = new Entry(UUID.fromString((String) data.get("id")), data.get("owner") == null ? null : UUID.fromString((String) data.get("owner")), (String) data.get("name"));
                    if (!ids.add(entry.id())) throw new IOException("duplicate claim"); entries.add(entry);
                } catch (RuntimeException error) { throw new IOException("catalog entry", error); }
            }
            result.put(section.getKey(), List.copyOf(entries));
        }
        return result;
    }
    public synchronized void save(Map<String, List<Entry>> catalog) throws IOException {
        Map<String, Object> output = new TreeMap<>();
        catalog.forEach((server, entries) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Entry entry : entries) {
                Map<String, Object> value = new LinkedHashMap<>(); value.put("id", entry.id().toString());
                value.put("owner", entry.owner() == null ? null : entry.owner().toString()); value.put("name", entry.name()); list.add(value);
            }
            output.put(server, list);
        });
        Path temporary = Files.createTempFile(directory, "teleport-catalog-", ".tmp");
        try {
            DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Files.writeString(temporary, new Yaml(options).dump(output), StandardCharsets.UTF_8);
            try { Files.move(temporary, directory.resolve("teleport-catalog.yml"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, directory.resolve("teleport-catalog.yml"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
