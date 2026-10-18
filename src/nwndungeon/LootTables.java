package nwndungeon;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** loot.yml：按难度分开写的掉落表（不动 config.yml 也能改奖励）。 */
public final class LootTables {

    /** 配置里没写 enchant-pool 时用的兜底池（原版附魔 ID，大小写都行）。 */
    public static final List<String> DEFAULT_ENCHANT_POOL = List.of(
            "sharpness", "protection", "efficiency", "unbreaking", "power", "knockback");
    /** 配置里没写 enchant-levels 时的兜底等级区间。 */
    public static final int[] DEFAULT_ENCHANT_LEVELS = {1, 2};

    private final Map<String, List<LootEntry>> supply = new HashMap<>();
    private final Map<String, List<LootEntry>> reward = new HashMap<>();
    /** 每个副本的随机附魔池（存名字，生成物品时才去注册表解析）。 */
    private final Map<String, List<String>> enchantPools = new HashMap<>();
    private final Map<String, int[]> enchantLevels = new HashMap<>();
    /**
     * 配置里**真的存在**的键（{@code <段名>:supply-chest} 这种）。
     *
     * <p>为什么要记：{@link #parse} 对空列表会兜底成"一块面包"，所以"没配"和"配了但空"
     * 光看解析结果分不出来 —— 编辑器里回读奖励箱内容、M4 决定要不要放箱子都得知道原始情况。
     */
    private final Set<String> configuredKeys = new HashSet<>();

    public static LootTables load(JavaPlugin plugin) {
        LootTables tables = new LootTables();
        File file = new File(plugin.getDataFolder(), "loot.yml");
        if (!file.exists()) {
            plugin.saveResource("loot.yml", false);
        }
        if (!file.exists()) {
            return tables;
        }
        YamlConfiguration yaml = SafeYaml.read(file);
        // 每段 = 一个副本（现在：iron / gold / diamond；以后：模板名）
        ConfigurationSection dungeons = yaml.getConfigurationSection("dungeons");
        if (dungeons == null) {
            dungeons = yaml.getConfigurationSection("tiers");   // 兼容旧写法
        }
        if (dungeons == null) {
            throw new SafeYaml.Problem("loot.yml 必须包含 dungeons: 配置段；空表请写 dungeons: {}");
        }
        for (String id : dungeons.getKeys(false)) {
            ConfigurationSection section = dungeons.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            tables.supply.put(key, parse(section.getList("supply-chest")));
            tables.reward.put(key, parse(section.getList("reward-chest")));
            if (section.isSet("supply-chest")) {
                tables.configuredKeys.add(key + ":supply-chest");
            }
            if (section.isSet("reward-chest")) {
                tables.configuredKeys.add(key + ":reward-chest");
            }
            tables.enchantPools.put(key, names(section.getList("enchant-pool")));
            tables.enchantLevels.put(key, levelRange(section.getList("enchant-levels")));
        }
        plugin.getLogger().info("掉落表已载入：" + tables.supply.size() + " 个副本（loot.yml）");
        return tables;
    }

    public boolean has(String tierId) {
        String key = tierId == null ? "" : tierId.toLowerCase(Locale.ROOT);
        return supply.containsKey(key) || reward.containsKey(key);
    }

    /** 配置里是不是真的写了这一栏（{@code supply-chest} / {@code reward-chest}）。 */
    public boolean configured(String dungeonId, String field) {
        String key = dungeonId == null ? "" : dungeonId.toLowerCase(Locale.ROOT);
        return configuredKeys.contains(key + ":" + field);
    }

    public List<LootEntry> supply(String tierId) {
        return supply.getOrDefault(tierId == null ? "" : tierId.toLowerCase(Locale.ROOT), List.of());
    }

    public List<LootEntry> reward(String tierId) {
        return reward.getOrDefault(tierId == null ? "" : tierId.toLowerCase(Locale.ROOT), List.of());
    }

    /** 某个副本的随机附魔候选池；没配就用兜底池。 */
    public List<String> enchantPool(String tierId) {
        List<String> configured = enchantPools.get(tierId == null ? "" : tierId.toLowerCase(Locale.ROOT));
        return configured == null || configured.isEmpty() ? DEFAULT_ENCHANT_POOL : configured;
    }

    /** 某个副本的随机附魔等级区间 [最小, 最大]（含两端）；没配就用兜底。 */
    public int[] enchantLevelRange(String tierId) {
        int[] configured = enchantLevels.get(tierId == null ? "" : tierId.toLowerCase(Locale.ROOT));
        return configured == null ? DEFAULT_ENCHANT_LEVELS : configured;
    }

