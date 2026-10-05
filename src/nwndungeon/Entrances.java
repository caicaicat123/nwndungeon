package nwndungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 入口登记表。
 *
 * <p>铁门/木门都是普通方块、不是方块实体，存不了 NBT，所以标记写在区块的持久化数据里
 * （{@code Chunk#getPersistentDataContainer}，随世界存档保存，属于真 NBT 存储）。
 *
 * <p><b>两种记录格式</b>：
 * <ul>
 *   <li><b>v2（1.5.0 起）</b>：{@code v2|<kind>|<target>|<alive>|<material>|<x,y,z>}
 *       —— <b>显式记下这块是门还是触发点</b>（kind: {@code tier} 内置难度 / {@code dungeon} 自建副本），
 *       以及**门到底是什么材质**。这就解决了"入口硬编码铁门 + 石按钮"的老毛病
 *       （以前是靠"这格是不是 IRON_DOOR"反推哪头是门，所以你放木门、铜门、拉杆都不行）。</li>
 *   <li><b>v1（老数据，继续认）</b>：{@code <难度>|<alive>|<x,y,z>}，靠"这格是不是铁门"反推角色。
 *       服务器上已经登记过的自然入口就是这种，不能丢。</li>
 * </ul>
 * {@code alive=0} 表示这扇门已被破坏、永久失效。
 */
public final class Entrances {

    /** 内置难度入口。 */
    public static final String KIND_TIER = "tier";
    /** 自建副本入口（target = 副本名）。 */
    public static final String KIND_DUNGEON = "dungeon";

    public record Entry(String kind, String target, boolean alive, Location door, Location trigger,
                        String material) {

        /** 兼容老叫法：内置难度入口的 target 就是难度 id。 */
        public String tier() {
            return target;
        }

        public boolean isDungeon() {
            return KIND_DUNGEON.equals(kind);
        }
    }

    private final NWNDungeon plugin;

    public Entrances(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey key(Location door) {
        return new NamespacedKey(plugin,
                "entrance_" + door.getBlockX() + "_" + door.getBlockY() + "_" + door.getBlockZ());
    }

    private NamespacedKey triggerKey(Location location) {
        return new NamespacedKey(plugin,
                "trigger_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ());
    }

    // ---------------------------------------------------------------- 登记

    /** 登记内置难度入口（门 + 门旁的按钮/压力板）。 */
    public void register(Location door, String tier, Location trigger) {
        register(door, KIND_TIER, tier, trigger,
                door.getBlock() == null ? null : door.getBlock().getType());
    }

    /**
     * 登记一处入口：门 + 触发点（两边互指，方便从按钮反查入口）。
     *
     * @param kind     {@link #KIND_TIER} 或 {@link #KIND_DUNGEON}
     * @param target   难度 id 或自建副本名
     * @param material 门的材质（**照原样记下你放的是什么门**）
     */
    public void register(Location door, String kind, String target, Location trigger, Material material) {
        String doorValue = value(kind, target, true, material, trigger);
        door.getChunk().getPersistentDataContainer().set(key(door), PersistentDataType.STRING, doorValue);
        if (trigger != null) {
            String triggerValue = value(kind, target, true, material, door);
            trigger.getChunk().getPersistentDataContainer()
                    .set(triggerKey(trigger), PersistentDataType.STRING, triggerValue);
        }
    }

    /** 按门的位置查入口。 */
    public Entry get(Location door) {
        if (door == null || !door.isWorldLoaded()) {
            return null;
        }
        return parse(door, door.getChunk().getPersistentDataContainer()
                .get(key(door), PersistentDataType.STRING), true);
    }

    /** 按"按钮/压力板/拉杆"的位置反查入口（进本用）。 */
    public Entry byTrigger(Location trigger) {
        if (trigger == null || !trigger.isWorldLoaded()) {
            return null;
        }
        Entry entry = parse(trigger, trigger.getChunk().getPersistentDataContainer()
                .get(triggerKey(trigger), PersistentDataType.STRING), false);
        if (entry == null) {
            return null;
        }
        return new Entry(entry.kind(), entry.target(), entry.alive(), entry.door(), trigger, entry.material());
    }

    public boolean isAliveEntrance(Location door) {
        Entry entry = get(door);
        return entry != null && entry.alive();
    }

    /** 门被破坏时调用：永久作废，原地补一扇新门也不会再触发。 */
    public boolean markBroken(Location door) {
        Entry entry = get(door);
        if (entry == null || !entry.alive()) {
            return false;
        }
        String dead = encode(entry.kind(), entry.target(), false, entry.material(), null);
        door.getChunk().getPersistentDataContainer().set(key(door), PersistentDataType.STRING, dead);
        if (entry.trigger() != null && entry.trigger().isWorldLoaded()) {
            entry.trigger().getChunk().getPersistentDataContainer()
                    .set(triggerKey(entry.trigger()), PersistentDataType.STRING, dead);
        }
        return true;
    }

    /** 这个方块能不能当触发点（按钮 / 压力板 / 拉杆 —— 不再只认石头按钮）。 */
    public static boolean isTriggerMaterial(Material material) {
        return material != null && (org.bukkit.Tag.BUTTONS.isTagged(material)
                || org.bukkit.Tag.PRESSURE_PLATES.isTagged(material)
                || material == Material.LEVER);
    }

    /** 这个方块是不是门（木门/铁门/铜门都算）。 */
    public static boolean isDoorMaterial(Material material) {
        return material != null && (org.bukkit.Tag.DOORS.isTagged(material)
                || material == Material.IRON_DOOR || material == Material.COPPER_DOOR);
    }

    // ---------------------------------------------------------------- 编解码

    /** 解出来的记录（只给自检与内部用）。 */
    public record Decoded(String kind, String target, boolean alive, String material, int[] other) {
    }

    /**
     * 编码一条入口记录（**不碰世界**，方便离线自检）。
     *
     * <p>{@code other} 是"另一头"的坐标（门上记按钮、按钮上记门）；null = 只记这一头。
     */
    public static String encode(String kind, String target, boolean alive, String material, int[] other) {
        StringBuilder builder = new StringBuilder("v2|")
                .append(kind).append('|').append(target).append('|').append(alive ? '1' : '0')
                .append('|').append(material == null ? "" : material);
        if (other != null && other.length >= 3) {
            builder.append('|').append(other[0]).append(',').append(other[1]).append(',').append(other[2]);
        }
        return builder.toString();
    }

    /**
     * 解码一条入口记录。**同时认 v1 老格式**（服务器上已经登记过的自然入口就是那种），
     * 这样升级不会把老入口弄丢。
     *
     * @param currentSelf 这里那一格的坐标（v1 格式要靠它判断"这格是不是门"，
     *                    不过 {@code askedAsDoor=true} 时不会去看方块，离线也能测）
     */
    public static Decoded decode(String raw, boolean askedAsDoor, int[] currentSelf, String selfMaterial) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|");
        if (parts.length > 0 && "v2".equals(parts[0])) {
            String kind = parts.length > 1 ? parts[1] : KIND_TIER;
            String target = parts.length > 2 ? parts[2] : "iron";
            boolean alive = parts.length > 3 && "1".equals(parts[3]);
            String material = parts.length > 4 && !parts[4].isBlank() ? parts[4] : null;
            return new Decoded(kind, target, alive, material, parts.length > 5 ? parseCoords(parts[5]) : null);
        }
        // v1：<目标>|<alive>|<x,y,z>，靠"这格是不是铁门"反推角色
        boolean alive = parts.length > 1 && "1".equals(parts[1]);
        boolean atDoor = askedAsDoor || "IRON_DOOR".equals(selfMaterial);
        return new Decoded(KIND_TIER, parts[0], alive,
                atDoor ? selfMaterial : null,
                parts.length > 2 ? parseCoords(parts[2]) : null);
    }

    private static int[] parseCoords(String raw) {
        try {
            String[] coords = raw.split(",");
            if (coords.length < 3) {
                return null;
            }
            return new int[]{Integer.parseInt(coords[0].trim()), Integer.parseInt(coords[1].trim()),
                    Integer.parseInt(coords[2].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    private String value(String kind, String target, boolean alive, Material material, Location other) {
        return encode(kind, target, alive, material == null ? null : material.name(),
                other == null ? null : new int[]{other.getBlockX(), other.getBlockY(), other.getBlockZ()});
    }

    /**
     * @param askedAsDoor true = 调用方是拿着"门"的位置来查的（{@link #get}），
     *                    false = 拿着"触发点"的位置来查的（{@link #byTrigger}）
     */
    private Entry parse(Location self, String raw, boolean askedAsDoor) {
        Decoded decoded = decode(raw, askedAsDoor, new int[]{self.getBlockX(), self.getBlockY(), self.getBlockZ()},
                safeMaterial(self));
        if (decoded == null) {
            return null;
        }
        Location other = decoded.other() == null ? null
                : new Location(self.getWorld(), decoded.other()[0], decoded.other()[1], decoded.other()[2]);
        Location door = askedAsDoor ? self : other;
        Location trigger = askedAsDoor ? other : self;
        return new Entry(decoded.kind(), decoded.target(), decoded.alive(), door, trigger, decoded.material());
    }

    /** v1 记录要靠"这格是不是铁门"反推角色；世界没加载时别去碰方块。 */
    private static String safeMaterial(Location self) {
        try {
            return self.isWorldLoaded() ? self.getBlock().getType().name() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
