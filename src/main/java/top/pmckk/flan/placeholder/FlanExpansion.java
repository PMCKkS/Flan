package top.pmckk.flan.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import top.pmckk.flan.Flan;
import top.pmckk.flan.model.*;
import java.util.Locale;

public final class FlanExpansion extends PlaceholderExpansion {
    private final Flan plugin;
    public FlanExpansion(Flan plugin) { this.plugin = plugin; }
    public String getIdentifier() { return "flan"; }
    public String getAuthor() { return "PMCKK"; }
    public String getVersion() { return plugin.getDescription().getVersion(); }
    public boolean persist() { return true; }
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || plugin.registry() == null) return "0";
        var registry = plugin.registry(); var id = player.getUniqueId();
        synchronized (registry) {
            PlayerAccount account = registry.account(id);
            return switch (params.toLowerCase(Locale.ROOT)) {
                case "blocks", "owned_blocks", "used_blocks" -> Long.toString(registry.used(id));
                case "remaining_blocks", "free_blocks" -> Long.toString(registry.remaining(id));
                case "total_blocks", "claim_blocks" -> Long.toString(account == null ? 0 : account.allowance());
                case "claim_count" -> Long.toString(registry.count(id));
                default -> null;
            };
        }
    }
}
