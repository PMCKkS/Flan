package top.pmckk.flan.service;

import top.pmckk.flan.config.MessageException;

import top.pmckk.flan.model.*;
import java.util.*;

/** Thread-safe in-memory domain model. All writes are initiated from the server thread. */
public final class ClaimRegistry {
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, PlayerAccount> players = new LinkedHashMap<>();
    private final Map<UUID, Map<Long, Set<UUID>>> index = new HashMap<>();
    private final Map<UUID, Set<UUID>> large = new HashMap<>();
    private Map<ClaimPermission, Boolean> defaults;
    private long revision;
    public ClaimRegistry(Snapshot snapshot, Map<ClaimPermission, Boolean> defaults) {
        this.defaults = Map.copyOf(defaults);
        for (PlayerAccount p : snapshot.players()) if (players.putIfAbsent(p.id(), p) != null) throw new MessageException("errors.duplicate-player");
        for (Claim c : snapshot.claims()) if (claims.putIfAbsent(c.id(), c) != null) throw new MessageException("errors.duplicate-claim");
        for (Claim c : claims.values()) {
            if (c.bounds().area() > 100000000L) throw new MessageException("errors.claim-too-large", "id", c.id());
            if (c.parent() != null) {
                Claim p = claims.get(c.parent());
                if (p == null || p.parent() != null || !p.world().equals(c.world()) || !Objects.equals(p.owner(), c.owner()) || !p.bounds().contains(c.bounds()))
                    throw new MessageException("errors.hierarchy", "id", c.id());
            }
            if (c.owner() != null && !players.containsKey(c.owner())) throw new MessageException("errors.owner-account-missing", "id", c.id());
            for (Claim other : candidates(c.world(), c.bounds())) {
                if (c.id().equals(other.id())) continue;
                if (Objects.equals(c.parent(), other.id()) || Objects.equals(other.parent(), c.id())) continue;
                if (c.bounds().intersects(other.bounds())) throw new MessageException("errors.stored-overlap", "id", c.id());
            }
            addIndex(c);
        }
    }
    public synchronized void defaults(Map<ClaimPermission, Boolean> value) { defaults = Map.copyOf(value); }
    public synchronized long revision() { return revision; }
    public synchronized Snapshot snapshot() { return new Snapshot(new ArrayList<>(claims.values()), new ArrayList<>(players.values())); }
    public synchronized Claim get(UUID id) { return claims.get(id); }
    public synchronized List<Claim> all() { return List.copyOf(claims.values()); }
    public synchronized List<PlayerAccount> accounts() { return List.copyOf(players.values()); }
    public synchronized PlayerAccount account(UUID id) { return players.get(id); }
    public synchronized PlayerAccount ensure(UUID id, String name, long start) {
        PlayerAccount p = players.get(id);
        if (p == null) { p = new PlayerAccount(id, name, start, 0); players.put(id, p); revision++; }
        else if (!p.name().equals(name)) { p = p.renamed(name); players.put(id, p); revision++; }
        return p;
    }
    public synchronized long used(UUID owner) {
        long sum = 0;
        for (Claim c : claims.values()) if (c.parent() == null && owner.equals(c.owner())) sum = Math.addExact(sum, c.bounds().area());
        return sum;
    }
    public synchronized long count(UUID owner) { return claims.values().stream().filter(c -> c.parent() == null && owner.equals(c.owner())).count(); }
    public synchronized long remaining(UUID owner) { PlayerAccount p = players.get(owner); return p == null ? 0 : Math.max(0, p.allowance() - used(owner)); }
    public synchronized void allowance(UUID id, long amount, long maximum) {
        PlayerAccount p = players.get(id); if (p == null) throw new MessageException("errors.player-not-joined");
        if (amount < used(id) || amount > maximum) throw new MessageException("errors.allowance-range");
        players.put(id, p.withAllowance(amount)); revision++;
    }
    public synchronized void accrue(UUID id, int seconds, long blocks, long maximum) {
        PlayerAccount p = players.get(id); if (p == null) return;
        long time = p.accrualSeconds() + 1; long awards = time / seconds;
        long amount = Math.min(maximum, p.allowance() + awards * blocks);
        players.put(id, new PlayerAccount(id, p.name(), Math.max(p.allowance(), amount), time % seconds)); revision++;
    }
    public synchronized Claim at(UUID world, int x, int y, int z) {
        Set<UUID> ids = new HashSet<>(index.getOrDefault(world, Map.of()).getOrDefault(key(x >> 4, z >> 4), Set.of()));
        ids.addAll(large.getOrDefault(world, Set.of())); Claim found = null;
        for (UUID id : ids) { Claim c = claims.get(id);
            if (c.bounds().contains(x, y, z) && (found == null || c.parent() != null)) found = c;
        } return found;
    }
    public synchronized Claim root(Claim c) { return c == null || c.parent() == null ? c : claims.get(c.parent()); }
    public synchronized List<Claim> nearby(UUID world, int x, int y, int z, int radius, int limit) {
        if (radius < 0 || limit < 1) return List.of();
        Bounds search = new Bounds(offset(x, -radius), offset(y, -radius), offset(z, -radius), offset(x, radius), offset(y, radius), offset(z, radius));
        Claim current = at(world, x, y, z);
        Claim root = root(current);
        return candidates(world, search).stream().filter(c -> c.bounds().intersects(search))
                .sorted(Comparator.comparingInt((Claim c) -> current != null && current.id().equals(c.id()) ? 0 : root != null && root.id().equals(c.id()) ? 1 : 2)
                        .thenComparingDouble(c -> distanceSquared(c.bounds(), x, y, z)).thenComparing(Claim::id))
                .limit(limit).toList();
    }
    private static int offset(int value, int amount) { return (int) Math.clamp((long) value + amount, Integer.MIN_VALUE, Integer.MAX_VALUE); }
    private static double distanceSquared(Bounds b, int x, int y, int z) {
        double dx = Math.max(Math.max((long) b.minX() - x, (long) x - b.maxX()), 0);
        double dy = Math.max(Math.max((long) b.minY() - y, (long) y - b.maxY()), 0);
        double dz = Math.max(Math.max((long) b.minZ() - z, (long) z - b.maxZ()), 0);
        return dx * dx + dy * dy + dz * dz;
    }
    public synchronized boolean allowed(Claim c, UUID actor, ClaimPermission permission) {
        if (c == null) return true;
        if (actor != null && actor.equals(c.owner()) && permission.personal()) return true;
        if (actor != null && permission.personal() && memberGroup(c, actor) != null) {
            Boolean individual = playerFlag(c, actor, permission);
            if (individual != null) return individual;
            Set<ClaimPermission> perms = group(c, memberGroup(c, actor)); return perms != null && perms.contains(permission);
        }
        Boolean value = c.flags().get(permission);
        if (value != null) return value;
        if (c.parent() != null) return allowed(claims.get(c.parent()), null, permission);
        return defaults.getOrDefault(permission, false);
    }
    public synchronized String memberGroup(Claim c, UUID player) {
        if (c.excludedMembers().contains(player)) return null;
        String g = c.members().get(player); return g != null || c.parent() == null ? g : memberGroup(claims.get(c.parent()), player);
    }
    public synchronized Boolean playerFlag(Claim c, UUID player, ClaimPermission permission) {
        Boolean value = c.playerFlags().getOrDefault(player, Map.of()).get(permission);
        if (value != null || c.parent() == null || c.excludedMembers().contains(player) || c.members().containsKey(player)) return value;
        return playerFlag(claims.get(c.parent()), player, permission);
    }
    public synchronized Set<UUID> trusted(Claim c) {
        Set<UUID> result = c.parent() == null ? new HashSet<>() : new HashSet<>(trusted(claims.get(c.parent())));
        result.removeAll(c.excludedMembers()); result.addAll(c.members().keySet());
        return Set.copyOf(result);
    }
    public synchronized Claim trust(Claim c, UUID player, String group) {
        if (player.equals(c.owner())) throw new MessageException("errors.trust-owner");
        if (group(c, group) == null) throw new MessageException("errors.group-missing");
        Map<UUID, String> members = new HashMap<>(c.members()); members.put(player, group);
        Set<UUID> excluded = new HashSet<>(c.excludedMembers()); excluded.remove(player);
        Claim updated = c.withTrust(members, c.playerFlags(), excluded); update(updated); return updated;
    }
    public synchronized Claim trustDefault(Claim c, UUID player) {
        if (group(c, "builder") != null) return trust(c, player, "builder");
        String key = "flan_empty";
        while (group(c, key) != null && !group(c, key).isEmpty()) key += "_";
        if (group(c, key) == null) {
            Map<String, Set<ClaimPermission>> groups = new HashMap<>(c.groups()); groups.put(key, Set.of()); c = c.withGroups(groups);
        }
        return trust(c, player, key);
    }
    public synchronized Claim untrust(Claim c, UUID player) {
        Map<UUID, String> members = new HashMap<>(c.members()); members.remove(player);
        Map<UUID, Map<ClaimPermission, Boolean>> flags = new HashMap<>(c.playerFlags()); flags.remove(player);
        Set<UUID> excluded = new HashSet<>(c.excludedMembers()); excluded.add(player);
        Claim updated = c.withTrust(members, flags, excluded); update(updated); return updated;
    }
    public synchronized Claim playerPermission(Claim c, UUID player, ClaimPermission permission, Boolean value) {
        if (!permission.personal()) throw new MessageException("errors.personal-only");
        if (memberGroup(c, player) == null || player.equals(c.owner())) throw new MessageException("errors.trust-missing");
        Map<UUID, Map<ClaimPermission, Boolean>> flags = new HashMap<>(c.playerFlags());
        Map<ClaimPermission, Boolean> individual = new EnumMap<>(ClaimPermission.class); individual.putAll(flags.getOrDefault(player, Map.of()));
        if (value == null) individual.remove(permission); else individual.put(permission, value);
        if (individual.isEmpty()) flags.remove(player); else flags.put(player, individual);
        Claim updated = c.withTrust(c.members(), flags, c.excludedMembers()); update(updated); return updated;
    }
    public synchronized Set<ClaimPermission> group(Claim c, String name) {
        Set<ClaimPermission> p = c.groups().get(name); return p != null || c.parent() == null ? p : group(claims.get(c.parent()), name);
    }
    public synchronized Map<String, Set<ClaimPermission>> groups(Claim c) {
        Map<String, Set<ClaimPermission>> result = new TreeMap<>();
        if (c.parent() != null) result.putAll(groups(claims.get(c.parent()))); result.putAll(c.groups()); return result;
    }
    public synchronized void create(Claim c, int maximumClaims) {
        if (claims.containsKey(c.id())) throw new MessageException("errors.duplicate-claim");
        if (c.parent() == null && c.owner() != null && maximumClaims >= 0 && count(c.owner()) >= maximumClaims) throw new MessageException("errors.claim-limit");
        validate(c); claims.put(c.id(), c); addIndex(c); revision++;
    }
    public synchronized void update(Claim c) {
        Claim before = claims.get(c.id()); if (before == null) throw new MessageException("errors.claim-missing");
        if (!Objects.equals(c.owner(), before.owner()) || !Objects.equals(c.parent(), before.parent()) || !c.world().equals(before.world()))
            throw new MessageException("errors.transfer-operation");
        validate(c); removeIndex(before); claims.put(c.id(), c); addIndex(c); revision++;
    }
    private void validate(Claim c) {
        if (c.bounds().area() > 100000000L) throw new MessageException("errors.claim-too-large", "id", c.id());
        if (c.parent() != null) {
            Claim p = claims.get(c.parent());
            if (p == null || p.parent() != null || !p.world().equals(c.world()) || !Objects.equals(p.owner(), c.owner()) || !p.bounds().contains(c.bounds()))
                throw new MessageException("errors.subclaim-parent");
        } else {
            for (Claim child : claims.values()) if (c.id().equals(child.parent()) && !c.bounds().contains(child.bounds())) throw new MessageException("errors.resize-children");
            if (c.owner() != null) {
                PlayerAccount p = players.get(c.owner()); if (p == null) throw new MessageException("errors.player-data-missing");
                Claim old = claims.get(c.id()); long oldArea = old == null ? 0 : old.bounds().area();
                if (used(c.owner()) - oldArea + c.bounds().area() > p.allowance()) throw new MessageException("errors.insufficient-blocks");
            }
        }
        for (Claim other : conflicts(c)) throw new MessageException("errors.overlap", "name", other.name());
    }
    public synchronized List<Claim> conflicts(Claim proposed) {
        return candidates(proposed.world(), proposed.bounds()).stream()
                .filter(other -> !proposed.id().equals(other.id()) && !proposed.id().equals(other.parent()) && !Objects.equals(proposed.parent(), other.id()))
                .filter(other -> proposed.bounds().intersects(other.bounds())).toList();
    }
    public synchronized void transfer(Claim c, UUID newOwner, int maxClaims) {
        if (c.parent() != null) throw new MessageException("errors.transfer-parent");
        if (Objects.equals(c.owner(), newOwner)) throw new MessageException("errors.already-owner");
        PlayerAccount p = players.get(newOwner); if (p == null) throw new MessageException("errors.player-not-joined");
        if ((maxClaims >= 0 && count(newOwner) >= maxClaims) || remaining(newOwner) < c.bounds().area()) throw new MessageException("errors.transfer-capacity");
        claims.put(c.id(), c.transferred(newOwner));
        for (Claim child : List.copyOf(claims.values())) if (c.id().equals(child.parent())) claims.put(child.id(), child.transferred(newOwner));
        revision++;
    }
    public synchronized void delete(Claim c) {
        List<Claim> remove = claims.values().stream().filter(other -> c.id().equals(other.id()) || c.id().equals(other.parent())).toList();
        for (Claim other : remove) { removeIndex(other); claims.remove(other.id()); } revision++;
    }
    private List<Claim> candidates(UUID world, Bounds b) {
        if (chunkCount(b) > 4096) return claims.values().stream().filter(c -> c.world().equals(world)).toList();
        Set<UUID> ids = new HashSet<>(large.getOrDefault(world, Set.of())); Map<Long, Set<UUID>> wi = index.getOrDefault(world, Map.of());
        for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) ids.addAll(wi.getOrDefault(key(x, z), Set.of()));
        return ids.stream().map(claims::get).toList();
    }
    private void addIndex(Claim c) {
        Bounds b = c.bounds();
        if (chunkCount(b) > 4096) { large.computeIfAbsent(c.world(), k -> new HashSet<>()).add(c.id()); return; }
        Map<Long, Set<UUID>> wi = index.computeIfAbsent(c.world(), k -> new HashMap<>());
        for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) wi.computeIfAbsent(key(x, z), k -> new HashSet<>()).add(c.id());
    }
    private void removeIndex(Claim c) {
        Bounds b = c.bounds(); Set<UUID> big = large.get(c.world()); if (big != null) big.remove(c.id());
        if (chunkCount(b) > 4096) return; Map<Long, Set<UUID>> wi = index.get(c.world()); if (wi == null) return;
        for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) {
            long k = key(x, z); Set<UUID> ids = wi.get(k); if (ids != null) { ids.remove(c.id()); if (ids.isEmpty()) wi.remove(k); }
        }
    }
    private static long chunkCount(Bounds b) { return ((long) (b.maxX() >> 4) - (b.minX() >> 4) + 1) * ((long) (b.maxZ() >> 4) - (b.minZ() >> 4) + 1); }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
