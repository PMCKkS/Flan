package top.pmckk.flan.model;

import java.util.List;

public record Snapshot(List<Claim> claims, List<PlayerAccount> players) {
    public Snapshot { claims = List.copyOf(claims); players = List.copyOf(players); }
    public static Snapshot empty() { return new Snapshot(List.of(), List.of()); }
    public boolean isEmpty() { return claims.isEmpty() && players.isEmpty(); }
}
