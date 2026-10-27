package top.pmckk.flan.network;

import java.util.*;

/** Correlates requests, destination readiness and acknowledgements across a server change. */
public final class TeleportTickets {
    public record Ticket(UUID id, UUID player, String server, UUID claim, long deadline, boolean delivered) {}
    private final Map<UUID, Ticket> pending = new HashMap<>();
    private final Map<UUID, Long> seen = new HashMap<>();
    public synchronized boolean open(Ticket ticket, long now) {
        seen.values().removeIf(deadline -> deadline < now);
        if (pending.containsKey(ticket.player()) || seen.containsKey(ticket.id())) return false;
        pending.put(ticket.player(), ticket); seen.put(ticket.id(), now + 60000); return true;
    }
    public synchronized Optional<Ticket> deliver(UUID player, String server, long now) {
        Ticket ticket = pending.get(player);
        if (ticket == null || ticket.delivered() || ticket.deadline() < now || !ticket.server().equals(server)) return Optional.empty();
        pending.put(player, new Ticket(ticket.id(), player, server, ticket.claim(), ticket.deadline(), true)); return Optional.of(ticket);
    }
    public synchronized boolean acknowledge(UUID id, UUID player, String server) {
        Ticket ticket = pending.get(player);
        if (ticket == null || !ticket.delivered() || !ticket.id().equals(id) || !ticket.server().equals(server)) return false;
        pending.remove(player); return true;
    }
    public synchronized boolean cancel(UUID id, UUID player) {
        Ticket ticket = pending.get(player); if (ticket == null || !ticket.id().equals(id)) return false;
        pending.remove(player); return true;
    }
    public synchronized List<Ticket> expire(long now) {
        List<Ticket> expired = pending.values().stream().filter(ticket -> ticket.deadline() < now).toList();
        expired.forEach(ticket -> pending.remove(ticket.player())); seen.values().removeIf(deadline -> deadline < now); return expired;
    }
    public synchronized void disconnect(UUID player) { pending.remove(player); }
    public synchronized List<Ticket> snapshot() { return List.copyOf(pending.values()); }
}
