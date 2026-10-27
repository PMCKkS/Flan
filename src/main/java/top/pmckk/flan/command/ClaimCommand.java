package top.pmckk.flan.command;

import top.pmckk.flan.config.MessageException;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import top.pmckk.flan.Flan;
import top.pmckk.flan.model.*;
import top.pmckk.flan.storage.*;
import java.util.*;

public final class ClaimCommand implements CommandExecutor, TabCompleter {
    private final Flan plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private record Pending(UUID claim, UUID target, long expires) {}
    public ClaimCommand(Flan plugin) { this.plugin = plugin; }
    private static boolean bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) { case "true", "allow", "on" -> true; case "false", "deny", "off" -> false; default -> throw new MessageException("errors.permission-value"); };
    }
    private void usage(String[] args, int length, String key) { if (args.length < length) throw usageError(key); }
    private MessageException usageError(String key) { return new MessageException("errors.usage", "usage", plugin.messages().text("usage." + key)); }
    private void admin(CommandSender s) { if (!s.hasPermission("flan.admin")) throw new MessageException("errors.permission", "permission", "flan.admin"); }
    private Player player(CommandSender sender) { if (sender instanceof Player p) return p; throw new MessageException("player-only"); }
    private PlayerAccount account(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && !plugin.maintenance()) return plugin.registry().ensure(online.getUniqueId(), online.getName(), plugin.settings().startingBlocks());
        return plugin.registry().accounts().stream().filter(p -> p.name().equalsIgnoreCase(name) || p.id().toString().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new MessageException("errors.player-not-found"));
    }
    private Claim current(Player p) { Claim c = plugin.at(p.getLocation()); if (c == null) throw new MessageException("errors.no-claim"); return c; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("flan.use") && !sender.hasPermission("flan.admin")) { plugin.messages().send(sender, "no-permission"); return true; }
        try {
            String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "help", "?" -> help(sender);
                case "reload" -> { admin(sender); plugin.writable(); plugin.reloadSettings(); plugin.messages().send(sender, "commands.reloaded"); }
                case "save" -> {
                    admin(sender);
                    plugin.persistence().save().whenComplete((v, error) -> later(() -> { if (error == null) plugin.messages().send(sender, "saved"); else plugin.messages().send(sender, "storage-error"); }));
                }
                case "storage" -> storage(sender, args);
                case "blocks" -> blocks(sender, args);
                case "list" -> list(sender, args);
                default -> {
                    Player p = player(sender);
                    if (plugin.maintenance()) throw new MessageException("errors.maintenance");
                    plugin.registry().ensure(p.getUniqueId(), p.getName(), plugin.settings().startingBlocks());
                    switch (sub) {
                        case "tool" -> {
                            if (!p.hasPermission("flan.tool")) throw new MessageException("errors.permission", "permission", "flan.tool");
                            if (p.getInventory().firstEmpty() < 0) throw new MessageException("errors.inventory-full");
                            p.getInventory().addItem(plugin.selections().tool()); plugin.messages().send(p, "commands.tool-given");
                        }
                        case "pos1", "pos2" -> {
                            if (!p.hasPermission("flan.claim")) throw new MessageException("errors.permission", "permission", "flan.claim");
                            plugin.selections().select(p, p.getLocation().getBlock(), sub.equals("pos1") ? 0 : 1);
                        }
                        case "create", "subclaim", "adminclaim" -> create(p, args, sub);
                        case "mode" -> {
                            if (!p.hasPermission("flan.claim")) throw new MessageException("errors.permission", "permission", "flan.claim");
                            if (args.length == 1) plugin.selections().mode(p);
                            else if (args.length == 2 && Set.of("2d", "3d").contains(args[1].toLowerCase(Locale.ROOT)))
                                plugin.selections().mode(p, args[1].equalsIgnoreCase("3d"));
                            else throw usageError("mode");
                        }
                        case "tp" -> { usage(args, 2, "tp"); plugin.teleports().teleport(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim()); }
                        case "info" -> info(p, current(p));
                        case "show" -> plugin.selections().show(p, current(p));
                        case "menu" -> { Claim c = current(p); plugin.requireManage(p, c); plugin.menu().open(p, c); }
                        case "trusted" -> plugin.menu().trusted(p, current(p));
                        case "rename" -> {
                            plugin.writable(); usage(args, 2, "rename"); Claim c = current(p); plugin.requireManage(p, c);
                            plugin.registry().update(c.renamed(name(args, 1))); plugin.changed(); plugin.messages().send(p, "commands.renamed");
                        }
                        case "resize" -> {
                            plugin.writable(); Claim c = current(p); plugin.requireOwner(p, c);
                            boolean is3d = !plugin.settings().worlds().ignoreY() && c.isThreeDimensional(p.getWorld().getMinHeight(), p.getWorld().getMaxHeight());
                            Bounds b = selection(p, is3d);
                            Claim resized = c.resized(b, is3d);
                            if (resized.teleportPoint() == null) resized = plugin.teleports().initialize(resized, p);
                            try { plugin.registry().update(resized); }
                            catch (MessageException e) { if (e.key().equals("errors.overlap")) plugin.selections().showConflicts(p, resized); throw e; }
                            plugin.changed(); plugin.selections().resetPoints(p); plugin.selections().show(p, resized); plugin.messages().send(p, "commands.resized", "remaining", plugin.registry().remaining(p.getUniqueId()));
                        }
                        case "delete" -> {
                            plugin.writable(); Claim c = current(p); plugin.requireOwner(p, c); pending.put(p.getUniqueId(), new Pending(c.id(), null, System.currentTimeMillis() + 30000));
                            plugin.messages().send(p, "commands.confirm-delete", "name", c.name());
                        }
                        case "transfer" -> {
                            plugin.writable(); usage(args, 2, "transfer"); Claim c = current(p); plugin.requireOwner(p, c);
                            if (c.parent() != null) throw new MessageException("errors.transfer-parent-location");
                            PlayerAccount target = account(args[1]);
                            pending.put(p.getUniqueId(), new Pending(c.id(), target.id(), System.currentTimeMillis() + 30000));
                            plugin.messages().send(p, "commands.confirm-transfer", "player", target.name());
                        }
                        case "confirm" -> confirm(p);
                        case "trust" -> trust(p, args, false);
                        case "untrust" -> trust(p, args, true);
                        case "flag" -> flag(p, args);
                        case "group" -> group(p, args);
                        case "bypass" -> { if (!p.hasPermission("flan.bypass")) throw new MessageException("errors.permission", "permission", "flan.bypass"); plugin.messages().send(p, "commands.bypass", "state", plugin.messages().text(plugin.toggleBypass(p) ? "labels.enabled" : "labels.disabled")); }
                        default -> help(sender);
                    }
                }
            }
        } catch (Exception e) { plugin.messages().sendError(sender, e); if (!(e instanceof MessageException) && !(e instanceof NumberFormatException) && !(e instanceof ArithmeticException)) plugin.messages().log(plugin.getLogger(), java.util.logging.Level.SEVERE, "logs.command-failed", e, "command", label); }
        return true;
    }
    private void later(Runnable task) { if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task); }
    private Bounds selection(Player p) {
        return selection(p, plugin.selections().threeDimensional(p));
    }
    private Bounds selection(Player p, boolean threeDimensional) {
        if (plugin.settings().worlds().disabled(p.getWorld())) throw new MessageException("errors.world-disabled");
        Bounds b = plugin.selections().bounds(p, threeDimensional);
        if (b.area() < plugin.settings().minimumArea() || b.area() > plugin.settings().maximumArea()) throw new MessageException("errors.area-range", "minimum", plugin.settings().minimumArea(), "maximum", plugin.settings().maximumArea());
        if ((long) b.maxY() - b.minY() + 1 < plugin.settings().minimumHeight()) throw new MessageException("errors.height");
        if (!p.getWorld().getWorldBorder().isInside(new Location(p.getWorld(), b.minX(), b.minY(), b.minZ())) || !p.getWorld().getWorldBorder().isInside(new Location(p.getWorld(), b.maxX(), b.maxY(), b.maxZ()))) throw new MessageException("errors.world-border");
        return b;
    }
    private String name(String[] args, int offset) {
        String name = String.join(" ", Arrays.copyOfRange(args, offset, args.length)).trim();
        if (name.isEmpty() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl)) throw new MessageException("errors.claim-name");
        return name;
    }
    private void create(Player p, String[] args, String mode) {
        plugin.writable(); if (!p.hasPermission("flan.claim")) throw new MessageException("errors.permission", "permission", "flan.claim");
        if (mode.equals("adminclaim")) admin(p);
        Bounds b = selection(p); Claim parent = null;
        if (mode.equals("subclaim")) {
            parent = plugin.registry().at(p.getWorld().getUID(), b.minX(), b.minY(), b.minZ()); parent = plugin.registry().root(parent);
            if (parent == null || !parent.bounds().contains(b)) throw new MessageException("errors.subclaim-selection"); plugin.requireOwner(p, parent);
        }
        String name = args.length > 1 ? name(args, 1) : plugin.settings().defaultName().isEmpty() ? plugin.messages().text("claim.default-name") : plugin.settings().defaultName();
        Claim claim = new Claim(UUID.randomUUID(), p.getWorld().getUID(), parent == null ? (mode.equals("adminclaim") ? null : p.getUniqueId()) : parent.owner(),
                parent == null ? null : parent.id(), name, b, Map.of(), Map.of(), parent == null ? plugin.settings().groups() : Map.of(), plugin.selections().threeDimensional(p));
        claim = plugin.teleports().initialize(claim, p);
        try { plugin.registry().create(claim, plugin.settings().maximumClaims()); }
        catch (MessageException e) { if (e.key().equals("errors.overlap")) plugin.selections().showConflicts(p, claim); throw e; }
        plugin.changed(); plugin.selections().resetPoints(p); plugin.selections().show(p, claim);
        plugin.messages().send(p, "created", "name", name, "blocks", b.area(), "remaining", plugin.registry().remaining(p.getUniqueId()));
    }
    private void confirm(Player p) {
        plugin.writable(); Pending action = pending.remove(p.getUniqueId());
        if (action == null || action.expires() < System.currentTimeMillis()) throw new MessageException("errors.confirm-expired");
        Claim c = plugin.registry().get(action.claim()); plugin.requireOwner(p, c);
        if (action.target() == null) { plugin.registry().delete(c); plugin.messages().send(p, "deleted", "name", c.name()); }
        else { plugin.registry().transfer(c, action.target(), plugin.settings().maximumClaims()); plugin.messages().send(p, "commands.transferred"); }
        plugin.changed();
    }
    private void trust(Player p, String[] args, boolean remove) {
        plugin.writable(); plugin.requireNode(p, "flan.trust"); plugin.requireNode(p, "flan.trust.edit");
        usage(args, 2, remove ? "untrust" : "trust"); Claim c = current(p); plugin.requireOwner(p, c);
        PlayerAccount target = account(args[1]);
        if (remove) plugin.registry().untrust(c, target.id());
        else {
            String group = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "builder";
            plugin.registry().trust(c, target.id(), group);
        }
        plugin.changed();
        plugin.messages().send(p, remove ? "commands.untrusted" : "commands.trusted");
    }
    private void flag(Player p, String[] args) {
        plugin.writable(); usage(args, 3, "flag"); Claim c = current(p); plugin.requireManage(p, c);
        ClaimPermission permission = ClaimPermission.parse(args[1]);
        if (permission == ClaimPermission.MANAGE) plugin.requireOwner(p, c);
        Map<ClaimPermission, Boolean> flags = new EnumMap<>(ClaimPermission.class); flags.putAll(c.flags());
        if (args[2].equalsIgnoreCase("inherit")) flags.remove(permission); else flags.put(permission, bool(args[2]));
        plugin.registry().update(c.withFlags(flags)); plugin.changed(); plugin.messages().send(p, "commands.flag-updated");
    }
    private void group(Player p, String[] args) {
        Claim c = current(p); plugin.requireManage(p, c);
        if (args.length == 1) { plugin.registry().groups(c).forEach((g, perms) -> plugin.messages().send(p, "commands.group-entry", "group", g, "permissions", perms.stream().sorted().map(plugin.messages()::permission).collect(java.util.stream.Collectors.joining(plugin.messages().text("labels.permission-separator"))))); return; }
        plugin.writable(); plugin.requireOwner(p, c); usage(args, 4, "group");
        String name = args[1].toLowerCase(Locale.ROOT); if (!name.matches("[a-z0-9_-]{1,32}")) throw new MessageException("errors.group-name");
        ClaimPermission permission = ClaimPermission.parse(args[2]);
        Set<ClaimPermission> perms = EnumSet.noneOf(ClaimPermission.class); Set<ClaimPermission> before = plugin.registry().group(c, name); if (before != null) perms.addAll(before);
        if (bool(args[3])) perms.add(permission); else perms.remove(permission);
        Map<String, Set<ClaimPermission>> groups = new HashMap<>(c.groups()); groups.put(name, perms);
        plugin.registry().update(c.withGroups(groups)); plugin.changed(); plugin.messages().send(p, "commands.group-updated");
    }
    private void blocks(CommandSender sender, String[] args) {
        if (args.length >= 4) {
            admin(sender); plugin.writable(); PlayerAccount p = account(args[1]); long amount = Long.parseLong(args[3]);
            long next = switch (args[2].toLowerCase(Locale.ROOT)) { case "set" -> amount; case "add" -> Math.addExact(p.allowance(), amount); case "take" -> Math.subtractExact(p.allowance(), amount); default -> throw usageError("blocks-admin"); };
            if (amount < 0) throw new MessageException("errors.negative-number"); plugin.registry().allowance(p.id(), next, plugin.settings().maximumBlocks()); plugin.changed(); plugin.messages().send(sender, "commands.blocks-updated", "player", p.name(), "total", next); return;
        }
        if (args.length > 2) throw usageError("blocks");
        PlayerAccount p;
        if (args.length == 2) { admin(sender); p = account(args[1]); }
        else { Player player = player(sender); p = plugin.registry().account(player.getUniqueId());
            if (p == null) { if (plugin.maintenance()) throw new MessageException("errors.maintenance"); p = plugin.registry().ensure(player.getUniqueId(), player.getName(), plugin.settings().startingBlocks()); }
        }
        plugin.messages().send(sender, "commands.blocks-info", "player", p.name(), "total", p.allowance(), "used", plugin.registry().used(p.id()), "remaining", plugin.registry().remaining(p.id()));
    }
    private void list(CommandSender sender, String[] args) {
        UUID owner; int page = 1;
        if (args.length > 1 && !args[1].matches("[0-9]+")) { admin(sender); owner = account(args[1]).id(); if (args.length > 2) page = Integer.parseInt(args[2]); }
        else { owner = player(sender).getUniqueId(); if (args.length > 1) page = Integer.parseInt(args[1]); }
        List<Claim> claims = plugin.registry().all().stream().filter(c -> owner.equals(c.owner()) && c.parent() == null).toList();
        int pages = Math.max(1, (claims.size() + 7) / 8); if (page < 1 || page > pages) throw new MessageException("errors.page-range", "pages", pages);
        plugin.messages().send(sender, "commands.list-title", "page", page, "pages", pages);
        for (Claim c : claims.subList((page - 1) * 8, Math.min(claims.size(), page * 8))) {
            World world = Bukkit.getWorld(c.world()); plugin.messages().send(sender, "commands.list-entry", "name", c.name(), "world", world == null ? c.world() : world.getName(), "x", c.bounds().minX(), "z", c.bounds().minZ(), "area", c.bounds().area());
        }
    }
    private void info(Player p, Claim c) {
        plugin.messages().send(p, "commands.info-title", "name", c.name(), "type", c.parent() == null ? "" : plugin.messages().text("labels.subclaim"), "id", c.id());
        PlayerAccount owner = c.owner() == null ? null : plugin.registry().account(c.owner());
        plugin.messages().send(p, "commands.info-owner", "owner", c.admin() ? plugin.messages().text("labels.admin-claim") : owner == null ? c.owner() : owner.name(), "area", c.bounds().area());
        plugin.messages().send(p, "commands.info-bounds", "min_x", c.bounds().minX(), "min_y", c.bounds().minY(), "min_z", c.bounds().minZ(), "max_x", c.bounds().maxX(), "max_y", c.bounds().maxY(), "max_z", c.bounds().maxZ());
        plugin.messages().send(p, "commands.info-mode", "mode", plugin.messages().text(c.isThreeDimensional(p.getWorld().getMinHeight(), p.getWorld().getMaxHeight()) ? "labels.mode-3d" : "labels.mode-2d"));
        plugin.messages().send(p, "commands.info-members", "count", plugin.registry().trusted(c).size());
    }
    private void storage(CommandSender sender, String[] args) throws Exception {
        admin(sender);
        if (args.length == 1) { plugin.messages().send(sender, "commands.storage-info", "type", plugin.persistence().type(), "state", plugin.messages().text(plugin.persistence().healthy() ? "labels.healthy" : "labels.unhealthy")); return; }
        usage(args, 3, "storage");
        if (!args[1].equalsIgnoreCase("migrate")) throw usageError("storage");
        plugin.writable(); String type = args[2].toUpperCase(Locale.ROOT);
        if (!Set.of("YAML", "SQLITE", "MYSQL").contains(type)) throw new MessageException("errors.storage-unsupported");
        if (plugin.persistence().type().equals(type)) throw new MessageException("errors.storage-same");
        var config = new org.bukkit.configuration.file.YamlConfiguration(); config.loadFromString(plugin.effectiveConfig().saveToString());
        plugin.maintenance(true);
        plugin.messages().send(sender, "commands.migration-start", "type", type);
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return StorageFactory.open(plugin.getDataFolder().toPath(), config, type); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).thenCompose(target -> plugin.persistence().migrate(target)).whenComplete((v, error) -> later(() -> {
            if (error == null) {
                try { top.pmckk.flan.config.ConfigurationFiles.storageType(plugin.getDataFolder().toPath(), type); }
                catch (Exception writeError) { plugin.messages().send(sender, "commands.migration-config", "type", type); return; }
                plugin.messages().send(sender, "commands.migration-done", "type", type);
            } else {
                plugin.maintenance(false); Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
                for (Player online : Bukkit.getOnlinePlayers()) plugin.registry().ensure(online.getUniqueId(), online.getName(), plugin.settings().startingBlocks());
                plugin.messages().log(plugin.getLogger(), java.util.logging.Level.SEVERE, "logs.migration-failed", cause, "reason", plugin.messages().error(cause));
                plugin.messages().send(sender, "commands.migration-failed", "reason", plugin.messages().error(cause));
            }
        }));
    }
    private void help(CommandSender sender) {
        plugin.messages().sendLines(sender, "help.player");
        if (sender.hasPermission("flan.admin")) plugin.messages().sendLines(sender, "help.admin");
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("help", "tool", "pos1", "pos2", "mode", "create", "subclaim", "tp", "info", "list", "blocks", "show", "menu", "trusted", "rename", "resize", "delete", "transfer", "confirm", "trust", "untrust", "flag", "group"));
            if (s.hasPermission("flan.admin")) options.addAll(List.of("adminclaim", "bypass", "reload", "save", "storage"));
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("tp") && s instanceof Player player && s.hasPermission("flan.tp")) {
                String typed = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                String previous = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1)) + " " : "";
                return plugin.teleports().names(player).stream().filter(name -> name.regionMatches(true, 0, typed, 0, typed.length()))
                        .map(name -> name.substring(previous.length())).sorted().toList();
            }
            if (sub.equals("mode") && args.length == 2) options.addAll(List.of("2d", "3d"));
            if ((sub.equals("flag") && args.length == 2) || (sub.equals("group") && args.length == 3)) options.addAll(Arrays.stream(ClaimPermission.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList());
            else if ((sub.equals("flag") && args.length == 3) || (sub.equals("group") && args.length == 4)) options.addAll(List.of("true", "false", "inherit"));
            else if (sub.equals("storage") && args.length == 2) options.add("migrate");
            else if (sub.equals("storage") && args.length == 3) options.addAll(List.of("YAML", "SQLITE", "MYSQL"));
            else if (sub.equals("blocks") && args.length == 3) options.addAll(List.of("set", "add", "take"));
            else if ((sub.equals("trust") || sub.equals("group")) && s instanceof Player p && ((sub.equals("trust") && args.length == 3) || (sub.equals("group") && args.length == 2))) { Claim claim = plugin.at(p.getLocation()); if (claim != null) options.addAll(plugin.registry().groups(claim).keySet()); }
            else if (args.length == 2 && Set.of("trust", "untrust", "transfer", "blocks").contains(sub)) options.addAll(plugin.registry().accounts().stream().map(PlayerAccount::name).toList());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT); return options.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
