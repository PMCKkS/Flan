package top.pmckk.flan.listener;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.BlockProjectileSource;
import top.pmckk.flan.Flan;
import top.pmckk.flan.model.*;
import java.util.*;

public final class ProtectionListener implements Listener {
    private final Flan plugin;
    private final Map<UUID, Long> notices = new HashMap<>();
    public ProtectionListener(Flan plugin) { this.plugin = plugin; }
    private boolean deny(Player player, Location at, ClaimPermission permission) {
        if (plugin.allowed(player, at, permission)) return false;
        if (player != null) {
            long now = System.currentTimeMillis();
            if (now - notices.getOrDefault(player.getUniqueId(), 0L) > 1200) {
                notices.put(player.getUniqueId(), now); plugin.messages().send(player, "protected", "permission", plugin.messages().permission(permission));
            }
        } return true;
    }
    private Player actor(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile p && p.getShooter() instanceof Player player) return player;
        if (entity instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (entity instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) return p;
        return null;
    }
    private boolean cross(Location from, Location to, ClaimPermission permission) {
        Claim a = plugin.at(from), b = plugin.at(to);
        if (Objects.equals(a == null ? null : a.id(), b == null ? null : b.id())) return false;
        return !plugin.registry().allowed(a, null, permission) || !plugin.registry().allowed(b, null, permission);
    }
    private List<Location> inventoryLocations(org.bukkit.inventory.Inventory inventory) {
        if (inventory.getHolder() instanceof DoubleChest chest) {
            List<Location> locations = new ArrayList<>();
            if (chest.getLeftSide() instanceof BlockState state) locations.add(state.getLocation());
            if (chest.getRightSide() instanceof BlockState state) locations.add(state.getLocation());
            return locations;
        }
        Location location = inventory.getLocation(); return location == null ? List.of() : List.of(location);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void openInventory(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player player) || e.getInventory().getHolder() == null) return;
        for (Location location : inventoryLocations(e.getInventory())) if (deny(player, location, ClaimPermission.CONTAINER)) { e.setCancelled(true); return; }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void breakBlock(BlockBreakEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation(), ClaimPermission.BUILD)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void place(BlockPlaceEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation(), ClaimPermission.BUILD)) { e.setCancelled(true); return; }
        if (e instanceof BlockMultiPlaceEvent multi) for (BlockState state : multi.getReplacedBlockStates()) if (deny(e.getPlayer(), state.getLocation(), ClaimPermission.BUILD)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH) public void interact(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.useInteractedBlock() == Event.Result.DENY && e.useItemInHand() == Event.Result.DENY) return;
        Block b = e.getClickedBlock();
        if (e.getAction() == Action.PHYSICAL) {
            if (deny(e.getPlayer(), b.getLocation(), b.getType() == Material.FARMLAND || b.getType() == Material.TURTLE_EGG ? ClaimPermission.BUILD : ClaimPermission.INTERACT)) e.setCancelled(true); return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ClaimPermission permission = b.getState() instanceof InventoryHolder || b.getType() == Material.ENDER_CHEST ? ClaimPermission.CONTAINER : ClaimPermission.INTERACT;
        boolean blocked = deny(e.getPlayer(), b.getLocation(), permission);
        if (!blocked && e.hasItem()) {
            Material item = e.getItem().getType(); String name = item.name();
            boolean changes = item.isBlock() || item == Material.BONE_MEAL || item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE
                    || item == Material.SHEARS || name.endsWith("_AXE") || name.endsWith("_HOE") || name.endsWith("_SHOVEL")
                    || name.endsWith("_BUCKET") || name.endsWith("_SPAWN_EGG") || item == Material.END_CRYSTAL;
            if (changes) blocked = deny(e.getPlayer(), b.getLocation(), ClaimPermission.BUILD) || deny(e.getPlayer(), b.getRelative(e.getBlockFace()).getLocation(), ClaimPermission.BUILD);
        }
        if (blocked) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void bucketEmpty(PlayerBucketEmptyEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation(), ClaimPermission.BUILD)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void bucketFill(PlayerBucketFillEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation(), ClaimPermission.BUILD)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void bucketEntity(PlayerBucketEntityEvent e) { if (deny(e.getPlayer(), e.getEntity().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void useEntity(PlayerInteractEntityEvent e) { if (deny(e.getPlayer(), e.getRightClicked().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void useEntityAt(PlayerInteractAtEntityEvent e) { useEntity(e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void entityPlace(EntityPlaceEvent e) { if (deny(e.getPlayer(), e.getEntity().getLocation(), ClaimPermission.BUILD)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void armorStand(PlayerArmorStandManipulateEvent e) { if (deny(e.getPlayer(), e.getRightClicked().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void damage(EntityDamageByEntityEvent e) {
        Player source = actor(e.getDamager()); ClaimPermission p = e.getEntity() instanceof Player ? ClaimPermission.PVP : ClaimPermission.ENTITY;
        boolean blocked;
        if (source != null) {
            blocked = deny(source, e.getEntity().getLocation(), p);
            if (p == ClaimPermission.PVP) blocked |= deny(source, source.getLocation(), p);
        } else if (e.getDamager() instanceof Projectile || e.getDamager() instanceof TNTPrimed || e.getDamager() instanceof Creeper || e.getDamager() instanceof WitherSkull) {
            blocked = deny(null, e.getEntity().getLocation(), e.getDamager() instanceof TNTPrimed || e.getDamager() instanceof Creeper ? ClaimPermission.EXPLOSION : p);
        } else blocked = false;
        if (blocked) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void splash(PotionSplashEvent e) {
        Player player = actor(e.getPotion());
        for (LivingEntity entity : e.getAffectedEntities()) {
            ClaimPermission p = entity instanceof Player ? ClaimPermission.PVP : ClaimPermission.ENTITY;
            if (entity.equals(player)) continue;
            if (!plugin.allowed(player, entity.getLocation(), p) || (p == ClaimPermission.PVP && player != null && !plugin.allowed(player, player.getLocation(), p))) e.setIntensity(entity, 0);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void cloud(AreaEffectCloudApplyEvent e) {
        Player player = actor(e.getEntity());
        e.getAffectedEntities().removeIf(entity -> !entity.equals(player) && (!plugin.allowed(player, entity.getLocation(), entity instanceof Player ? ClaimPermission.PVP : ClaimPermission.ENTITY)
                || entity instanceof Player && player != null && !plugin.allowed(player, player.getLocation(), ClaimPermission.PVP)));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void combust(EntityCombustByEntityEvent e) {
        Player player = actor(e.getCombuster()); ClaimPermission p = e.getEntity() instanceof Player ? ClaimPermission.PVP : ClaimPermission.ENTITY;
        if (player != null && (deny(player, e.getEntity().getLocation(), p) || p == ClaimPermission.PVP && deny(player, player.getLocation(), p))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void explosionDamage(EntityDamageEvent e) {
        if ((e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)
                && deny(null, e.getEntity().getLocation(), ClaimPermission.EXPLOSION)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hangingPlace(HangingPlaceEvent e) { if (deny(e.getPlayer(), e.getEntity().getLocation(), ClaimPermission.BUILD)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hangingBreak(HangingBreakEvent e) {
        Player player = e instanceof HangingBreakByEntityEvent by ? actor(by.getRemover()) : null;
        if (deny(player, e.getEntity().getLocation(), e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION ? ClaimPermission.EXPLOSION : ClaimPermission.ENTITY)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void explosion(EntityExplodeEvent e) { e.blockList().removeIf(b -> !plugin.allowed(null, b.getLocation(), ClaimPermission.EXPLOSION)); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void blockExplosion(BlockExplodeEvent e) { e.blockList().removeIf(b -> !plugin.allowed(null, b.getLocation(), ClaimPermission.EXPLOSION)); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void ignite(BlockIgniteEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation(), e.getPlayer() == null ? ClaimPermission.FIRE : ClaimPermission.BUILD)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void burn(BlockBurnEvent e) { if (deny(null, e.getBlock().getLocation(), ClaimPermission.FIRE)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void spread(BlockSpreadEvent e) {
        ClaimPermission p = e.getSource().getType() == Material.FIRE || e.getSource().getType() == Material.SOUL_FIRE ? ClaimPermission.FIRE : ClaimPermission.BUILD;
        if (p == ClaimPermission.FIRE ? deny(null, e.getBlock().getLocation(), p) : cross(e.getSource().getLocation(), e.getBlock().getLocation(), p)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void fluid(BlockFromToEvent e) { if (cross(e.getBlock().getLocation(), e.getToBlock().getLocation(), ClaimPermission.FLUID)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void form(BlockFormEvent e) {
        if (e instanceof EntityBlockFormEvent entity) {
            if (deny(actor(entity.getEntity()), e.getBlock().getLocation(), ClaimPermission.BUILD)) e.setCancelled(true);
        } else if (cross(e.getBlock().getLocation(), e.getBlock().getRelative(BlockFace.UP).getLocation(), ClaimPermission.FLUID)) e.setCancelled(true);
    }
    private boolean piston(Block base, List<Block> blocks, BlockFace direction) {
        for (Block b : blocks) if (cross(base.getLocation(), b.getLocation(), ClaimPermission.PISTON) || cross(b.getLocation(), b.getRelative(direction).getLocation(), ClaimPermission.PISTON)) return true;
        return false;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void extend(BlockPistonExtendEvent e) { if (piston(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void retract(BlockPistonRetractEvent e) {
        BlockFace facing = e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d ? d.getFacing() : e.getDirection();
        if (piston(e.getBlock(), e.getBlocks(), facing.getOppositeFace())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hopper(InventoryMoveItemEvent e) {
        for (Location from : inventoryLocations(e.getSource())) for (Location to : inventoryLocations(e.getDestination()))
            if (cross(from, to, ClaimPermission.CONTAINER)) { e.setCancelled(true); return; }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hopperPickup(InventoryPickupItemEvent e) {
        for (Location to : inventoryLocations(e.getInventory())) if (cross(e.getItem().getLocation(), to, ClaimPermission.CONTAINER)) { e.setCancelled(true); return; }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void dispense(BlockDispenseEvent e) {
        if (e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional directional && cross(e.getBlock().getLocation(), e.getBlock().getRelative(directional.getFacing()).getLocation(), ClaimPermission.BUILD)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void changeBlock(EntityChangeBlockEvent e) {
        if (deny(actor(e.getEntity()), e.getBlock().getLocation(), actor(e.getEntity()) == null ? ClaimPermission.MOB_GRIEF : ClaimPermission.BUILD)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void entityInteract(EntityInteractEvent e) { if (deny(actor(e.getEntity()), e.getBlock().getLocation(), e.getBlock().getType() == Material.FARMLAND ? ClaimPermission.MOB_GRIEF : ClaimPermission.INTERACT)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && deny(p, e.getItem().getLocation(), ClaimPermission.PICKUP)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void drop(PlayerDropItemEvent e) { if (deny(e.getPlayer(), e.getItemDrop().getLocation(), ClaimPermission.DROP)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void sign(SignChangeEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation(), ClaimPermission.BUILD)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void shear(PlayerShearEntityEvent e) { if (deny(e.getPlayer(), e.getEntity().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void leash(PlayerLeashEntityEvent e) { if (deny(e.getPlayer(), e.getEntity().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void unleash(PlayerUnleashEntityEvent e) { if (deny(e.getPlayer(), e.getEntity().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void vehicleDamage(VehicleDamageEvent e) { if (deny(actor(e.getAttacker()), e.getVehicle().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void vehicleDestroy(VehicleDestroyEvent e) { if (deny(actor(e.getAttacker()), e.getVehicle().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void vehicleEnter(VehicleEnterEvent e) { if (e.getEntered() instanceof Player p && deny(p, e.getVehicle().getLocation(), ClaimPermission.ENTITY)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void grow(StructureGrowEvent e) {
        Player player = e.getPlayer(); if (e.getBlocks().stream().anyMatch(b -> player == null
                ? cross(e.getLocation(), b.getLocation(), ClaimPermission.BUILD) : !plugin.allowed(player, b.getLocation(), ClaimPermission.BUILD))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void fertilize(BlockFertilizeEvent e) {
        if (e.getBlocks().stream().anyMatch(b -> e.getPlayer() == null
                ? cross(e.getBlock().getLocation(), b.getLocation(), ClaimPermission.BUILD) : !plugin.allowed(e.getPlayer(), b.getLocation(), ClaimPermission.BUILD))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void projectile(ProjectileHitEvent e) {
        if (e.getHitBlock() != null) {
            Player p = actor(e.getEntity());
            if (deny(p, e.getHitBlock().getLocation(), ClaimPermission.INTERACT)) e.setCancelled(true);
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e) { notices.remove(e.getPlayer().getUniqueId()); }
}
