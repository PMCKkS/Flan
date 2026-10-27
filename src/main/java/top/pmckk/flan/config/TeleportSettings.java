package top.pmckk.flan.config;

import org.bukkit.configuration.ConfigurationSection;

public record TeleportSettings(boolean velocity, String secret, int timeoutSeconds) {
    public static TeleportSettings read(ConfigurationSection config) {
        if (config.contains("teleport.velocity.enabled") && !config.isBoolean("teleport.velocity.enabled")) throw new MessageException("errors.teleport-config");
        boolean enabled = config.getBoolean("teleport.velocity.enabled", false);
        if (config.contains("teleport.velocity.secret") && !config.isString("teleport.velocity.secret")) throw new MessageException("errors.teleport-config");
        String secret = config.getString("teleport.velocity.secret", "");
        int timeout = config.getInt("teleport.timeout-seconds", 15);
        if (config.contains("teleport.timeout-seconds") && !config.isInt("teleport.timeout-seconds")
                || timeout < 5 || timeout > 60 || enabled && (secret.length() < 32 || secret.length() > 256)) throw new MessageException("errors.teleport-config");
        return new TeleportSettings(enabled, secret, timeout);
    }
}
