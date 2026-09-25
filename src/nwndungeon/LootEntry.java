package nwndungeon;

import org.bukkit.Material;

/**
 * 掉落表里的一项。
 *
 * item   物品
 * weight 权重（同一池子里谁更容易被抽中）
 * min/max 抽中后给几个（不写 = 用箱子默认区间：补给箱 1~2、奖励箱 1~4）
 * chance 这一项本次是否进入候选池的概率（默认 1 = 一定在池里）
 *
 * 写法（两种都认）：
 *   - GOLDEN_APPLE
 *   - { item: BREAD, weight: 3, min: 1, max: 4, chance: 0.5 }
 */
public record LootEntry(Material material, int weight, int min, int max, double chance) {
}
