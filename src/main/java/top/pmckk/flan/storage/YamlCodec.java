package top.pmckk.flan.storage;

import top.pmckk.flan.config.MessageException;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import top.pmckk.flan.model.*;
import java.util.*;

public final class YamlCodec {
    private YamlCodec() {}
    public static String claim(Claim claim) {
        YamlConfiguration y = new YamlConfiguration(); writeClaim(y, claim); return y.saveToString();
    }
    public static Claim claim(String payload) throws Exception {
        YamlConfiguration y = new YamlConfiguration(); y.loadFromString(payload); return readClaim(y);
    }
    public static void writeClaim(ConfigurationSection y, Claim c) {
        y.set("id", c.id().toString()); y.set("world", c.world().toString());
        y.set("owner", c.owner() == null ? "admin" : c.owner().toString());
        y.set("parent", c.parent() == null ? null : c.parent().toString()); y.set("name", c.name());
        Bounds b = c.bounds(); y.set("bounds", List.of(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
        y.set("three-dimensional", c.threeDimensional());
        y.set("entry-title", c.entryTitle()); y.set("entry-subtitle", c.entrySubtitle());
        if (c.teleportPoint() != null) {
            TeleportPoint point = c.teleportPoint();
            y.set("teleport.x", point.x()); y.set("teleport.y", point.y()); y.set("teleport.z", point.z());
            y.set("teleport.yaw", point.yaw()); y.set("teleport.pitch", point.pitch());
        }
        c.flags().forEach((p, v) -> y.set("flags." + p.name(), v));
        c.members().forEach((id, g) -> y.set("members." + id, g));
        c.groups().forEach((g, perms) -> y.set("groups." + g, perms.stream().map(Enum::name).sorted().toList()));
        c.playerFlags().forEach((id, flags) -> flags.forEach((permission, value) -> y.set("player-flags." + id + "." + permission.name(), value)));
        y.set("excluded-members", c.excludedMembers().stream().map(UUID::toString).sorted().toList());
    }
    public static Claim readClaim(ConfigurationSection y) {
        if (!y.isList("bounds") || !y.isString("id") || !y.isString("world") || !y.isString("owner") || !y.isString("name"))
            throw new MessageException("errors.claim-fields");
        for (String key : List.of("flags", "members", "groups", "player-flags", "teleport")) if (y.contains(key) && !y.isConfigurationSection(key))
            throw new MessageException("errors.claim-mapping", "key", key);
        List<Integer> b = y.getIntegerList("bounds");
        if (b.size() != 6 || y.getList("bounds").stream().anyMatch(v -> !(v instanceof Integer))) throw new MessageException("errors.claim-bounds");
        if (y.contains("three-dimensional") && !y.isBoolean("three-dimensional")) throw new MessageException("errors.claim-dimension");
        for (String key : List.of("entry-title", "entry-subtitle"))
            if (y.contains(key) && !y.isString(key)) throw new MessageException("errors.entry-text");
        Map<ClaimPermission, Boolean> flags = new EnumMap<>(ClaimPermission.class);
        ConfigurationSection fs = y.getConfigurationSection("flags");
        if (fs != null) for (String key : fs.getKeys(false)) {
            if (!fs.isBoolean(key)) throw new MessageException("errors.flag-boolean");
            flags.put(ClaimPermission.parse(key), fs.getBoolean(key));
        }
        Map<UUID, String> members = new HashMap<>(); ConfigurationSection ms = y.getConfigurationSection("members");
        if (ms != null) for (String key : ms.getKeys(false)) members.put(UUID.fromString(key), Objects.requireNonNull(ms.getString(key)));
        Map<String, Set<ClaimPermission>> groups = new HashMap<>(); ConfigurationSection gs = y.getConfigurationSection("groups");
        if (gs != null) for (String key : gs.getKeys(false)) {
            Set<ClaimPermission> perms = EnumSet.noneOf(ClaimPermission.class);
            for (String entry : gs.getStringList(key)) perms.add(ClaimPermission.parse(entry));
            groups.put(key, perms);
        }
        TeleportPoint point = null;
        if (y.contains("teleport")) {
            ConfigurationSection tp = y.getConfigurationSection("teleport");
            if (tp == null) throw new MessageException("errors.teleport-point");
            for (String key : List.of("x", "y", "z", "yaw", "pitch"))
                if (!(tp.get(key) instanceof Number)) throw new MessageException("errors.teleport-point");
            point = new TeleportPoint(tp.getDouble("x"), tp.getDouble("y"), tp.getDouble("z"), (float) tp.getDouble("yaw"), (float) tp.getDouble("pitch"));
        }
        String owner = Objects.requireNonNull(y.getString("owner")), parent = y.getString("parent");
        Map<UUID, Map<ClaimPermission, Boolean>> individual = new HashMap<>();
        ConfigurationSection pf = y.getConfigurationSection("player-flags");
        if (pf != null) for (String id : pf.getKeys(false)) {
            ConfigurationSection values = pf.getConfigurationSection(id);
            if (values == null) throw new MessageException("errors.claim-mapping", "key", "player-flags." + id);
            Map<ClaimPermission, Boolean> permissions = new EnumMap<>(ClaimPermission.class);
            for (String key : values.getKeys(false)) {
                ClaimPermission permission = ClaimPermission.parse(key);
                if (!values.isBoolean(key) || !permission.personal()) throw new MessageException("errors.personal-only");
                permissions.put(permission, values.getBoolean(key));
            }
            individual.put(UUID.fromString(id), permissions);
        }
        Set<UUID> excluded = new HashSet<>();
        if (y.contains("excluded-members")) {
            if (!y.isList("excluded-members") || y.getList("excluded-members").stream().anyMatch(value -> !(value instanceof String)))
                throw new MessageException("errors.claim-mapping", "key", "excluded-members");
            for (String id : y.getStringList("excluded-members")) excluded.add(UUID.fromString(id));
        }
        return new Claim(UUID.fromString(Objects.requireNonNull(y.getString("id"))), UUID.fromString(Objects.requireNonNull(y.getString("world"))),
                owner.equals("admin") ? null : UUID.fromString(owner), parent == null ? null : UUID.fromString(parent),
                Objects.requireNonNull(y.getString("name")), new Bounds(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)), flags, members, groups,
                y.contains("three-dimensional") ? y.getBoolean("three-dimensional") : null, individual, excluded, y.getString("entry-title"), y.getString("entry-subtitle"), point);
    }
    public static String encode(Snapshot snapshot) {
        YamlConfiguration y = new YamlConfiguration(); y.set("schema-version", 1);
        for (Claim c : snapshot.claims()) writeClaim(y.createSection("claims." + c.id()), c);
        for (PlayerAccount p : snapshot.players()) {
            ConfigurationSection s = y.createSection("players." + p.id());
            s.set("name", p.name()); s.set("allowance", p.allowance()); s.set("accrual-seconds", p.accrualSeconds());
        }
        return y.saveToString();
    }
    public static Snapshot decode(String payload) throws Exception {
        YamlConfiguration y = new YamlConfiguration(); y.loadFromString(payload);
        if (y.getInt("schema-version") != 1) throw new MessageException("errors.schema-version");
        for (String key : List.of("claims", "players")) if (y.contains(key) && !y.isConfigurationSection(key))
            throw new MessageException("errors.data-mapping", "key", key);
        List<Claim> claims = new ArrayList<>(); List<PlayerAccount> players = new ArrayList<>();
        ConfigurationSection cs = y.getConfigurationSection("claims"), ps = y.getConfigurationSection("players");
        if (cs != null) for (String key : cs.getKeys(false)) {
            Claim c = readClaim(Objects.requireNonNull(cs.getConfigurationSection(key)));
            if (!c.id().toString().equals(key)) throw new MessageException("errors.claim-path-id");
            claims.add(c);
        }
        if (ps != null) for (String key : ps.getKeys(false)) {
            ConfigurationSection s = Objects.requireNonNull(ps.getConfigurationSection(key));
            if (!s.isString("name") || !s.isLong("allowance") && !s.isInt("allowance")
                    || !s.isLong("accrual-seconds") && !s.isInt("accrual-seconds")) throw new MessageException("errors.player-fields");
            players.add(new PlayerAccount(UUID.fromString(key), s.getString("name", key), s.getLong("allowance"), s.getLong("accrual-seconds")));
        }
        return new Snapshot(claims, players);
    }
}
