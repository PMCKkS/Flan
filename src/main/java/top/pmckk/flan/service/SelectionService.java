package top.pmckk.flan.service;

import top.pmckk.flan.config.MessageException;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import top.pmckk.flan.Flan;
import top.pmckk.flan.config.Messages;
import top.pmckk.flan.config.BorderSettings;
import top.pmckk.flan.config.BorderSettings.Kind;
import top.pmckk.flan.service.BorderSession.Outline;
import top.pmckk.flan.model.Bounds;
import top.pmckk.flan.model.Claim;
import org.bukkit.block.data.BlockData;
import java.util.*;

public final class SelectionService {
    private final Flan plugin;
    private final NamespacedKey marker;
    private final Map<UUID, ClaimSelection> selections = new HashMap<>();
    private final Map<UUID, Display> displays = new HashMap<>();
    private record Marker(BorderGeometry.Position position, Material material, boolean terrain, int outline) {}
    public SelectionService(Flan plugin) { this.plugin = plugin; marker = new NamespacedKey(plugin, "claim_tool"); }
    public ItemStack tool() {
        var s = plugin.settings(); ItemStack stack = new ItemStack(s.toolMaterial()); ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(s.toolName().isEmpty() ? plugin.messages().formatted("tool.name") : Messages.color(s.toolName()));
        meta.setLore(s.toolLore().isEmpty() ? plugin.messages().lines("tool.lore") : s.toolLore().stream().map(Messages::color).toList());
        if (s.customModelData() >= 0) meta.setCustomModelData(s.customModelData());
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1); stack.setItemMeta(meta); return stack;
    }
    public boolean isTool(ItemStack item) {
        if (item == null || item.getType() != plugin.settings().toolMaterial()) return false;
        return !plugin.settings().requireMarker() || (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE));
    }
    private boolean holdingTool(Player player) {
        return plugin.settings().border().showHoldingTool() && player.hasPermission("flan.use") && player.hasPermission("flan.claim")
                && !plugin.settings().worlds().disabled(player.getWorld())
                && (isTool(player.getInventory().getItemInMainHand()) || isTool(player.getInventory().getItemInOffHand()));
    }
    private List<Outline> nearby(Player player) {
        Location location = player.getLocation();
        int limit = Math.min(8, plugin.settings().borderPoints() / 8);
        return plugin.registry().nearby(player.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                plugin.settings().border().nearbyRadius(), limit).stream()
                .map(c -> outline(player, c, c.parent() == null ? Kind.MAIN : Kind.SUB)).toList();
    }
    private Location target(Player player) {
        Location eye = player.getEyeLocation(); World world = player.getWorld();
        org.bukkit.util.Vector direction = eye.getDirection().multiply(0.25);
        Location cursor = eye.clone();
        int distance = plugin.settings().selectionDistance();
        for (int step = 0; step < distance * 4; step++) {
            cursor.add(direction);
            if (cursor.getBlockY() < world.getMinHeight() || cursor.getBlockY() >= world.getMaxHeight()
                    || !world.isChunkLoaded(cursor.getBlockX() >> 4, cursor.getBlockZ() >> 4)) break;
            Block block = world.getBlockAt(cursor);
            if (!block.isPassable() || block.isLiquid()) return block.getLocation();
        }
        return threeDimensional(player) ? cursor : player.getLocation();
    }
    private List<Outline> preview(Player player, Location target, long availableArea) {
        World world = player.getWorld();
        ClaimSelection selection = selections.get(player.getUniqueId());
        if (selection == null) selection = new ClaimSelection();
        if (selection.anchor(world.getUID()) == null && plugin.at(player.getLocation()) != null) return List.of();
        Location center = selection.anchor(world.getUID()) == null ? player.getLocation() : target;
        Bounds bounds = selection.preview(world.getUID(), center.getBlockX(), center.getBlockY(), center.getBlockZ(),
                world.getMinHeight(), world.getMaxHeight(), threeDimensional(player),
                availableArea, plugin.settings().selectionDistance());
        return bounds == null ? List.of() : List.of(new Outline(bounds, Kind.EDIT, threeDimensional(player)));
    }
    public void resetPoints(Player player) {
        ClaimSelection selection = selections.get(player.getUniqueId());
        if (selection != null) selection.clearPoints();
    }
    public void refreshTools() {
        // Discover newly held tools quickly; each active display follows the view every tick.
        for (Player player : Bukkit.getOnlinePlayers())
            if (!displays.containsKey(player.getUniqueId()) && holdingTool(player)) show(player, List.of());
    }
    public void select(Player player, Block block, int corner) {
        if (plugin.settings().worlds().disabled(player.getWorld())) throw new MessageException("errors.world-disabled");
        if (block.getWorld() != player.getWorld() || block.getLocation().distanceSquared(player.getLocation()) > (double) plugin.settings().selectionDistance() * plugin.settings().selectionDistance())
            throw new MessageException("errors.selection-distance");
        if (block.getY() < player.getWorld().getMinHeight() || block.getY() >= player.getWorld().getMaxHeight())
            throw new MessageException("errors.selection-height");
        ClaimSelection selection = selections.computeIfAbsent(player.getUniqueId(), k -> new ClaimSelection());
        selection.select(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), corner);
        plugin.messages().send(player, "selection", "corner", corner + 1, "x", block.getX(), "y", block.getY(), "z", block.getZ());
        if (!selection.complete()) show(player, List.of());
        if (selection.complete()) {
            Bounds b = bounds(player); show(player, b);
            plugin.messages().send(player, "commands.selection-area", "area", b.area());
            plugin.messages().send(player, "commands.selection-height", "mode", modeLabel(threeDimensional(player)), "minimum", b.minY(), "maximum", b.maxY());
        }
    }
    public Bounds bounds(Player player) {
        return bounds(player, threeDimensional(player));
    }
    public Bounds bounds(Player player, boolean threeDimensional) {
        ClaimSelection selection = selections.get(player.getUniqueId());
        if (selection == null) throw new MessageException("errors.selection-required");
        World world = player.getWorld();
        return selection.bounds(world.getUID(), world.getMinHeight(), world.getMaxHeight(), threeDimensional && !plugin.settings().worlds().ignoreY());
    }
    public boolean threeDimensional(Player player) {
        if (plugin.settings().worlds().ignoreY()) return false;
        ClaimSelection selection = selections.get(player.getUniqueId());
        return selection == null ? plugin.settings().threeDimensional() : selection.mode(plugin.settings().threeDimensional());
    }
    private String modeLabel(boolean threeDimensional) { return plugin.messages().text(threeDimensional ? "labels.mode-3d" : "labels.mode-2d"); }
    public void mode(Player player, boolean threeDimensional) {
        if (threeDimensional && plugin.settings().worlds().ignoreY()) throw new MessageException("errors.ignore-y");
        ClaimSelection selection = selections.computeIfAbsent(player.getUniqueId(), k -> new ClaimSelection());
        selection.setMode(threeDimensional);
        plugin.messages().send(player, "commands.claim-mode", "mode", modeLabel(threeDimensional));
        if (selection.complete()) show(player, bounds(player));
    }
    public void mode(Player player) {
        plugin.messages().send(player, "commands.claim-mode", "mode", modeLabel(threeDimensional(player)));
    }
    public void show(Player player, Bounds b) {
        show(player, List.of(new Outline(b, Kind.EDIT, threeDimensional(player))));
    }
    private Outline outline(Player player, Claim claim, Kind kind) {
        World world = player.getWorld();
        return new Outline(claim.bounds(), kind, claim.isThreeDimensional(world.getMinHeight(), world.getMaxHeight()));
    }
    public void show(Player player, Claim claim) {
        if (claim.world().equals(player.getWorld().getUID()))
            show(player, List.of(outline(player, claim, claim.parent() == null ? Kind.MAIN : Kind.SUB)));
    }
    public void showConflicts(Player player, Claim proposed) {
        List<Outline> outlines = new ArrayList<>();
        outlines.add(outline(player, proposed, Kind.EDIT));
        // Keep a bounded total even when a proposed region intersects many claims.
        int limit = Math.min(8, plugin.settings().borderPoints() / 8 - 1);
        plugin.registry().conflicts(proposed).stream().limit(Math.max(1, limit))
                .forEach(c -> outlines.add(outline(player, c, Kind.CONFLICT)));
        show(player, outlines);
    }
    private void show(Player player, List<Outline> outlines) {
        clearDisplay(player.getUniqueId());
        Display display = new Display(player, outlines);
        displays.put(player.getUniqueId(), display);
        display.runTaskTimer(plugin, 0, 1);
    }
    private final class Display extends BukkitRunnable {
        private final Player player;
        private final World world;
        private final top.pmckk.flan.config.Settings settings = plugin.settings();
        private final List<Marker> markers = new ArrayList<>();
        private final Map<BorderGeometry.Position, List<BorderGeometry.Position>> terrain = new HashMap<>();
        private final EnumMap<Material, BlockData> data = new EnumMap<>(Material.class);
        private final BlockOverlay<BlockData> overlay;
        private final BorderSession session;
        private List<Outline> rendered = List.of();
        private int outlineBudget;
        private int renderHeight;
        private boolean holding;
        private Location target, lastView;
        private List<Outline> nearby = List.of();
        private long availableArea;
        private int ticks;
        private final Map<Location, BlockData> packets = new LinkedHashMap<>();
        private final List<TileState> tileRestores = new ArrayList<>();

        Display(Player player, List<Outline> outlines) {
            this.player = player; world = player.getWorld();
            session = new BorderSession(outlines, settings.borderSeconds() * 20);
            overlay = new BlockOverlay<>((p, block) -> packets.put(location(p), block), p -> {
                if (player.isOnline() && player.getWorld() == world && loaded(p)) {
                    Block actual = world.getBlockAt(p.x(), p.y(), p.z());
                    packets.put(location(p), actual.getBlockData());
                    if (actual.getState() instanceof TileState tile) tileRestores.add(tile);
                }
            });
        }
        private void updateTool() {
            boolean wasHolding = holding;
            holding = holdingTool(player);
            if (holding) {
                // Nearby claims and allowance need less frequent updates than the live cursor.
                boolean refresh = !wasHolding || ticks % 10 == 0;
                if (refresh) {
                    nearby = nearby(player);
                    availableArea = Math.min(settings.maximumArea(), plugin.registry().remaining(player.getUniqueId()));
                }
                Location view = player.getEyeLocation();
                if (refresh || !view.equals(lastView)) {
                    target = target(player); lastView = view;
                }
                session.tool(true, nearby, preview(player, target, availableArea));
            } else {
                target = null; lastView = null; nearby = List.of();
                session.tool(false, List.of());
            }
            // Recheck edited terrain periodically while retaining overlapping columns during dragging.
            if (ticks % 20 == 0) terrain.clear();
        }
        private void flush() {
            if (!packets.isEmpty()) {
                List<BlockState> changes = new ArrayList<>(packets.size());
                // Spigot takes located snapshots; creating/copying them never modifies the world.
                packets.forEach((location, block) -> changes.add(block.createBlockState().copy(location)));
                player.sendBlockChanges(changes);
            }
            for (TileState tile : tileRestores) player.sendBlockUpdate(tile.getLocation(), tile);
            packets.clear(); tileRestores.clear();
        }
        private void rebuild(List<Outline> outlines, int height) {
            rendered = outlines; renderHeight = height; markers.clear();
            int budget = Math.max(8, (settings.borderPoints() - 2) / Math.max(1, outlines.size()));
            outlineBudget = budget;
            for (int index = 0; index < outlines.size(); index++) {
                Outline outline = outlines.get(index);
                Bounds b = outline.bounds();
                boolean flat = !outline.threeDimensional();
                for (var p : BorderGeometry.markers(b, !flat, height, budget))
                    markers.add(new Marker(p, settings.border().material(outline.kind()), flat, index));
            }
            Set<BorderGeometry.Position> columns = new HashSet<>();
            for (Marker marker : markers) if (marker.terrain()) columns.add(marker.position());
            terrain.keySet().retainAll(columns);
        }
        private Location location(BorderGeometry.Position p) { return new Location(world, p.x(), p.y(), p.z()); }
        private boolean loaded(BorderGeometry.Position p) {
            return p.y() >= world.getMinHeight() && p.y() < world.getMaxHeight() && world.isChunkLoaded(p.x() >> 4, p.z() >> 4);
        }
        private boolean visible(BorderGeometry.Position p, Location eye) {
            int distance = Math.min(8, Math.min(Bukkit.getViewDistance(), Math.max(2, player.getClientViewDistance())));
            return loaded(p) && Math.abs((long) (p.x() >> 4) - (eye.getBlockX() >> 4)) <= distance
                    && Math.abs((long) (p.z() >> 4) - (eye.getBlockZ() >> 4)) <= distance;
        }
        @Override public void run() {
            if (!player.isOnline() || player.getWorld() != world) { stop(); return; }
            updateTool();
            if (!session.advance()) { stop(); return; }
            Location eye = player.getLocation();
            List<Outline> outlines = session.outlines(Math.max(1, (settings.borderPoints() - 2) / 8));
            if (outlines.isEmpty() && !holding) { stop(); return; }
            ClaimSelection selected = selections.get(player.getUniqueId());
            ClaimSelection.Point anchor = selected == null ? null : selected.anchor(world.getUID());
            int height = holding && anchor != null ? anchor.y() : eye.getBlockY();
            if (!outlines.equals(rendered) || Math.abs((long) height - renderHeight) >= 3) rebuild(outlines, height);
            Map<BorderGeometry.Position, BlockData> next = new LinkedHashMap<>();
            Map<Integer, Set<BorderGeometry.Position>> assigned = new HashMap<>();
            for (Marker marker : markers) {
                var p = marker.position();
                if (!visible(p, eye)) continue;
                List<BorderGeometry.Position> positions = marker.terrain() ? terrain.computeIfAbsent(p, key ->
                        BorderTerrain.heights(world.getMinHeight(), world.getMaxHeight() - 1, key.y(),
                                y -> world.getBlockAt(key.x(), y, key.z()).getType().isSolid(),
                                y -> world.getBlockAt(key.x(), y, key.z()).isLiquid()).stream()
                                .map(y -> new BorderGeometry.Position(key.x(), y, key.z())).toList()) : List.of(p);
                for (var position : positions) {
                    Set<BorderGeometry.Position> group = assigned.computeIfAbsent(marker.outline(), k -> new HashSet<>());
                    if (group.size() >= outlineBudget && !group.contains(position)) break;
                    group.add(position);
                    next.put(position, data.computeIfAbsent(marker.material(), Material::createBlockData));
                }
            }
            // Reserve room for the cursor and fixed anchor, including low point limits.
            int reserved = holding ? 2 : 0;
            while (next.size() > settings.borderPoints() - reserved) next.remove(next.keySet().iterator().next());
            if (holding) {
                if (target != null) {
                    var p = new BorderGeometry.Position(target.getBlockX(), target.getBlockY(), target.getBlockZ());
                    if (visible(p, eye)) next.put(p, data.computeIfAbsent(Material.YELLOW_WOOL, Material::createBlockData));
                }
                ClaimSelection selection = selections.get(player.getUniqueId());
                ClaimSelection.Point fixed = selection == null ? null : selection.anchor(world.getUID());
                if (fixed != null) {
                    var p = new BorderGeometry.Position(fixed.x(), fixed.y(), fixed.z());
                    if (visible(p, eye)) next.put(p, data.computeIfAbsent(Material.SEA_LANTERN, Material::createBlockData));
                }
            }
            if (settings.border().mode() == BorderSettings.Mode.BLOCKS) {
                // Resend periodically for client-side block interactions, otherwise send only changes.
                overlay.update(next, ticks % 10 == 0); flush();
            } else if (ticks % 2 == 0) {
                for (var p : next.keySet()) player.spawnParticle(settings.borderParticle(), p.x() + .5, p.y() + 1.15, p.z() + .5, 1, 0, 0, 0, 0);
            }
            ticks++;
        }
        void stop() { cancel(); overlay.clear(); flush(); displays.remove(player.getUniqueId(), this); }
    }
    public void clearDisplay(UUID id) { Display display = displays.get(id); if (display != null) display.stop(); }
    public void clearDisplays() {
        for (Display display : List.copyOf(displays.values())) display.stop();
    }
    public void clear(UUID id) { selections.remove(id); clearDisplay(id); }
}
