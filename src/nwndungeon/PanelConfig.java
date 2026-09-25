package nwndungeon;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/** party.yml：进本确认面板的开关与文字（支持 & 颜色代码，%count% / %tier% 占位符）。 */
public final class PanelConfig {

    public boolean enabled = true;
    public String title = "§8确认进入副本";
    public String header = "§b本次一起进本：§f%count% 人";
    public String hint = "§7难度：%tier%";
    public String hint2 = "§7只有点「确认进入」才会开始 3 秒读条";
    public String confirm = "§a确认进入";
    public String confirmLore = "§7带上面这些人开始读条";
    public String cancel = "§c取消";
    public String cancelLore = "§7先不进本";

    public static PanelConfig load(JavaPlugin plugin) {
        PanelConfig config = new PanelConfig();
        File file = new File(plugin.getDataFolder(), "party.yml");
        if (!file.exists()) {
            plugin.saveResource("party.yml", false);
        }
        if (!file.exists()) {
            return config;
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        config.enabled = yaml.getBoolean("confirm-panel", true);
        config.title = color(yaml.getString("title", config.title));
        config.header = color(yaml.getString("header", config.header));
        config.hint = color(yaml.getString("hint", config.hint));
        config.hint2 = color(yaml.getString("hint2", config.hint2));
        config.confirm = color(yaml.getString("confirm", config.confirm));
        config.cancel = color(yaml.getString("cancel", config.cancel));
        List<String> confirmLore = yaml.getStringList("confirm-lore");
        if (!confirmLore.isEmpty()) {
            config.confirmLore = color(confirmLore.get(0));
        }
        List<String> cancelLore = yaml.getStringList("cancel-lore");
        if (!cancelLore.isEmpty()) {
            config.cancelLore = color(cancelLore.get(0));
        }
        return config;
    }

    /** 允许用 & 代替 § 写颜色代码。 */
    public static String color(String text) {
        return text == null ? "" : text.replace('&', '§');
    }

    public String header(int count) {
        return header.replace("%count%", String.valueOf(count));
    }

    public String hint(String tierName) {
        return hint.replace("%tier%", tierName == null ? "" : tierName);
    }
}
