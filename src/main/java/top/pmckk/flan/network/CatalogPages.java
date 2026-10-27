package top.pmckk.flan.network;

import java.util.*;
import top.pmckk.flan.network.TeleportWire.*;

/** Publishes a server snapshot only after all its pages arrive; old generations cannot roll it back. */
public final class CatalogPages {
    private record Assembly(long generation, int count, long deadline, Map<Integer, List<Entry>> pages) {}
    private final Map<String, Assembly> partial = new HashMap<>();
    private final Map<String, Long> complete = new HashMap<>();
    public synchronized Optional<List<Entry>> accept(String server, Catalog packet, long now) {
        partial.values().removeIf(value -> value.deadline() < now);
        if (packet.generation() <= complete.getOrDefault(server, 0L)) return Optional.empty();
        Assembly assembly = partial.get(server);
        if (assembly != null && packet.generation() < assembly.generation()) return Optional.empty();
        if (assembly == null || assembly.generation() != packet.generation()) {
            assembly = new Assembly(packet.generation(), packet.pages(), now + 30000, new HashMap<>()); partial.put(server, assembly);
        }
        if (assembly.count() != packet.pages()) throw new IllegalArgumentException("catalog pages");
        List<Entry> previous = assembly.pages().putIfAbsent(packet.page(), packet.entries());
        if (previous != null && !previous.equals(packet.entries())) throw new IllegalArgumentException("duplicate page");
        if (assembly.pages().size() != assembly.count()) return Optional.empty();
        Map<UUID, Entry> entries = new LinkedHashMap<>();
        for (int page = 0; page < assembly.count(); page++) for (Entry entry : assembly.pages().get(page))
            if (entries.putIfAbsent(entry.id(), entry) != null) throw new IllegalArgumentException("duplicate claim");
        complete.put(server, packet.generation()); partial.remove(server); return Optional.of(List.copyOf(entries.values()));
    }
}
