package nwndungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 抽掉落物 + 写箱子。
 *
 * <p>逻辑和内置副本（{@code Instances} 里那套）是一样的，但这里**故意做成独立的一份**：
 * 内置副本那条路是线上跑了很久、刚在 1.4.13 修过坑的，M4 这一版不去动它。
 * 两处该合并（M6 的清理项）—— 合并时必须带着回归测试一起做，不能顺手合。
 *
 * <p>抽奖规则：先按 {@code chance} 筛出候选（{@code chance: 1} 的一直在池里），再按 {@code weight} 加权抽；
 * 每件物品用自己写的数量区间（不写时：补给 1~2、奖励 1~4）。
 */
public final class LootRoller {

    private LootRoller() {
    }

    public static Map<Integer, ItemStack> roll(Random random, List<LootEntry> pool, int maxTypes,
                                               boolean rich, String dungeonId, NWNDungeon plugin) {
        Map<Integer, ItemStack> loot = new LinkedHashMap<>();
        if (pool == null || pool.isEmpty() || maxTypes <= 0) {
            return loot;
        }
        int types = rich ? Math.min(27, maxTypes) : 1 + random.nextInt(Math.min(3, maxTypes));
        for (LootSelection.Selected selected : LootSelection.weighted(random, pool, types, rich ? 4 : 2)) {
            int slot = random.nextInt(27);
            while (loot.containsKey(slot)) { slot = (slot + 1) % 27; }
            ItemStack stack = new ItemStack(selected.entry().material(), selected.amount());
            applyEnchants(random, stack, selected.entry(), dungeonId, plugin);
            loot.put(slot, stack);
        }
        return loot;
    }

    /** 新配置的独立概率物品；按原版堆叠上限拆分，供每人奖励或补给箱使用。 */
    public static List<ItemStack> independent(Random random, List<LootEntry> entries,
                                               String dungeonId, NWNDungeon plugin) {
        List<ItemStack> items = new ArrayList<>();
        for (LootSelection.Selected selected : LootSelection.independent(random, entries)) {
            int remaining = selected.amount();
            int stackLimit = selected.entry().material().getMaxStackSize();
            if (stackLimit <= 0) {
                throw new SafeYaml.Problem("奖励材质不能创建为背包物品：" + selected.entry().material());
            }
            while (remaining > 0) {
                int amount = Math.min(remaining, stackLimit);
                ItemStack stack = new ItemStack(selected.entry().material(), amount);
                applyEnchants(random, stack, selected.entry(), dungeonId, plugin);
                items.add(stack);
                remaining -= amount;
            }
        }
        return items;
    }

    private static LootEntry pick(Random random, List<LootEntry> candidates, int totalWeight) {
        int roll = random.nextInt(Math.max(1, totalWeight));
        for (LootEntry entry : candidates) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** 名字 → 附魔的缓存；值为 null 表示不认识（缓存下来别反复查）。 */
    private static final Map<String, Enchantment> CACHE = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();

    private static Enchantment enchant(String name, NWNDungeon plugin) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        Enchantment found = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        if (found == null && WARNED.add(key)) {
            plugin.getLogger().warning("loot.yml 里有不认识的附魔名：" + name
                    + "（已跳过；要用原版附魔 ID，例如 SHARPNESS / PROTECTION / MENDING）");
        }
        CACHE.put(key, found);
        return found;
    }

    /** 写死的附魔优先；没写又要求随机、或是"没有附魔的附魔书"就按该副本的池随机。 */
    private static void applyEnchants(Random random, ItemStack stack, LootEntry entry,
                                      String dungeonId, NWNDungeon plugin) {
        if (entry.hasEnchants()) {
            entry.enchants().forEach((name, level) -> {
                Enchantment enchantment = enchant(name, plugin);
                if (enchantment != null) {
                    add(stack, enchantment, Math.max(1, level), false);
                }
            });
            return;
        }
        if (!entry.wantsRandomEnchants() && !entry.isBlankEnchantedBook()) {
            return;
        }
        List<String> pool = plugin.lootTables().enchantPool(dungeonId);
        if (pool.isEmpty()) {
            return;
        }
        int[] levels = plugin.lootTables().enchantLevelRange(dungeonId);
        int min = entry.wantsRandomEnchants() ? Math.max(1, entry.randomEnchantMin()) : 1;
        int max = entry.wantsRandomEnchants() ? Math.max(min, entry.randomEnchantMax()) : 2;
        int count = min + random.nextInt(max - min + 1);
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        Set<Enchantment> chosen = new HashSet<>();
        for (String name : shuffled) {
            if (count <= 0) {
                break;
            }
            Enchantment enchantment = enchant(name, plugin);
            if (enchantment == null || !chosen.add(enchantment)) {
                continue;
            }
            int level = levels[0] + random.nextInt(Math.max(1, levels[1] - levels[0] + 1));
            add(stack, enchantment, level, true);
            count--;
        }
    }

    private static void add(ItemStack stack, Enchantment enchantment, int level, boolean clamp) {
        int value = clamp ? Math.min(level, enchantment.getMaxLevel()) : level;
        if (stack.getType() == Material.ENCHANTED_BOOK) {
            if (stack.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(enchantment, value, true);
                stack.setItemMeta(meta);
            }
            return;
        }
        stack.addUnsafeEnchantment(enchantment, value);
    }

    /**
     * 下一 tick 把内容写进箱子。
     *
     * <p>踩过的坑（内置副本里也写着）：方块刚 {@code setType} 完那一瞬间世界里的容器还没稳定，
     * 这时 {@code getState()} 拿到的快照背包是个空壳，填完再 {@code update()} 等于把空背包写回去。
     * 所以先按**实时容器**写，写不进再退回"快照 + update"。
     */
    public static void writeChest(Location location, Map<Integer, ItemStack> loot, NWNDungeon plugin) {
        BlockState state = location.getBlock().getState(false);
        if (!(state instanceof Chest chest)) {
            plugin.getLogger().warning("箱子没放成，跳过一个：" + location.getBlockX()
                    + "," + location.getBlockY() + "," + location.getBlockZ());
            return;
        }
        Inventory inventory = chest.getInventory();
        loot.forEach(inventory::setItem);
        if (filled(inventory)) {
            return;
        }
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            BlockState snapshot = location.getBlock().getState();
            if (snapshot instanceof Chest snap) {
                Inventory snapInventory = snap.getInventory();
                loot.forEach(snapInventory::setItem);
                snap.update(true, false);
                if (filled(snapInventory)) {
                    return;
                }
            }
            plugin.getLogger().warning("箱子写入后回读仍为空：" + location.getBlockX()
                    + "," + location.getBlockY() + "," + location.getBlockZ());
        });
    }

    private static boolean filled(Inventory inventory) {
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() != Material.AIR) {
                return true;
            }
        }
        return false;
    }
}
