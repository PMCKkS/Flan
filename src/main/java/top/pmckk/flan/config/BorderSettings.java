package top.pmckk.flan.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.Locale;
import java.util.function.Predicate;

public record BorderSettings(Mode mode, Material main, Material sub, Material conflict, Material edit, boolean showHoldingTool, int nearbyRadius) {
    public enum Mode { BLOCKS, PARTICLES }
    public enum Kind { MAIN, SUB, CONFLICT, EDIT }

    public Material material(Kind kind) {
        return switch (kind) { case MAIN -> main; case SUB -> sub; case CONFLICT -> conflict; case EDIT -> edit; };
    }

    public static BorderSettings read(FileConfiguration config) {
        return read(config, material -> material.isBlock() && material.isSolid());
    }

    static BorderSettings read(FileConfiguration config, Predicate<Material> validBlock) {
        String mode = config.getString("claims.border.mode", "BLOCKS");
        Mode parsed;
        try { parsed = Mode.valueOf(mode.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new MessageException("errors.border-mode", "mode", mode); }
        return new BorderSettings(parsed, material(config, "main", "GOLD_BLOCK", validBlock), material(config, "sub", "IRON_BLOCK", validBlock),
                material(config, "conflict", "REDSTONE_BLOCK", validBlock), material(config, "edit", "LAPIS_BLOCK", validBlock),
                config.getBoolean("claims.border.show-holding-tool", true), Math.clamp(config.getInt("claims.border.nearby-radius", 32), 0, 128));
    }

    private static Material material(FileConfiguration config, String kind, String fallback, Predicate<Material> validBlock) {
        String name = config.getString("claims.border.blocks." + kind, fallback);
        Material material = Material.matchMaterial(name);
        if (material == null || !validBlock.test(material))
            throw new MessageException("errors.border-material", "kind", kind, "material", name);
        return material;
    }
}
