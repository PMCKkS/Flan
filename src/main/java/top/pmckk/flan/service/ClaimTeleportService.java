package top.pmckk.flan.service;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import top.pmckk.flan.Flan;
import top.pmckk.flan.config.*;
import top.pmckk.flan.model.*;
import top.pmckk.flan.network.*;
import top.pmckk.flan.network.TeleportWire.*;
import java.util.*;
import java.util.logging.Level;

/** Bukkit-side landing checks and signed Velocity transport. All world access stays on the server thread. */
public final class ClaimTeleportService implements Listener, PluginMessageListener {
    private record Waiting(UUID id, long deadline) {}
    private final Flan plugin;
    private TeleportSettings settings;
    private final Map<UUID, Waiting> waiting = new HashMap<>();
    private final Map<UUID, Intent> arrivals = new HashMap<>();
    private final Map<UUID, Long> completed = new HashMap<>();
    private final Map<UUID, List<String>> remoteNames = new HashMap<>();
    private List<Entry> published;
    private long generation, lastWarning;
    private int seconds;
    private BukkitTask task;
    private static final Set<Material> HAZARDS = EnumSet.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE);

    public ClaimTeleportService(Flan plugin, TeleportSettings settings) { this.plugin = plugin; this.settings = settings; }
    public void start() {
        register();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        if (settings.velocity()) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            publish(true); for (Player player : Bukkit.getOnlinePlayers()) ready(player);
        }, 2);
    }
    private void register() {
        if (!settings.velocity()) return;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, TeleportWire.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, TeleportWire.CHANNEL, this);
    }
    public void reload(TeleportSettings next) {
        unregister(); settings = next; published = null; waiting.clear(); arrivals.clear(); completed.clear(); remoteNames.clear(); register();
        if (settings.velocity()) { publish(true); for (Player player : Bukkit.getOnlinePlayers()) ready(player); }
    }
    private void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, TeleportWire.CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, TeleportWire.CHANNEL);
    }
    public void close() { unregister(); if (task != null) task.cancel(); waiting.clear(); arrivals.clear(); remoteNames.clear(); }
    private void send(Player player, Message packet) {
        if (settings.velocity() && player.isOnline()) player.sendPluginMessage(plugin, TeleportWire.CHANNEL, TeleportWire.encode(packet, settings.secret(), System.currentTimeMillis()));
    }
    private void ready(Player player) { send(player, new Ready(player.getUniqueId())); }
    private List<Entry> entries() {
        return plugin.registry().all().stream().map(c -> new Entry(c.id(), c.owner(), c.name())).sorted(Comparator.comparing(Entry::id)).toList();
    }
    public void publish(boolean force) {
        try { publishNow(force); } catch (Exception error) { warn(error); }
    }
    private void publishNow(boolean force) {
        if (!settings.velocity() || plugin.maintenance() || Bukkit.getOnlinePlayers().isEmpty()) return;
        List<Entry> entries = entries(); if (!force && entries.equals(published)) return;
        int pages = Math.max(1, (entries.size() + TeleportWire.PAGE_SIZE - 1) / TeleportWire.PAGE_SIZE);
        if (pages > TeleportWire.MAX_PAGES) { warn(new MessageException("errors.teleport-catalog")); return; }
        Player carrier = Bukkit.getOnlinePlayers().iterator().next(); generation = Math.max(System.currentTimeMillis(), generation + 1);
        for (int page = 0; page < pages; page++) send(carrier, new Catalog(generation, page, pages,
                entries.subList(page * TeleportWire.PAGE_SIZE, Math.min(entries.size(), (page + 1) * TeleportWire.PAGE_SIZE))));
        published = entries;
    }
    private void tick() {
        long now = System.currentTimeMillis();
        waiting.entrySet().removeIf(entry -> {
            if (entry.getValue().deadline() >= now) return false;
            Player player = Bukkit.getPlayer(entry.getKey()); if (player != null) plugin.messages().send(player, "network.timeout"); return true;
        });
        completed.values().removeIf(deadline -> deadline < now);
        for (Intent intent : List.copyOf(arrivals.values())) applyArrival(intent);
        publish(++seconds % 30 == 0);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            publish(true); ready(event.getPlayer());
            for (Intent intent : List.copyOf(arrivals.values())) if (intent.player().equals(event.getPlayer().getUniqueId())) applyArrival(intent);
        }, 2);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { waiting.remove(event.getPlayer().getUniqueId()); remoteNames.remove(event.getPlayer().getUniqueId()); }
    @Override public void onPluginMessageReceived(String channel, Player carrier, byte[] bytes) {
        if (!settings.velocity() || !TeleportWire.CHANNEL.equals(channel)) return;
        try {
            Message message = TeleportWire.decode(bytes, settings.secret(), System.currentTimeMillis());
            if (message instanceof Names names) {
                if (Bukkit.getPlayer(names.player()) != null) remoteNames.put(names.player(), names.names());
            } else if (message instanceof Status status) {
                Waiting request = waiting.get(status.player());
                if (request == null || !request.id().equals(status.id())) return;
                waiting.remove(status.player());
                Player player = Bukkit.getPlayer(status.player());
                if (player != null && !status.key().equals("network.accepted")) plugin.messages().send(player, status.key());
            } else if (message instanceof Intent intent) {
                Player player = Bukkit.getPlayer(intent.player());
                if (completed.containsKey(intent.id())) { if (player != null) send(player, new Ack(intent.id(), intent.player())); return; }
                if (intent.deadline() < System.currentTimeMillis() || intent.deadline() > System.currentTimeMillis() + 60000) return;
                Intent pending = arrivals.putIfAbsent(intent.id(), intent);
                if (pending != null && !pending.equals(intent)) return;
                Bukkit.getScheduler().runTask(plugin, () -> applyArrival(intent));
            }
        } catch (Exception error) { warn(error); }
    }
    private void warn(Throwable error) {
        long now = System.currentTimeMillis(); if (now - lastWarning < 10000) return; lastWarning = now;
        plugin.messages().log(plugin.getLogger(), Level.WARNING, "logs.teleport-network", null, "reason", plugin.messages().error(error));
    }
    private void applyArrival(Intent intent) {
        if (!arrivals.containsKey(intent.id())) return;
        if (intent.deadline() < System.currentTimeMillis()) { arrivals.remove(intent.id()); return; }
        Player player = Bukkit.getPlayer(intent.player()); if (player == null || !player.isOnline()) return;
        arrivals.remove(intent.id()); completed.put(intent.id(), System.currentTimeMillis() + 60000);
        try { apply(player, plugin.registry().get(intent.claim())); }
        catch (Exception error) { plugin.messages().sendError(player, error); if (!(error instanceof IllegalArgumentException)) warn(error); }
        finally { send(player, new Ack(intent.id(), intent.player())); }
    }
    public List<String> names(Player player) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        plugin.registry().all().stream().filter(c -> player.hasPermission("flan.admin") || player.getUniqueId().equals(c.owner())).forEach(c -> names.add(c.name()));
        if (settings.velocity()) names.addAll(remoteNames.getOrDefault(player.getUniqueId(), List.of())); return List.copyOf(names);
    }
    public void teleport(Player player, String selector) {
        plugin.requireNode(player, "flan.tp");
        ClaimDirectory local = new ClaimDirectory(); local.replace("local", entries());
        try { apply(player, plugin.registry().get(local.resolve(player.getUniqueId(), selector, player.hasPermission("flan.admin")).entry().id())); return; }
        catch (ClaimDirectory.LookupException error) {
            if (!error.key().equals("network.not-found") || !settings.velocity()) throw new MessageException(error.key());
        }
        if (waiting.containsKey(player.getUniqueId())) throw new MessageException("network.busy");
        UUID id = UUID.randomUUID(); waiting.put(player.getUniqueId(), new Waiting(id, System.currentTimeMillis() + settings.timeoutSeconds() * 1000L));
        // Publish before requesting, so immediate post-creation requests see the latest names.
        publish(false); send(player, new Request(id, player.getUniqueId(), selector, player.hasPermission("flan.admin")));
        plugin.messages().send(player, "network.requesting");
    }
    public Claim initialize(Claim claim, Player creator) { return claim.withTeleportPoint(defaultPoint(claim, creator)); }
    public Claim setPoint(Player player, Claim claim) {
        plugin.writable(); plugin.requireOwner(player, claim); plugin.requireNode(player, "flan.tp.set");
        if (!claim.world().equals(player.getWorld().getUID()) || !point(player.getLocation()).inside(claim.bounds())) throw new MessageException("errors.teleport-outside");
        if (!safe(player.getLocation(), claim)) throw new MessageException("errors.teleport-unsafe");
        Claim updated = claim.withTeleportPoint(point(player.getLocation())); plugin.registry().update(updated); plugin.changed();
        plugin.messages().send(player, "commands.teleport-set"); return updated;
    }
    public void apply(Player player, Claim claim) {
        if (plugin.maintenance()) throw new MessageException("errors.maintenance");
        plugin.requireNode(player, "flan.tp"); plugin.requireOwner(player, claim);
        World world = Bukkit.getWorld(claim.world()); if (world == null) throw new MessageException("errors.teleport-world");
        if (claim.teleportPoint() == null) { plugin.writable(); claim = initialize(claim, player); plugin.registry().update(claim); plugin.changed(); }
        TeleportPoint saved = claim.teleportPoint(); Location destination = location(world, saved);
        if (!safe(destination, claim)) destination = search(world, claim, destination, saved.yaw(), saved.pitch());
        if (destination == null) throw new MessageException("errors.teleport-unsafe");
        if (!plugin.allowed(player, destination, ClaimPermission.ENTER) || !plugin.allowed(player, destination, ClaimPermission.TELEPORT)) throw new MessageException("errors.teleport-denied");
        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) throw new MessageException("errors.teleport-cancelled");
        plugin.messages().send(player, "commands.teleport-done", "name", claim.name());
    }
    private static TeleportPoint point(Location location) { return new TeleportPoint(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()); }
    private static Location location(World world, TeleportPoint point) { return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch()); }
    private TeleportPoint defaultPoint(Claim claim, Player creator) {
        World world = Bukkit.getWorld(claim.world()); if (world == null) throw new MessageException("errors.teleport-world");
        Location current = creator.getLocation(); Bounds b = claim.bounds();
        if (current.getWorld() == world && safe(current, claim)) return point(current);
        double x = ((long) b.minX() + b.maxX()) / 2.0 + .5, z = ((long) b.minZ() + b.maxZ()) / 2.0 + .5;
        double y = Math.clamp(current.getY(), b.minY(), b.maxY());
        Location preferred = new Location(world, x, y, z, current.getYaw(), current.getPitch());
        Location safe = search(world, claim, preferred, current.getYaw(), current.getPitch());
        return point(safe == null ? preferred : safe);
    }
    private Location search(World world, Claim claim, Location preferred, float yaw, float pitch) {
        Bounds b = claim.bounds(); int min = Math.max(b.minY(), world.getMinHeight() + 1), max = Math.min(b.maxY(), world.getMaxHeight() - 2);
        if (min > max) return null;
        int centerX = b.minX() + (int) (((long) b.maxX() - b.minX()) / 2), centerZ = b.minZ() + (int) (((long) b.maxZ() - b.minZ()) / 2);
        int[][] columns = {{Math.clamp(preferred.getBlockX(), b.minX(), b.maxX()), Math.clamp(preferred.getBlockZ(), b.minZ(), b.maxZ())},
                {centerX, centerZ}, {b.minX(), b.minZ()}, {b.maxX(), b.minZ()}, {b.minX(), b.maxZ()}, {b.maxX(), b.maxZ()}};
        Set<Long> checked = new HashSet<>(); int start = Math.clamp(preferred.getBlockY(), min, max);
        for (int[] column : columns) {
            long key = ((long) column[0] << 32) ^ (column[1] & 0xffffffffL); if (!checked.add(key)) continue;
            for (int offset = 0; offset <= max - min; offset++) for (int y : offset == 0 ? new int[]{start} : new int[]{start + offset, start - offset}) {
                if (y < min || y > max) continue;
                Location candidate = new Location(world, column[0] + .5, y, column[1] + .5, yaw, pitch);
                if (safe(candidate, claim)) return candidate;
            }
        }
        return null;
    }
    private boolean safe(Location at, Claim claim) {
        World world = at.getWorld(); if (world == null || !claim.world().equals(world.getUID()) || !point(at).inside(claim.bounds())
                || at.getY() < world.getMinHeight() + 1 || at.getY() + 1.8 >= world.getMaxHeight() || !world.getWorldBorder().isInside(at)) return false;
        Block floor = world.getBlockAt(at.getBlockX(), (int) Math.floor(at.getY() - .01), at.getBlockZ());
        if (!floor.getType().isSolid() || HAZARDS.contains(floor.getType()) || Math.abs(floor.getBoundingBox().getMaxY() - at.getY()) > .02) return false;
        org.bukkit.util.BoundingBox body = new org.bukkit.util.BoundingBox(at.getX() - .3, at.getY(), at.getZ() - .3, at.getX() + .3, at.getY() + 1.8, at.getZ() + .3);
        for (int x = (int) Math.floor(at.getX() - .3); x <= (int) Math.floor(at.getX() + .3); x++)
            for (int z = (int) Math.floor(at.getZ() - .3); z <= (int) Math.floor(at.getZ() + .3); z++)
                for (int y = at.getBlockY(); y <= (int) Math.floor(at.getY() + 1.8); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isLiquid() || HAZARDS.contains(block.getType()) || !block.isPassable() && block.getBoundingBox().overlaps(body)) return false;
                }
        return true;
    }
}
