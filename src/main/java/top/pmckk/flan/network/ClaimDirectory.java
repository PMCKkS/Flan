package top.pmckk.flan.network;

import java.util.*;
import top.pmckk.flan.network.TeleportWire.Entry;

/** Teleport discovery only. Protection and ownership remain authoritative on each backend. */
public final class ClaimDirectory {
    public record Destination(String server, Entry entry) {}
    public static final class LookupException extends IllegalArgumentException {
        private final String key;
        LookupException(String key) { super(key); this.key = key; }
        public String key() { return key; }
    }
    private final Map<String, List<Entry>> servers = new TreeMap<>();
    public synchronized boolean replace(String server, List<Entry> entries) {
        List<Entry> next = entries.stream().sorted(Comparator.comparing(Entry::id)).toList();
        return !Objects.equals(servers.put(server, next), next);
    }
    public synchronized Map<String, List<Entry>> snapshot() { return Map.copyOf(servers); }
    public synchronized Destination resolve(UUID player, String selector, boolean admin) {
        List<Destination> matches = new ArrayList<>();
        servers.forEach((server, entries) -> entries.stream().filter(e -> admin || player.equals(e.owner()))
                .filter(e -> matches(server, e, selector)).forEach(e -> matches.add(new Destination(server, e))));
        if (matches.isEmpty()) throw new LookupException("network.not-found");
        if (matches.size() > 1) throw new LookupException("network.ambiguous");
        return matches.getFirst();
    }
    private static boolean matches(String server, Entry entry, String selector) {
        return entry.name().equalsIgnoreCase(selector) || entry.id().toString().equalsIgnoreCase(selector)
                || (server + "/" + entry.name()).equalsIgnoreCase(selector) || (server + "/" + entry.id()).equalsIgnoreCase(selector);
    }
    public synchronized List<String> names(UUID player, boolean admin) {
        Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        servers.forEach((server, entries) -> entries.stream().filter(e -> admin || player.equals(e.owner())).forEach(e -> {
            result.add(e.name());
        }));
        return result.stream().limit(200).toList();
    }
}
