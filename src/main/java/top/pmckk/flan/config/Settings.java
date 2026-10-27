package top.pmckk.flan.config;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import top.pmckk.flan.model.ClaimPermission;
import java.util.*;

public record Settings(long startingBlocks, long maximumBlocks, long minimumArea, long maximumArea, int maximumClaims,
                       boolean threeDimensional, int minimumHeight, int selectionDistance,
                       String defaultName, boolean accrual, int awardSeconds, long awardBlocks, int autosaveSeconds,
                       Material toolMaterial, String toolName, List<String> toolLore, int customModelData, boolean requireMarker,
                       int borderSeconds, int borderPoints, Particle borderParticle, BorderSettings border, boolean crossingMessages,
                       Map<ClaimPermission, Boolean> defaults, Map<String, Set<ClaimPermission>> groups, WorldRules worlds) {
    public static Settings read(FileConfiguration c) {
        long start = c.getLong("claims.starting-blocks", 500), max = c.getLong("claims.maximum-blocks", 50000);
        long minArea = c.getLong("claims.minimum-area", 9), maxArea = c.getLong("claims.maximum-area", 1000000);
        int awardSeconds = c.getInt("claims.accrual.seconds-per-award", 600);
        long award = c.getLong("claims.accrual.blocks-per-award", 10);
        if (start < 0 || max < start || max > 1000000000000L || minArea < 1 || maxArea < minArea || maxArea > 100000000L
                || awardSeconds < 1 || award < 0 || award > max) throw new MessageException("errors.config-limits");
        Material material = Material.matchMaterial(c.getString("tool.material", "GOLDEN_HOE"));
        if (material == null || !material.isItem() || material.isAir()) throw new MessageException("errors.tool-material");
        Map<ClaimPermission, Boolean> defaults = new EnumMap<>(ClaimPermission.class);
        for (ClaimPermission p : ClaimPermission.values()) defaults.put(p, c.getBoolean("defaults." + p.name().toLowerCase(Locale.ROOT), false));
        Map<String, Set<ClaimPermission>> groups = new HashMap<>();
        ConfigurationSection section = c.getConfigurationSection("groups");
        if (section != null) for (String name : section.getKeys(false)) {
            if (!name.matches("[a-zA-Z0-9_-]{1,32}")) throw new MessageException("errors.config-group", "group", name);
            Set<ClaimPermission> perms = EnumSet.noneOf(ClaimPermission.class);
            for (String p : section.getStringList(name)) perms.add(ClaimPermission.parse(p));
            groups.put(name.toLowerCase(Locale.ROOT), Set.copyOf(perms));
        }
        int distance = c.getInt("claims.maximum-selection-distance", 100);
        int height = c.getInt("claims.minimum-height", 1);
        if (distance < 1 || distance > 256 || height < 1 || height > 4096) throw new MessageException("errors.config-selection");
        Particle particle;
        try { particle = Particle.valueOf(c.getString("claims.border.particle", "HAPPY_VILLAGER").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new MessageException("errors.unknown-particle", "particle", c.getString("claims.border.particle")); }
        if (particle.getDataType() != Void.class) throw new MessageException("errors.particle-data");
        return new Settings(start, max, minArea, maxArea, c.getInt("claims.maximum-claims", 20),
                c.getBoolean("claims.three-dimensional"), height, distance,
                c.getString("claims.default-name", ""), c.getBoolean("claims.accrual.enabled", true), awardSeconds, award,
                Math.max(10, c.getInt("storage.autosave-seconds", 60)), material, c.getString("tool.name", ""),
                List.copyOf(c.getStringList("tool.lore")), c.getInt("tool.custom-model-data", -1), c.getBoolean("tool.require-marker", true),
                Math.clamp(c.getInt("claims.border.duration-seconds", 15), 1, 120),
                Math.clamp(c.getInt("claims.border.maximum-points", 160), 16, 1000),
                particle, BorderSettings.read(c),
                c.getBoolean("claims.messages-on-crossing", true), Map.copyOf(defaults), Map.copyOf(groups), WorldRules.read(c));
    }
}
