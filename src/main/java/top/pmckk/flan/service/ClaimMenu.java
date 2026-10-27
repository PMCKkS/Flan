package top.pmckk.flan.service;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import top.pmckk.flan.Flan;
import top.pmckk.flan.config.*;
import top.pmckk.flan.config.GuiSettings.*;
import top.pmckk.flan.model.*;
import java.util.*;

public final class ClaimMenu implements Listener {
    private final Flan plugin;
    private record Edit(UUID claim, boolean subtitle, long deadline) {}
    private final Map<UUID, Edit> edits = new ConcurrentHashMap<>();
    public void cancelEdit(UUID player) { edits.remove(player); }
    private static final class Holder implements InventoryHolder {
        final UUID claim, target;
        final Menu menu;
        final Layout layout;
        final int page, pages;
        final Map<Integer, UUID> players = new HashMap<>();
        Inventory inventory;
        Holder(UUID claim, UUID target, Menu menu, Layout layout, int page, int pages) {
            this.claim = claim; this.target = target; this.menu = menu; this.layout = layout; this.page = page; this.pages = pages;
        }
        public Inventory getInventory() { return inventory; }
    }
    public ClaimMenu(Flan plugin) { this.plugin = plugin; }
    public void open(Player player, Claim claim) { open(player, claim, Menu.CLAIMS, null, 0); }
    public void trusted(Player player, Claim claim) { open(player, claim, Menu.TRUSTED, null, 0); }
    private void authorize(Player player, Claim claim, Menu menu) {
        plugin.requireNode(player, "flan.menu");
        if (menu == Menu.CLAIMS) plugin.requireManage(player, claim);
        else {
            plugin.requireOwner(player, claim); plugin.requireNode(player, "flan.trust");
            if (menu == Menu.PLAYER_PERMISSIONS) plugin.requireNode(player, "flan.trust.edit");
        }
    }
    private String name(UUID player) {
        PlayerAccount account = plugin.registry().account(player);
        if (account != null) return account.name();
        OfflinePlayer known = Bukkit.getOfflinePlayer(player);
        return known.getName() == null ? player.toString() : known.getName();
    }
    private List<UUID> candidates(Claim claim, Menu menu) {
        if (menu == Menu.TRUSTED) return plugin.registry().trusted(claim).stream().sorted(Comparator.comparing(this::name, String.CASE_INSENSITIVE_ORDER).thenComparing(UUID::toString)).toList();
        Set<UUID> candidates = new HashSet<>();
        plugin.registry().accounts().forEach(account -> candidates.add(account.id()));
        Bukkit.getOnlinePlayers().forEach(player -> candidates.add(player.getUniqueId()));
        candidates.removeAll(plugin.registry().trusted(claim)); candidates.remove(claim.owner());
        return candidates.stream().sorted(Comparator.comparing((UUID id) -> Bukkit.getPlayer(id) == null)
                .thenComparing(this::name, String.CASE_INSENSITIVE_ORDER).thenComparing(UUID::toString)).toList();
    }
    private void open(Player player, Claim claim, Menu menu, UUID target, int requestedPage) {
        authorize(player, claim, menu);
        cancelEdit(player.getUniqueId());
        if (target != null && plugin.registry().memberGroup(claim, target) == null) throw new MessageException("errors.trust-missing");
        Layout layout = plugin.gui().menu(menu);
        List<UUID> candidates = menu == Menu.TRUSTED || menu == Menu.PLAYERS ? candidates(claim, menu) : List.of();
        int capacity = layout.contentSlots().size();
        int pages = capacity == 0 ? 1 : Math.max(1, (candidates.size() + capacity - 1) / capacity);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        Holder holder = new Holder(claim.id(), target, menu, layout, page, pages);
        holder.inventory = Bukkit.createInventory(holder, layout.size(), plugin.messages().format(layout.title(), arguments(holder, claim, target, null)));
        if (layout.filler() != null) for (int slot = 0; slot < layout.size(); slot++) holder.inventory.setItem(slot, icon(layout.filler(), arguments(holder, claim, target, null), null));
        for (Button button : layout.buttons().values()) {
            ClaimPermission permission = button.action().startsWith("permission:") ? ClaimPermission.parse(button.action().substring(11)) : null;
            holder.inventory.setItem(button.slot(), icon(button.icon(), arguments(holder, claim, target, permission), null));
        }
        for (int index = page * capacity; index < Math.min(candidates.size(), (page + 1) * capacity); index++) {
            UUID candidate = candidates.get(index); int slot = layout.contentSlots().get(index % capacity);
            holder.players.put(slot, candidate);
            holder.inventory.setItem(slot, icon(layout.player(), arguments(holder, claim, candidate, null), candidate));
        }
        player.openInventory(holder.inventory);
    }
    private Object[] arguments(Holder holder, Claim claim, UUID target, ClaimPermission permission) {
        boolean effective = permission != null && plugin.registry().allowed(claim, target, permission);
        boolean explicit = permission != null && (target == null ? claim.flags().containsKey(permission) : claim.playerFlags().getOrDefault(target, Map.of()).containsKey(permission));
        String teleport = claim.teleportPoint() == null ? "" : String.format(Locale.ROOT, "%.1f, %.1f, %.1f", claim.teleportPoint().x(), claim.teleportPoint().y(), claim.teleportPoint().z());
        return new Object[]{"teleport", teleport, "entry_title", Objects.toString(claim.entryTitle(), plugin.messages().text("claim.entry-title")),
                "entry_subtitle", Objects.toString(claim.entrySubtitle(), plugin.messages().text("claim.entry-subtitle")), "name", claim.name(), "player", target == null ? "" : name(target), "page", holder.page + 1, "pages", holder.pages,
                "group", target == null ? "" : Objects.toString(plugin.registry().memberGroup(claim, target), ""),
                "state", holder.layout.labels().get(effective ? "allowed" : "denied"), "source", holder.layout.labels().get(explicit ? "explicit" : "inherited"),
                "state_color", effective ? holder.layout.allowed() : holder.layout.denied()};
    }
    private ItemStack icon(Icon definition, Object[] arguments, UUID head) {
        ItemStack icon = new ItemStack(definition.material()); ItemMeta meta = icon.getItemMeta();
        meta.setDisplayName(plugin.messages().format(definition.name(), arguments));
        meta.setLore(definition.lore().stream().map(line -> plugin.messages().format(line, arguments)).toList());
        if (definition.model() >= 0) meta.setCustomModelData(definition.model());
        if (meta instanceof SkullMeta skull && head != null) skull.setOwningPlayer(Bukkit.getOfflinePlayer(head));
        meta.addItemFlags(ItemFlag.values()); icon.setItemMeta(meta); return icon;
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= holder.layout.size()
                || event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        Button button = holder.layout.buttons().get(event.getRawSlot()); UUID selected = holder.players.get(event.getRawSlot());
        if (button == null && selected == null) return;
        // Navigation and mutations run next tick, with identity and authorization checked again.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            try {
                if (button != null && button.action().equals("close")) { player.closeInventory(); return; }
                Claim claim = plugin.registry().get(holder.claim); authorize(player, claim, holder.menu);
                if (selected != null) {
                    if (holder.menu == Menu.PLAYERS) {
                        plugin.writable(); plugin.requireNode(player, "flan.trust.edit");
                        if (plugin.registry().memberGroup(claim, selected) != null) throw new MessageException("errors.trust-exists");
                        Player online = Bukkit.getPlayer(selected);
                        if (online != null) plugin.registry().ensure(selected, online.getName(), plugin.settings().startingBlocks());
                        claim = plugin.registry().trustDefault(claim, selected); plugin.changed();
                        plugin.messages().send(player, "commands.trust-added", "player", name(selected));
                    }
                    open(player, claim, Menu.PLAYER_PERMISSIONS, selected, 0); return;
                }
                String action = button.action();
                switch (action) {
                    case "teleport-point" -> {
                        if (event.getClick() == ClickType.RIGHT) { player.closeInventory(); plugin.teleports().apply(player, claim); }
                        else { Claim updated = plugin.teleports().setPoint(player, claim); open(player, updated); }
                    }
                    case "entry-title", "entry-subtitle" -> {
                        plugin.writable(); plugin.requireOwner(player, claim); plugin.requireNode(player, "flan.greeting");
                        boolean subtitle = action.equals("entry-subtitle");
                        Edit pending = new Edit(claim.id(), subtitle, System.currentTimeMillis() + 60000);
                        edits.put(player.getUniqueId(), pending);
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (edits.remove(player.getUniqueId(), pending) && player.isOnline())
                                plugin.messages().sendError(player, new MessageException("errors.entry-expired"));
                        }, 1200);
                        player.closeInventory();
                        plugin.messages().send(player, subtitle ? "commands.entry-subtitle-prompt" : "commands.entry-title-prompt");
                    }
                    case "trusted" -> open(player, claim, Menu.TRUSTED, null, 0);
                    case "add" -> { plugin.requireNode(player, "flan.trust.edit"); open(player, claim, Menu.PLAYERS, null, 0); }
                    case "back" -> open(player, claim, holder.menu == Menu.TRUSTED ? Menu.CLAIMS : Menu.TRUSTED, null, 0);
                    case "previous", "next" -> open(player, claim, holder.menu, holder.target, holder.page + (action.equals("next") ? 1 : -1));
                    case "remove" -> {
                        plugin.writable(); plugin.requireNode(player, "flan.trust.edit");
                        if (holder.target == null) return;
                        Claim updated = plugin.registry().untrust(claim, holder.target); plugin.changed();
                        plugin.messages().send(player, "commands.trust-removed", "player", name(holder.target));
                        open(player, updated, Menu.TRUSTED, null, 0);
                    }
                    default -> {
                        if (!action.startsWith("permission:")) return;
                        plugin.writable(); ClaimPermission permission = ClaimPermission.parse(action.substring(11));
                        Claim updated;
                        if (holder.menu == Menu.PLAYER_PERMISSIONS) {
                            plugin.requireNode(player, "flan.trust.edit");
                            Boolean value = event.getClick() == ClickType.RIGHT ? null : !plugin.registry().allowed(claim, holder.target, permission);
                            updated = plugin.registry().playerPermission(claim, holder.target, permission, value);
                        } else {
                            if (permission == ClaimPermission.MANAGE) plugin.requireOwner(player, claim);
                            Map<ClaimPermission, Boolean> flags = new EnumMap<>(ClaimPermission.class); flags.putAll(claim.flags());
                            if (event.getClick() == ClickType.RIGHT) flags.remove(permission); else flags.put(permission, !plugin.registry().allowed(claim, null, permission));
                            updated = claim.withFlags(flags); plugin.registry().update(updated);
                        }
                        plugin.changed(); open(player, updated, holder.menu, holder.target, holder.page);
                    }
                }
            } catch (IllegalArgumentException error) { plugin.messages().sendError(player, error); }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void editChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId(); Edit edit = edits.remove(id);
        if (edit == null) return;
        event.setCancelled(true);
        String input = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline()) return;
            try {
                if (System.currentTimeMillis() > edit.deadline()) throw new MessageException("errors.entry-expired");
                Claim claim = plugin.registry().get(edit.claim());
                plugin.requireOwner(player, claim); plugin.requireNode(player, "flan.menu"); plugin.requireNode(player, "flan.greeting");
                if (input.equalsIgnoreCase("cancel")) {
                    plugin.messages().send(player, "commands.entry-cancelled"); open(player, claim); return;
                }
                plugin.writable();
                String value = input.equalsIgnoreCase("reset") ? null : input.equalsIgnoreCase("clear") ? "" : input;
                Claim updated = claim.withEntryText(edit.subtitle(), value);
                plugin.registry().update(updated); plugin.changed();
                plugin.messages().send(player, "commands.entry-saved"); open(player, updated);
            } catch (IllegalArgumentException error) { plugin.messages().sendError(player, error); }
        });
    }
    public void closeAll() {
        edits.clear();
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) player.closeInventory();
    }
}
