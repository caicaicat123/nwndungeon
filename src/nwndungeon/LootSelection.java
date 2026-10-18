package nwndungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 只决定抽中了什么、多少，不创建物品，不依赖服务端注册表。 */
public final class LootSelection {
    public record Selected(LootEntry entry, int amount) { }
    private LootSelection() { }

    /** 每项仅判定一次概率，互不排斥；weight 不参与这种模式。 */
    public static List<Selected> independent(Random random, List<LootEntry> entries) {
        List<Selected> selected = new ArrayList<>();
        for (LootEntry entry : entries) {
            if (accept(random, entry.chance())) {
                selected.add(new Selected(entry, amount(random, entry, 1)));
            }
        }
        return selected;
    }

    /** 兼容旧池：准入概率后加权抽取，可重复；全未准入时保持空池，绝不回退原池。 */
    public static List<Selected> weighted(Random random, List<LootEntry> entries, int draws, int defaultMax) {
        List<LootEntry> candidates = new ArrayList<>();
        long weight = 0;
        for (LootEntry entry : entries) {
            if (accept(random, entry.chance())) {
                if (entry.weight() <= 0) { throw new IllegalArgumentException("掉落权重必须大于 0"); }
                candidates.add(entry);
                weight = Math.addExact(weight, entry.weight());
            }
        }
        List<Selected> selected = new ArrayList<>();
        if (candidates.isEmpty()) { return selected; }
        for (int i = 0; i < draws; i++) {
            long roll = random.nextLong(weight);
            for (LootEntry entry : candidates) {
                roll -= entry.weight();
                if (roll < 0) {
                    selected.add(new Selected(entry, amount(random, entry, defaultMax)));
                    break;
                }
            }
        }
        return selected;
    }

    private static boolean accept(Random random, double chance) {
        if (!Double.isFinite(chance) || chance < 0 || chance > 1) {
            throw new IllegalArgumentException("物品概率必须在 0 到 1 之间");
        }
        return chance > 0 && (chance == 1 || random.nextDouble() < chance);
    }

    private static int amount(Random random, LootEntry entry, int defaultMax) {
        int min = entry.min() > 0 ? entry.min() : 1;
        int max = entry.max() > 0 ? entry.max() : defaultMax;
        if (max < min) { max = min; }
        return min == max ? min : min + random.nextInt(max - min + 1);
    }
}
