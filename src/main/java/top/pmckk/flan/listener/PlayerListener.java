package top.pmckk.flan.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import top.pmckk.flan.Flan;
import top.pmckk.flan.model.*;
import java.util.Objects;

public final class PlayerListener implements Listener {
    private final Flan plugin;
    public PlayerListener(Flan plugin) { this.plugin = plugin; }
    @EventHandler public void join(PlayerJoinEvent e) {
        if (!plugin.maintenance()) plugin.registry().ensure(e.getPlayer().getUniqueId(), e.getPlayer().getName(), plugin.settings().startingBlocks());
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        plugin.menu().cancelEdit(e.getPlayer().getUniqueId()); plugin.selections().clear(e.getPlayer().getUniqueId()); plugin.clearBypass(e.getPlayer().getUniqueId()); plugin.changed();
    }
    private boolean different(Location a, Location b) {
        return b != null && (!a.getWorld().equals(b.getWorld()) || a.getBlockX() != b.getBlockX() || a.getBlockY() != b.getBlockY() || a.getBlockZ() != b.getBlockZ());
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent || !different(e.getFrom(), e.getTo())) return;
        Claim from = plugin.at(e.getFrom()), to = plugin.at(e.getTo());
        if (!Objects.equals(from == null ? null : from.id(), to == null ? null : to.id()) && !plugin.allowed(e.getPlayer(), e.getTo(), ClaimPermission.ENTER)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        if (!plugin.allowed(e.getPlayer(), e.getTo(), ClaimPermission.TELEPORT) || !plugin.allowed(e.getPlayer(), e.getTo(), ClaimPermission.ENTER)) {
            e.setCancelled(true); plugin.messages().send(e.getPlayer(), "protected", "permission", plugin.messages().text("labels.teleport-enter"));
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void portal(PlayerPortalEvent e) { teleport(e); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void crossing(PlayerMoveEvent e) {
        if (!plugin.settings().crossingMessages() || !different(e.getFrom(), e.getTo())) return;
        Claim from = plugin.at(e.getFrom()), to = plugin.at(e.getTo());
        if (Objects.equals(from == null ? null : from.id(), to == null ? null : to.id())) return;
        if (to != null) {
            String title = to.entryTitle() == null ? plugin.messages().text("claim.entry-title") : to.entryTitle();
            String subtitle = to.entrySubtitle() == null ? plugin.messages().text("claim.entry-subtitle") : to.entrySubtitle();
            e.getPlayer().sendTitle(plugin.messages().format(title, "name", to.name(), "player", e.getPlayer().getName()),
                    plugin.messages().format(subtitle, "name", to.name(), "player", e.getPlayer().getName()), 10, 50, 10);
        }
        else if (from != null) plugin.messages().send(e.getPlayer(), "leave", "name", from.name());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleportCrossing(PlayerTeleportEvent e) { plugin.selections().clearDisplay(e.getPlayer().getUniqueId()); crossing(e); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void portalCrossing(PlayerPortalEvent e) { plugin.selections().clearDisplay(e.getPlayer().getUniqueId()); crossing(e); }
    @EventHandler public void changedWorld(PlayerChangedWorldEvent e) { plugin.selections().clear(e.getPlayer().getUniqueId()); }
    @EventHandler public void respawn(PlayerRespawnEvent e) { plugin.selections().clearDisplay(e.getPlayer().getUniqueId()); }
}
