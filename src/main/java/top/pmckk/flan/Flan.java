package top.pmckk.flan;

import top.pmckk.flan.config.MessageException;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.pmckk.flan.command.ClaimCommand;
import top.pmckk.flan.config.*;
import top.pmckk.flan.listener.*;
import top.pmckk.flan.model.*;
import top.pmckk.flan.placeholder.FlanExpansion;
import top.pmckk.flan.service.*;
import top.pmckk.flan.storage.*;
import java.io.File;
import java.util.*;
import java.util.logging.Level;

public final class Flan extends JavaPlugin {
    private Settings settings;
    private volatile Messages messages;
    private ClaimRegistry registry;
    private Persistence persistence;
    private SelectionService selections;
    private ClaimMenu menu;
    private ClaimTeleportService teleports;
    private GuiSettings gui;
    private org.bukkit.configuration.file.YamlConfiguration effectiveConfig;
    private FlanExpansion expansion;
    private final Set<UUID> bypass = new HashSet<>();
    private volatile boolean maintenance;

    @Override
    public void onEnable() {
        getServer().getLogger().log(Level.INFO, "[Flan] 领地插件已启动!");
        Storage opened = null;
        try {
            messages = Messages.load(getDataFolder().toPath().resolve("lang"), "zh_CN");
            var loaded = ConfigurationFiles.load(getDataFolder().toPath());
            effectiveConfig = loaded.effective();
            messages = Messages.load(getDataFolder().toPath().resolve("lang"), loaded.main().getString("Lang", "zh_CN"));
            settings = Settings.read(effectiveConfig);
            gui = GuiSettings.load(getDataFolder().toPath().resolve("gui"), messages);
            opened = StorageFactory.open(getDataFolder().toPath(), effectiveConfig, effectiveConfig.getString("storage.type", "YAML"));
            registry = new ClaimRegistry(opened.load(), settings.defaults());
            persistence = new Persistence(opened, registry, e -> messages.log(getLogger(), Level.SEVERE, "logs.save-failed", e));
            opened = null; selections = new SelectionService(this); menu = new ClaimMenu(this);
            teleports = new ClaimTeleportService(this, TeleportSettings.read(effectiveConfig));
            ClaimCommand command = new ClaimCommand(this);
            Objects.requireNonNull(getCommand("flan")).setExecutor(command); getCommand("flan").setTabCompleter(command);
            Bukkit.getPluginManager().registerEvents(new ToolListener(this), this);
            Bukkit.getPluginManager().registerEvents(new ProtectionListener(this), this);
            Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
            Bukkit.getPluginManager().registerEvents(menu, this);
            Bukkit.getPluginManager().registerEvents(teleports, this);
            for (Player p : Bukkit.getOnlinePlayers()) registry.ensure(p.getUniqueId(), p.getName(), settings.startingBlocks());
            teleports.start();
            Bukkit.getScheduler().runTaskTimer(this, selections::refreshTools, 1, 2);
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) { expansion = new FlanExpansion(this); expansion.register(); }
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (maintenance) return;
                if (settings.accrual() && persistence.healthy()) for (Player p : Bukkit.getOnlinePlayers()) registry.accrue(p.getUniqueId(), settings.awardSeconds(), settings.awardBlocks(), settings.maximumBlocks());
            }, 20, 20);
            Bukkit.getScheduler().runTaskTimer(this, () -> persistence.save(), 20L * settings.autosaveSeconds(), 20L * settings.autosaveSeconds());
            messages.log(getLogger(), Level.INFO, "logs.enabled", null, "language", messages.locale(), "type", persistence.type(), "count", registry.all().size());
        } catch (Exception e) {
            if (messages != null) messages.log(getLogger(), Level.SEVERE, "logs.startup-failed", e, "reason", messages.error(e));
            else getLogger().log(Level.SEVERE, e.getMessage(), e);
            if (opened != null) try { opened.close(); } catch (Exception ignored) {}
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (expansion != null) expansion.unregister();
        if (menu != null) menu.closeAll();
        if (teleports != null) teleports.close();
        if (selections != null) selections.clearDisplays();
        Bukkit.getScheduler().cancelTasks(this);
        if (persistence != null) try { persistence.close(getDataFolder().toPath()); }
        catch (Exception e) { messages.log(getLogger(), Level.SEVERE, "logs.close-failed", e); }
    }
    public Settings settings() { return settings; }
    public Messages messages() { return messages; }
    public ClaimRegistry registry() { return registry; }
    public Persistence persistence() { return persistence; }
    public SelectionService selections() { return selections; }
    public ClaimMenu menu() { return menu; }
    public ClaimTeleportService teleports() { return teleports; }
    public GuiSettings gui() { return gui; }
    public org.bukkit.configuration.file.YamlConfiguration effectiveConfig() { return effectiveConfig; }
    public boolean maintenance() { return maintenance; }
    public void maintenance(boolean value) { maintenance = value; }
    public void writable() {
        if (maintenance) throw new MessageException("errors.maintenance");
        if (!persistence.healthy()) throw new MessageException("errors.storage-unhealthy");
    }
    public Claim at(Location location) { return registry.at(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
    public boolean bypass(Player p) { return p != null && p.hasPermission("flan.bypass") && bypass.contains(p.getUniqueId()); }
    public boolean toggleBypass(Player p) { if (bypass.remove(p.getUniqueId())) return false; bypass.add(p.getUniqueId()); return true; }
    public void clearBypass(UUID id) { bypass.remove(id); }
    public boolean allowed(Player player, Location location, ClaimPermission permission) {
        return bypass(player) || registry.allowed(at(location), player == null ? null : player.getUniqueId(), permission);
    }
    public void requireManage(Player player, Claim claim) {
        if (claim == null) throw new MessageException("errors.no-claim");
        if (!player.hasPermission("flan.admin") && !registry.allowed(claim, player.getUniqueId(), ClaimPermission.MANAGE)) throw new MessageException("errors.manage");
    }
    public void requireOwner(Player player, Claim claim) {
        if (claim == null) throw new MessageException("errors.no-claim");
        if (!player.hasPermission("flan.admin") && !player.getUniqueId().equals(claim.owner())) throw new MessageException("errors.owner");
    }
    public void requireNode(Player player, String permission) {
        if (!player.hasPermission(permission)) throw new MessageException("errors.permission", "permission", permission);
    }
    public void changed() { Bukkit.getScheduler().runTask(this, () -> { persistence.save(); if (teleports != null) teleports.publish(false); }); }
    public void reloadSettings() throws Exception {
        var loaded = ConfigurationFiles.load(getDataFolder().toPath()); var candidate = loaded.effective();
        Messages nextMessages = Messages.load(getDataFolder().toPath().resolve("lang"), loaded.main().getString("Lang", "zh_CN"));
        Settings next = Settings.read(candidate);
        TeleportSettings nextTeleport = TeleportSettings.read(candidate);
        GuiSettings nextGui = GuiSettings.load(getDataFolder().toPath().resolve("gui"), nextMessages);
        selections.clearDisplays();
        menu.closeAll();
        settings = next; messages = nextMessages; gui = nextGui; effectiveConfig = candidate; registry.defaults(next.defaults()); teleports.reload(nextTeleport);
    }
}
