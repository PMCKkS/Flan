package top.pmckk.flan.config;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

public record WorldRules(Set<String> keys, boolean ignoreY) {
    public static WorldRules read(ConfigurationSection c) {
        String path = "claims.disabled-worlds";
        if (c.contains(path) && (!c.isList(path) || c.getList(path).stream().anyMatch(value -> !(value instanceof String))))
            throw new MessageException("errors.world-rules");
        Set<String> keys = new HashSet<>();
        for (String key : c.getStringList(path)) {
            if (!key.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) throw new MessageException("errors.world-key", "world", key);
            keys.add(key);
        }
        if (c.contains("claims.ignore-y") && !c.isBoolean("claims.ignore-y")) throw new MessageException("errors.world-rules");
        return new WorldRules(Set.copyOf(keys), c.getBoolean("claims.ignore-y", false));
    }
    public boolean disabled(World world) { return disabled(world.getKey().toString()); }
    public boolean disabled(String key) { return keys.contains(key); }
}
