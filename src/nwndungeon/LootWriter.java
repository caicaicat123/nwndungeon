package nwndungeon;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把编辑器 GUI 里放好的物品**写进 {@code loot.yml}**（Q5：奖励物品复用现有掉落表，段名 = 副本名）。
 *
 * <p>为什么物品从 GUI 来、数值从配置来：GUI 里最自然的是"放东西"，
 * 而权重 / 概率 / 数量区间这些数值在 yml 里改最方便（还能直接看到语法）。
 * 所以这里写进去的每项都是 {@code weight: 1, chance: 1.0, min/max = 你放的个数}，
 * 想调权重就去 yml 里改。
 *
 * <p>只写 {@code item / weight / chance / min / max / enchants} —— 附魔会保留（含附魔书的**存储附魔**，
 * 这正是 1.4.12 修的那个坑），自定义名字与 lore **不保留**（掉落表里没有这两个字段）。
 */
public final class LootWriter {

    private LootWriter() {
    }

    /**
     * 写一段掉落表。
     *
     * @param key {@code supply-chest}（每关补给箱）或 {@code reward-chest}（通关奖励箱）
     * @return 写进去几项；items 为空时返回 0（并把这一段清掉）
     */
    public static int write(NWNDungeon plugin, String dungeonName, String key, List<ItemStack> items) {
        File file = new File(plugin.getDataFolder(), "loot.yml");
        YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        String path = "dungeons." + dungeonName + "." + key;
        List<Map<String, Object>> out = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("item", stack.getType().name());
            map.put("weight", 1);
            map.put("chance", 1.0);
            int amount = Math.max(1, stack.getAmount());
            map.put("min", amount);
            map.put("max", amount);
            Map<String, Integer> enchants = enchantsOf(stack);
            if (!enchants.isEmpty()) {
                map.put("enchants", enchants);
            }
            out.add(map);
        }
        if (out.isEmpty()) {
            yaml.set(path, null);
        } else {
            yaml.set(path, out);
        }
        try {
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("写 loot.yml 失败：" + e.getMessage());
            return 0;
        }
        plugin.reloadLoot();
        plugin.getLogger().info("已把 " + out.size() + " 项写进 loot.yml 的 " + path);
        return out.size();
    }

    /** 物品上的附魔（附魔书取**存储附魔**，普通物品取普通附魔）。 */
    private static Map<String, Integer> enchantsOf(ItemStack stack) {
        Map<String, Integer> out = new LinkedHashMap<>();
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            for (Map.Entry<Enchantment, Integer> entry : storage.getStoredEnchants().entrySet()) {
                out.put(key(entry.getKey()), entry.getValue());
            }
        }
        for (Map.Entry<Enchantment, Integer> entry : stack.getEnchantments().entrySet()) {
            out.put(key(entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** 附魔名写成 loot.yml 认的样子（原版 ID，小写）。 */
    private static String key(Enchantment enchantment) {
        try {
            return enchantment.getKey().getKey().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return enchantment.toString().toLowerCase(Locale.ROOT);
        }
    }
}
