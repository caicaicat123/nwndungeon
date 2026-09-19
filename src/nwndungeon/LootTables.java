package nwndungeon;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** loot.yml：按难度分开写的掉落表（不动 config.yml 也能改奖励）。 */
public final class LootTables {

    private final Map<String, List<LootEntry>> supply = new HashMap<>();
    private final Map<String, List<LootEntry>> reward = new HashMap<>();

    public static LootTables load(JavaPlugin plugin) {
        LootTables tables = new LootTables();
        File file = new File(plugin.getDataFolder(), "loot.yml");
        if (!file.exists()) {
            plugin.saveResource("loot.yml", false);
        }
        if (!file.exists()) {
            return tables;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // 每段 = 一个副本（现在：iron / gold / diamond；以后：模板名）
        ConfigurationSection dungeons = yaml.getConfigurationSection("dungeons");
        if (dungeons == null) {
            dungeons = yaml.getConfigurationSection("tiers");   // 兼容旧写法
        }
        if (dungeons == null) {
            return tables;
        }
        for (String id : dungeons.getKeys(false)) {
            ConfigurationSection section = dungeons.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            tables.supply.put(key, parse(section.getList("supply-chest")));
            tables.reward.put(key, parse(section.getList("reward-chest")));
        }
        plugin.getLogger().info("掉落表已载入：" + tables.supply.size() + " 个副本（loot.yml）");
        return tables;
    }

    public boolean has(String tierId) {
        String key = tierId == null ? "" : tierId.toLowerCase(Locale.ROOT);
        return supply.containsKey(key) || reward.containsKey(key);
    }

    public List<LootEntry> supply(String tierId) {
        return supply.getOrDefault(tierId == null ? "" : tierId.toLowerCase(Locale.ROOT), List.of());
    }

    public List<LootEntry> reward(String tierId) {
        return reward.getOrDefault(tierId == null ? "" : tierId.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * 解析掉落表：纯物品名，或带 weight / min / max / chance 的写法都认。
     * 整个池子解析为空时兜底给一块面包，避免箱子彻底空着。
     */
    public static List<LootEntry> parse(List<?> raw) {
        List<LootEntry> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object entry : raw) {
            LootEntry parsed = parseEntry(entry);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out.isEmpty() ? List.of(new LootEntry(Material.BREAD, 1, 0, 0, 1.0)) : out;
    }

    private static LootEntry parseEntry(Object entry) {
        if (entry instanceof String text) {
            Material material = Material.matchMaterial(text.trim());
            return material == null || material.isAir() ? null : new LootEntry(material, 1, 0, 0, 1.0);
        }
        if (entry instanceof ConfigurationSection section) {
            return new LootEntry(
                    material(section.getString("item")),
                    Math.max(1, section.getInt("weight", 1)),
                    Math.max(0, section.getInt("min", 0)),
                    Math.max(0, section.getInt("max", 0)),
                    clampChance(section.getDouble("chance", 1.0)));
        }
        if (entry instanceof Map<?, ?> map) {
            return new LootEntry(
                    material(map.get("item") == null ? null : String.valueOf(map.get("item"))),
                    Math.max(1, number(map.get("weight"), 1)),
                    Math.max(0, number(map.get("min"), 0)),
                    Math.max(0, number(map.get("max"), 0)),
                    clampChance(map.get("chance") instanceof Number value ? value.doubleValue() : 1.0));
        }
        return null;
    }

    private static Material material(String name) {
        Material material = name == null ? null : Material.matchMaterial(name.trim());
        return material == null || material.isAir() ? Material.BREAD : material;
    }

    private static int number(Object raw, int fallback) {
        return raw instanceof Number value ? value.intValue() : fallback;
    }

    private static double clampChance(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
