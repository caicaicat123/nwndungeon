package nwndungeon;

import org.bukkit.Material;

import java.util.Map;

/**
 * 掉落表里的一项。
 *
 * <pre>
 * item             物品（原版 ID）
 * weight           权重（同一池子里谁更容易被抽中）
 * min/max          抽中后给几个（不写 = 用箱子默认区间：补给箱 1~2、奖励箱 1~4）
 * chance           这一项本次是否进入候选池的概率（默认 1 = 一定在池里）
 * enchants         写死的附魔：{ SHARPNESS: 3, UNBREAKING: 2 }（写了就完全按它来）
 * random-enchants  随机附魔条数：2 或 [1, 3]；附魔名与等级从该副本那一段的
 *                  enchant-pool / enchant-levels 取
 * </pre>
 *
 * 写法（四种都认）：
 * <pre>
 *   - GOLDEN_APPLE
 *   - { item: BREAD, weight: 3, min: 1, max: 4, chance: 0.5 }
 *   - { item: ENCHANTED_BOOK, weight: 2, enchants: { MENDING: 1 } }
 *   - { item: ENCHANTED_BOOK, weight: 3, random-enchants: [1, 2] }
 * </pre>
 *
 * <b>附魔名在解析阶段只当字符串存着</b>，真正去注册表里找 Enchantment 是"生成物品时"做的：
 * 这样配置解析可以脱离服务器单独验证，也不会为没抽到的物品白解析附魔。
 */
public record LootEntry(Material material, int weight, int min, int max, double chance,
                        Map<String, Integer> enchants, int randomEnchantMin, int randomEnchantMax) {

    /** 配置里写死了附魔。 */
    public boolean hasEnchants() {
        return !enchants.isEmpty();
    }

    /** 配置里要求随机附魔。 */
    public boolean wantsRandomEnchants() {
        return randomEnchantMax > 0;
    }

    /**
     * 是不是"配了附魔书却一条附魔都没有"。
     *
     * 原版里这种空附魔书是废纸（名字照显示「附魔书」，但没有任何附魔、铁砧上也用不了），
     * 1.4.11 及以前就是这样发出去的 —— 生成物品时必须给它补上随机附魔。
     */
    public boolean isBlankEnchantedBook() {
        return material == Material.ENCHANTED_BOOK && enchants.isEmpty() && randomEnchantMax <= 0;
    }
}
