package nwndungeon;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * 给 PlaceholderAPI 的占位符接口（菜单、记分板、聊天都能读）：
 *
 *   %nwndungeon_stamina%              当前体力
 *   %nwndungeon_stamina_max%          体力上限
 *   %nwndungeon_stamina_next%         距离回下一点还有多少秒（已满 = 0）
 *   %nwndungeon_stamina_next_text%    好看版：已满 / 3 分 20 秒
 *   %nwndungeon_stamina_bar%          进度条（■/□）
 *   %nwndungeon_stamina_full%          是否已满（true/false）
 *   %nwndungeon_cost_iron% / _gold% / _diamond%    各难度进本消耗
 *   %nwndungeon_money_iron% / _gold% / _diamond%   通关发放的金币
 *
 * 这个类只在服务器装了 PlaceholderAPI 时才会被加载（plugin.yml 里 softdepend）。
 */
public final class StaminaPlaceholders extends PlaceholderExpansion {

    private final NWNDungeon plugin;

    public StaminaPlaceholders(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "nwndungeon";
    }

    @Override
    public String getAuthor() {
        return "新世界网络";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params == null ? "" : params.toLowerCase(java.util.Locale.ROOT);
        if (key.startsWith("cost_")) {
            return String.valueOf(plugin.staminaCostFor(key.substring("cost_".length())));
        }
        if (key.startsWith("money_")) {
            Tier tier = plugin.tier(key.substring("money_".length()));
            return String.valueOf(tier == null ? 0 : tier.moneyReward());
        }
        if (player == null || player.getPlayer() == null) {
            return "";
        }
        Player online = player.getPlayer();
        Stamina stamina = plugin.stamina();
        return switch (key) {
            case "stamina" -> String.valueOf(stamina.current(online));
            case "stamina_max" -> String.valueOf(stamina.max());
            case "stamina_next" -> String.valueOf(stamina.secondsToNextPoint(online));
            case "stamina_next_text" -> nextText(stamina.secondsToNextPoint(online));
            case "stamina_bar" -> bar(stamina.current(online), stamina.max());
            case "stamina_full" -> String.valueOf(stamina.current(online) >= stamina.max());
            default -> null;   // 不认识的占位符返回 null，交回给别的插件
        };
    }

    private static String nextText(long seconds) {
        if (seconds <= 0) {
            return "已满";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return minutes > 0 ? minutes + " 分 " + rest + " 秒" : rest + " 秒";
    }

    private static String bar(int current, int max) {
        int slots = 10;
        int filled = max <= 0 ? 0 : (int) Math.round(current * slots / (double) max);
        filled = Math.max(0, Math.min(slots, filled));
        return "§a" + "■".repeat(filled) + "§8" + "□".repeat(slots - filled);
    }
}
