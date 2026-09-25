package nwndungeon;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一档副本难度：铁 / 金 / 钻。
 *
 * 房间阵容支持多波，两种写法都认：
 * <pre>
 * rooms:
 *   - "ZOMBIE:5"                  # 旧写法：只有一波（兼容）
 *   - waves:                      # 多波
 *       - "ZOMBIE:3"
 *       - "ZOMBIE:2,SKELETON:2"
 * </pre>
 * 首领房的 {@code boss.waves} 是"前面的小怪波"，最后一波 = {@code boss.guards} + 首领本人。
 */
public record Tier(String id, String display, Material floorBlock, int timeLimitMinutes,
                   List<List<String>> rooms, String bossType, String bossName, int bossHealth,
                   List<String> bossWaves, String bossGuards,
                   List<LootEntry> supplyItems, List<LootEntry> rewardItems,
                   int moneyReward, int staminaCost) {

    public static Tier from(String id, ConfigurationSection section, LootTables loot) {
        Material block = Material.matchMaterial(String.valueOf(section.getString("block", "IRON_BLOCK")));
        return new Tier(id,
                section.getString("display", id),
                block == null ? Material.IRON_BLOCK : block,
                Math.max(1, section.getInt("time-limit-minutes", 20)),
                readRooms(section.getList("rooms")),
                section.getString("boss.type", "ZOMBIE").toUpperCase(Locale.ROOT),
                section.getString("boss.name", "§c首领"),
                Math.max(20, section.getInt("boss.health", 60)),
                toStrings(section.getList("boss.waves")),
                section.getString("boss.guards", ""),
                loot != null && loot.has(id) ? loot.supply(id) : LootTables.parse(section.getList("supply-chest")),
                loot != null && loot.has(id) ? loot.reward(id) : LootTables.parse(section.getList("reward-chest")),
                Math.max(0, section.getInt("money-reward", 0)),
                Math.max(0, section.getInt("stamina-cost", 0)));
    }

    /** 首领房的波次（每项 = 一波的阵容字符串）：前面几波小怪 + 最后一波（随首领一起登场）。 */
    public List<String> bossRoomWaves() {
        List<String> waves = new ArrayList<>(bossWaves);
        waves.add(bossGuards == null ? "" : bossGuards);
        return waves;
    }

    /** 每个房间 = 若干波；兼容"一个字符串 = 一波"的旧写法。 */
    private static List<List<String>> readRooms(List<?> raw) {
        List<List<String>> rooms = new ArrayList<>();
        if (raw == null) {
            return rooms;
        }
        for (Object entry : raw) {
            List<String> waves = wavesOf(entry);
            if (!waves.isEmpty()) {
                rooms.add(waves);
            }
        }
        return rooms;
    }

    private static List<String> wavesOf(Object entry) {
        if (entry instanceof String text) {
            return text.isBlank() ? List.of() : List.of(text);
        }
        if (entry instanceof ConfigurationSection section) {
            return toStrings(section.getList("waves"));
        }
        if (entry instanceof Map<?, ?> map) {
            return map.get("waves") instanceof List<?> list ? toStrings(list) : List.of();
        }
        return List.of();
    }

    private static List<String> toStrings(List<?> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object entry : raw) {
            if (entry != null && !String.valueOf(entry).isBlank()) {
                out.add(String.valueOf(entry));
            }
        }
        return out;
    }

}
