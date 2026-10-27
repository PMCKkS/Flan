package top.pmckk.flan.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import top.pmckk.flan.model.ClaimPermission;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

/** Every menu and its plain text templates are owned by gui/*.yml. */
public record GuiSettings(Map<Menu, Layout> menus) {
    public enum Menu {
        CLAIMS("claims.yml"), TRUSTED("trusted.yml"), PLAYERS("players.yml"), PLAYER_PERMISSIONS("player-permissions.yml");
        public final String file;
        Menu(String file) { this.file = file; }
    }
    public record Icon(Material material, int model, String name, List<String> lore) {
        public Icon { lore = List.copyOf(lore); }
    }
    public record Button(int slot, String action, Icon icon) {}
    public record Layout(int size, String title, String allowed, String denied, Map<String, String> labels, Map<Integer, Button> buttons,
                         List<Integer> contentSlots, Icon player, Icon filler) {
        public Layout { buttons = Map.copyOf(buttons); labels = Map.copyOf(labels); contentSlots = List.copyOf(contentSlots); }
    }
    public GuiSettings { menus = Map.copyOf(menus); }
    public Layout menu(Menu menu) { return menus.get(menu); }

    public static GuiSettings load(Path directory, Messages messages) throws Exception {
        return load(directory, messages, material -> material.isItem() && !material.isAir());
    }
    static GuiSettings load(Path directory, Messages messages, Predicate<Material> validItem) throws Exception {
        Files.createDirectories(directory);
        Map<Menu, Layout> menus = new EnumMap<>(Menu.class);
        for (Menu menu : Menu.values()) {
            Path file = directory.resolve(menu.file);
            if (!Files.exists(file)) ConfigurationFiles.resource("gui/" + menu.file).save(file.toFile());
            var config = new YamlConfiguration(); config.load(file.toFile());
            if (menu == Menu.CLAIMS && !config.contains("gui-version")) {
                Path backup = directory.resolve("claims.legacy.yml");
                if (!Files.exists(backup)) Files.copy(file, backup);
                config = migrate(config, messages); config.save(file.toFile());
            }
            if (config.getInt("gui-version", 0) < 3) {
                Path backup = directory.resolve(menu.file.replace(".yml", ".v2.yml"));
                if (!Files.exists(backup)) Files.copy(file, backup);
                flatten(config); config.set("gui-version", 3);
                read(menu, config, validItem); config.save(file.toFile());
            }
            String before = config.saveToString();
            menus.put(menu, read(menu, config, validItem));
            if (!before.equals(config.saveToString())) config.save(file.toFile());
        }
        return new GuiSettings(menus);
    }
    static Layout read(Menu menu, YamlConfiguration config, Predicate<Material> validItem) throws Exception {
        Set<String> added = new LinkedHashSet<>();
        if (menu == Menu.CLAIMS) for (String key : List.of("entry-title", "entry-subtitle", "teleport-point"))
            if (!config.contains("buttons." + key)) added.add(key);
        merge(config, ConfigurationFiles.resource("gui/" + menu.file));
        int rows = integer(config, "rows"); if (rows < 1 || rows > 6) throw invalid(menu.file + ": rows");
        int size = rows * 9;
        if (!config.isConfigurationSection("buttons")) throw invalid(menu.file + ": buttons");
        // New controls fit around customized older layouts without changing existing buttons.
        if (!added.isEmpty()) {
            Set<Integer> used = new HashSet<>();
            for (String key : config.getConfigurationSection("buttons").getKeys(false))
                if (!added.contains(key) && config.getBoolean("buttons." + key + ".enabled", true)) used.add(config.getInt("buttons." + key + ".slot"));
            for (String key : added) {
                int slot = config.getInt("buttons." + key + ".slot");
                if (slot >= size || used.contains(slot)) {
                    slot = -1;
                    for (int candidate = size - 1; candidate >= 0; candidate--) if (!used.contains(candidate)) { slot = candidate; break; }
                }
                if (slot < 0) config.set("buttons." + key + ".enabled", false);
                else { config.set("buttons." + key + ".slot", slot); used.add(slot); }
            }
        }

        Map<Integer, Button> buttons = new LinkedHashMap<>();
        for (String id : config.getConfigurationSection("buttons").getKeys(false)) {
            String path = "buttons." + id;
            if (config.contains(path + ".enabled") && !bool(config, path + ".enabled")) continue;
            int slot = integer(config, path + ".slot"); if (slot < 0 || slot >= size) throw invalid(path + ".slot");
            String action = string(config, path + ".action");
            if (action.startsWith("permission:")) {
                ClaimPermission permission = ClaimPermission.parse(action.substring(11));
                if (menu == Menu.PLAYER_PERMISSIONS && !permission.personal()) throw invalid(path + ".action");
                if (menu != Menu.CLAIMS && menu != Menu.PLAYER_PERMISSIONS) throw invalid(path + ".action");
            } else if (!Set.of("close", "back", "trusted", "add", "previous", "next", "remove", "entry-title", "entry-subtitle", "teleport-point").contains(action)) throw invalid(path + ".action");
            if ((action.equals("entry-title") || action.equals("entry-subtitle") || action.equals("teleport-point")) && menu != Menu.CLAIMS) throw invalid(path + ".action");
            if (buttons.putIfAbsent(slot, new Button(slot, action, icon(config, path, validItem))) != null) throw invalid(path + ".slot");
        }
        List<Integer> content = new ArrayList<>();
        if (menu == Menu.TRUSTED || menu == Menu.PLAYERS) {
            if (!config.isList("content-slots") || config.getList("content-slots").stream().anyMatch(value -> !(value instanceof Integer))) throw invalid("content-slots");
            for (int slot : config.getIntegerList("content-slots")) {
                if (slot < 0 || slot >= size || buttons.containsKey(slot) || content.contains(slot)) throw invalid("content-slots");
                content.add(slot);
            }
            if (content.isEmpty()) throw invalid("content-slots");
        }
        Icon player = content.isEmpty() ? null : icon(config, "player", validItem);
        Icon filler = bool(config, "filler.enabled") ? icon(config, "filler", validItem) : null;
        Map<String, String> labels = new HashMap<>();
        for (String key : List.of("allowed", "denied", "explicit", "inherited")) labels.put(key, string(config, "labels." + key));
        return new Layout(size, string(config, "title"), string(config, "state-colors.allowed"), string(config, "state-colors.denied"), labels, buttons, content, player, filler);
    }
    private static Icon icon(ConfigurationSection config, String path, Predicate<Material> validItem) {
        Material material = Material.matchMaterial(string(config, path + ".material"));
        if (material == null || !validItem.test(material)) throw invalid(path + ".material");
        int model = config.contains(path + ".custom-model-data") ? integer(config, path + ".custom-model-data") : -1;
        if (model < -1) throw invalid(path + ".custom-model-data");
        String lorePath = path + ".lore";
        if (!config.isList(lorePath) || config.getList(lorePath).stream().anyMatch(value -> !(value instanceof String))) throw invalid(lorePath);
        return new Icon(material, model, string(config, path + ".name"), config.getStringList(lorePath));
    }
    private static void flatten(ConfigurationSection config) {
        for (String key : List.copyOf(config.getKeys(false))) {
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null) continue;
            if (section.contains("zh_CN") || section.contains("en_US")) {
                Object value = section.get(section.contains("zh_CN") ? "zh_CN" : "en_US");
                config.set(key, null); config.set(key, value);
            } else flatten(section);
        }
    }
    private static String string(ConfigurationSection config, String path) { if (!config.isString(path)) throw invalid(path); return config.getString(path); }
    private static int integer(ConfigurationSection config, String path) { if (!config.isInt(path)) throw invalid(path); return config.getInt(path); }
    private static boolean bool(ConfigurationSection config, String path) { if (!config.isBoolean(path)) throw invalid(path); return config.getBoolean(path); }
    private static MessageException invalid(String path) { return new MessageException("errors.gui-config", "path", "gui/: " + path); }
    private static void merge(ConfigurationSection config, ConfigurationSection defaults) {
        for (String key : defaults.getKeys(false)) {
            if (!config.contains(key)) config.set(key, defaults.get(key));
            else if (config.isConfigurationSection(key) && defaults.isConfigurationSection(key)) merge(config.getConfigurationSection(key), defaults.getConfigurationSection(key));
        }
    }
    private static YamlConfiguration migrate(YamlConfiguration old, Messages messages) throws Exception {
        var next = ConfigurationFiles.resource("gui/claims.yml");
        next.set("rows", old.getInt("rows", 5));
        next.set("title", old.getString("title", messages.text(old.getString("title-key", "menu.title"))));
        next.set("state-colors.allowed", old.getString("state-colors.allowed", "&a"));
        next.set("state-colors.denied", old.getString("state-colors.denied", "&c"));
        for (ClaimPermission permission : ClaimPermission.values()) {
            String key = permission.name().toLowerCase(Locale.ROOT), path = "permissions." + key, target = "buttons." + key;
            if (old.contains(path + ".slot")) next.set(target + ".slot", old.get(path + ".slot"));
            if (old.contains(path + ".material")) next.set(target + ".material", old.get(path + ".material"));
            if (old.contains(path + ".custom-model-data")) next.set(target + ".custom-model-data", old.get(path + ".custom-model-data"));
            String template = old.getString(path + ".name", old.getString("permission-text.name", messages.text(old.getString(path + ".name-key", old.getString("permission-text.name-key", "menu.permission-name")))));
            List<String> lore = old.contains(path + ".lore") ? old.getStringList(path + ".lore") : old.contains("permission-text.lore") ? old.getStringList("permission-text.lore") : messages.lines(old.getString(path + ".lore-key", old.getString("permission-text.lore-key", "menu.permission-lore")));
            String label = messages.permission(permission), description = messages.text("permission-descriptions." + key);
            next.set(target + ".name", template.replace("{permission}", label).replace("{description}", description).replace("{key}", key));
            next.set(target + ".lore", lore.stream().map(line -> line.replace("{permission}", label).replace("{description}", description).replace("{key}", key)).toList());
        }
        // Let the normal loader place new title controls after preserving all legacy slots.
        next.set("buttons.entry-title", null); next.set("buttons.entry-subtitle", null); next.set("buttons.teleport-point", null);
        // Preserve the old close position; choose a free position for the new trust button.
        int close = old.getInt("close.slot", 40); next.set("buttons.close.slot", close);
        next.set("buttons.close.enabled", old.getBoolean("close.enabled", true));
        next.set("buttons.close.material", old.getString("close.material", "BARRIER"));
        next.set("buttons.close.name", old.getString("close.name", messages.text(old.getString("close.name-key", "menu.close-name"))));
        next.set("buttons.close.lore", old.contains("close.lore") ? old.getStringList("close.lore") : messages.lines(old.getString("close.lore-key", "menu.close-lore")));
        next.set("filler.enabled", old.getBoolean("filler.enabled", true));
        next.set("filler.material", old.getString("filler.material", "GRAY_STAINED_GLASS_PANE"));
        if (old.contains("filler.custom-model-data")) next.set("filler.custom-model-data", old.get("filler.custom-model-data"));
        Set<Integer> used = new HashSet<>();
        for (String id : next.getConfigurationSection("buttons").getKeys(false)) if (!id.equals("trusted")) used.add(next.getInt("buttons." + id + ".slot"));
        for (int slot = next.getInt("rows") * 9 - 1; slot >= 0; slot--) if (!used.contains(slot)) { next.set("buttons.trusted.slot", slot); break; }
        return next;
    }
}
