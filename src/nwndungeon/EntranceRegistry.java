package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 自然生成副本入口的轻量登记表（{@code entrances.yml}）。
 *
 * 铁门的标记本身写在区块 PDC 里，但"找最近的入口"没法靠扫区块（自然入口 1/400 区块，
 * 想找到一个往往要加载上万区块，直接卡服）。所以生成入口时顺手在这里记一笔：
 * 世界 + 坐标 + 难度 + 是否还有效；`/dungeon locate` 直接查这张表。
 */
public final class EntranceRegistry {

    public static final class Entry {
        public final String world;
        public final int x;
        public final int y;
        public final int z;
        public final String tier;
        public final boolean alive;
        public final long createdAt;

        Entry(String world, int x, int y, int z, String tier, boolean alive, long createdAt) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tier = tier;
            this.alive = alive;
            this.createdAt = createdAt;
        }

        public Location toLocation() {
            World bukkitWorld = Bukkit.getWorld(world);
            return bukkitWorld == null ? null : new Location(bukkitWorld, x + 0.5, y, z + 0.5);
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public EntranceRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "entrances.yml");
        load();
    }

    public int size() {
        return entries.size();
    }

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (var raw : yaml.getMapList("entrances")) {
            try {
                Object tierRaw = raw.get("tier");
                entries.add(new Entry(
                        String.valueOf(raw.get("world")),
                        ((Number) raw.get("x")).intValue(),
                        ((Number) raw.get("y")).intValue(),
                        ((Number) raw.get("z")).intValue(),
                        tierRaw == null ? "iron" : String.valueOf(tierRaw),
                        !Boolean.FALSE.equals(raw.get("alive")),
                        raw.get("created") instanceof Number number ? number.longValue() : 0L));
            } catch (Exception ignored) {
                // 单条坏了就跳过，别影响其它入口
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (Entry entry : entries) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("world", entry.world);
            map.put("x", entry.x);
            map.put("y", entry.y);
            map.put("z", entry.z);
            map.put("tier", entry.tier);
            map.put("alive", entry.alive);
            if (entry.createdAt > 0) {
                map.put("created", entry.createdAt);
            }
            out.add(map);
        }
        yaml.set("entrances", out);
        try {
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("入口登记表保存失败：" + e.getMessage());
        }
    }

    /** 生成入口时登记（同一个门只记一次）。 */
    public void add(String world, int x, int y, int z, String tier) {
        for (Entry entry : entries) {
            if (entry.world.equals(world) && entry.x == x && entry.y == y && entry.z == z) {
                return;
            }
        }
        entries.add(new Entry(world, x, y, z, tier.toLowerCase(Locale.ROOT), true, System.currentTimeMillis()));
        save();
    }

    /** 门被破坏后从"可用名单"里划掉。 */
    public void markBroken(String world, int x, int y, int z) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.world.equals(world) && entry.x == x && entry.y == y && entry.z == z) {
                entries.set(i, new Entry(entry.world, entry.x, entry.y, entry.z, entry.tier, false, entry.createdAt));
                save();
                return;
            }
        }
    }

    /** 离 from 最近的若干个仍然有效的入口（tierFilter 为 null 表示不限难度）。 */
    public List<Entry> nearest(Location from, String tierFilter, int limit) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.alive) {
                continue;
            }
            if (tierFilter != null && !entry.tier.equalsIgnoreCase(tierFilter)) {
                continue;
            }
            if (entry.toLocation() != null) {
                found.add(entry);
            }
        }
        found.sort(Comparator.comparingDouble(entry -> {
            Location location = entry.toLocation();
            if (location == null || !location.getWorld().equals(from.getWorld())) {
                return Double.MAX_VALUE;   // 别的世界排最后
            }
            return location.distanceSquared(from);
        }));
        return found.subList(0, Math.min(limit, found.size()));
    }

    /** 两个坐标之间大概的方位（给玩家看的方向）。 */
    public static String direction(double dx, double dz) {
        if (Math.abs(dx) < 1 && Math.abs(dz) < 1) {
            return "就在脚下";
        }
        String ns = dz < 0 ? "北" : (dz > 0 ? "南" : "");
        String ew = dx > 0 ? "东" : (dx < 0 ? "西" : "");
        if (Math.abs(dz) > Math.abs(dx) * 2) {
            return ns;
        }
        if (Math.abs(dx) > Math.abs(dz) * 2) {
            return ew;
        }
        return ns + ew;
    }
}
