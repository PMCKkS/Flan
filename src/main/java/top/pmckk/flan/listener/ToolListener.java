package top.pmckk.flan.listener;

import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import top.pmckk.flan.Flan;

public final class ToolListener implements Listener {
    private final Flan plugin;
    public ToolListener(Flan plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.LOWEST)
    public void select(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !plugin.selections().isTool(e.getItem())) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        if (!e.getPlayer().hasPermission("flan.claim")) { plugin.messages().send(e.getPlayer(), "no-permission"); return; }
        try { plugin.selections().select(e.getPlayer(), e.getClickedBlock(), e.getAction() == Action.RIGHT_CLICK_BLOCK ? 0 : 1); }
        catch (IllegalArgumentException ex) { plugin.messages().sendError(e.getPlayer(), ex); }
    }
}