    /**
     * 解析掉落表：纯物品名，或带 weight / min / max / chance / enchants / random-enchants 的写法都认。
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
        return out;
    }

    /** 新格式每项 amount 默认 1；保留显式 min/max 供迁移，禁止同一项写两种数量。 */
    public static List<LootEntry> parseIndependent(List<?> raw) {
        List<LootEntry> out = new ArrayList<>();
        if (raw == null) { return out; }
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new SafeYaml.Problem("独立奖励必须逐项填写 item、amount、chance");
            }
            if (map.containsKey("weight")) {
                throw new SafeYaml.Problem("新格式奖励不使用 weight，请用 chance 填写独立概率");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            if (map.containsKey("amount") && (map.containsKey("min") || map.containsKey("max"))) {
                throw new SafeYaml.Problem("amount 与 min/max 不能同时填写");
            }
            if (map.containsKey("amount")) {
                int amount = number(map.get("amount"), 1);
                if (amount <= 0 || amount > 2304) { throw new SafeYaml.Problem("amount 必须为 1 到 2304 的整数"); }
                normalized.put("min", amount);
                normalized.put("max", amount);
            } else if (!map.containsKey("min") && !map.containsKey("max")) {
                normalized.put("min", 1);
                normalized.put("max", 1);
            }
            out.add(parseEntry(normalized));
        }
        return out;
    }

    private static LootEntry bread() {
        return new LootEntry(Material.BREAD, 1, 0, 0, 1.0, Map.of(), 0, 0);
    }

    private static LootEntry parseEntry(Object entry) {
        if (entry instanceof String text) {
            Material material = Material.matchMaterial(text.trim());
            if (isAir(material)) { throw new SafeYaml.Problem("无法识别的掉落物品：" + text); }
            return new LootEntry(material, 1, 0, 0, 1.0, Map.of(), 0, 0);
        }
        if (entry instanceof ConfigurationSection section) {
            return parseEntry(section.getValues(false));
        }
        if (entry instanceof Map<?, ?> map) {
            int[] random = randomCount(map.get("random-enchants"));
            int weight = number(map.get("weight"), 1);
            int min = number(map.get("min"), 0);
            int max = number(map.get("max"), 0);
            if (weight <= 0 || min < 0 || max < 0 || (max > 0 && max < min) || max > 2304 || min > 2304) {
                throw new SafeYaml.Problem("掉落权重须大于 0；数量须为 0 到 2304 且 max 不小于 min");
            }
            Object chanceRaw = map.get("chance");
            if (chanceRaw != null && !(chanceRaw instanceof Number)) {
                throw new SafeYaml.Problem("chance 须填写 0 到 1 的数字，例如 0.3");
            }
            return new LootEntry(
                    material(map.get("item") == null ? null : String.valueOf(map.get("item"))),
                    weight, min, max,
                    clampChance(map.get("chance") instanceof Number value ? value.doubleValue() : 1.0),
                    enchants(map.get("enchants")),
                    random[0],
                    random[1]);
        }
        throw new SafeYaml.Problem("掉落条目必须是物品名或键值表");
    }

    /**
     * 解析 enchants：{ SHARPNESS: 3, UNBREAKING: 2 }。
     * 名字统一转小写存着（原版附魔 ID 就是小写），到生成物品时再解析成 Enchantment。
     */
    private static Map<String, Integer> enchants(Object raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw instanceof ConfigurationSection section) {
            for (String name : section.getKeys(false)) {
                out.put(name.toLowerCase(Locale.ROOT), Math.max(1, section.getInt(name)));
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                out.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT),
                        Math.max(1, number(entry.getValue(), 1)));
            }
        }
        return out;
    }

    /**
     * 解析 random-enchants：写 `2` = 固定 2 条，写 `[1, 3]` = 1~3 条。
     * 不写返回 {0, 0}（= 不随机）。
     */
    private static int[] randomCount(Object raw) {
        if (raw instanceof Number value) {
            int count = Math.max(1, value.intValue());
            return new int[]{count, count};
        }
        if (raw instanceof List<?> list && !list.isEmpty()) {
            int min = Math.max(1, number(list.get(0), 1));
            int max = list.size() > 1 ? Math.max(min, number(list.get(1), min)) : min;
            return new int[]{min, max};
        }
        return new int[]{0, 0};
    }

    private static List<String> names(List<?> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object entry : raw) {
            if (entry != null && !String.valueOf(entry).isBlank()) {
                out.add(String.valueOf(entry).trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 解析 enchant-levels：[最小, 最大]；只写一个数就是固定等级。 */
    private static int[] levelRange(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int first = Math.max(1, number(raw.get(0), 1));
        int second = raw.size() > 1 ? Math.max(first, number(raw.get(1), first)) : first;
        return new int[]{first, second};
    }

    private static Material material(String name) {
        Material material = name == null ? null : Material.matchMaterial(name.trim());
        if (isAir(material)) { throw new SafeYaml.Problem("无法识别的掉落物品：" + name); }
        return material;
    }

    /**
     * 和 {@code Material#isAir()} 同一个判断，但**不碰服务器注册表**。
     *
     * 好处是整条配置解析链路可以脱离服务器单独跑（注册表没初始化时 isAir() 会抛
     * "No RegistryAccess implementation found"），掉落表读错时能离线验证，不用起服。
     */
    private static boolean isAir(Material material) {
        return material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static int number(Object raw, int fallback) {
        if (raw == null) { return fallback; }
        if (!(raw instanceof Number value) || !Double.isFinite(value.doubleValue())
                || value.doubleValue() != value.intValue()) {
            throw new SafeYaml.Problem("需要整数，实际填写为：" + raw);
        }
        return value.intValue();
    }

    private static double clampChance(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new SafeYaml.Problem("chance 必须是 0 到 1 的有限数字");
        }
        return value;
    }
}
