package nwndungeon;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 入口登记表。
 *
 * 铁门是普通方块、不是方块实体，存不了 NBT，所以标记写在区块的持久化数据里
 * （Chunk#getPersistentDataContainer，随世界存档保存，属于真 NBT 存储）。
 * 记录格式：<难度>|1|x,y,z 表示有效（最后一段是"另一头"的坐标：门上记按钮、按钮上记门），
 * <难度>|0 表示这扇门已被破坏、永久失效。
 */
public final class Entrances {

    public record Entry(String tier, boolean alive, Location door, Location trigger) {
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

    /** 登记入口：门 + 门旁的按钮/压力板（两边互指，方便从按钮反查入口）。 */
    public void register(Location door, String tier, Location trigger) {
        door.getChunk().getPersistentDataContainer()
                .set(key(door), PersistentDataType.STRING, value(tier, trigger));
        if (trigger != null) {
            trigger.getChunk().getPersistentDataContainer()
                    .set(triggerKey(trigger), PersistentDataType.STRING, value(tier, door));
        }
    }

    public Entry get(Location door) {
        if (door == null || !door.isWorldLoaded()) {
            return null;
        }
        return parse(door, door.getChunk().getPersistentDataContainer()
                .get(key(door), PersistentDataType.STRING));
    }

    /** 按"按钮/压力板"的位置反查入口（队伍按钮进本用）。 */
    public Entry byTrigger(Location trigger) {
        if (trigger == null || !trigger.isWorldLoaded()) {
            return null;
        }
        Entry entry = parse(trigger, trigger.getChunk().getPersistentDataContainer()
                .get(triggerKey(trigger), PersistentDataType.STRING));
        if (entry == null) {
            return null;
        }
        return new Entry(entry.tier(), entry.alive(), entry.door(), trigger);
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
        door.getChunk().getPersistentDataContainer()
                .set(key(door), PersistentDataType.STRING, entry.tier() + "|0");
        if (entry.trigger() != null && entry.trigger().isWorldLoaded()) {
            entry.trigger().getChunk().getPersistentDataContainer()
                    .set(triggerKey(entry.trigger()), PersistentDataType.STRING, entry.tier() + "|0");
        }
        return true;
    }

    private String value(String tier, Location other) {
        StringBuilder builder = new StringBuilder(tier).append("|1");
        if (other != null) {
            builder.append('|').append(other.getBlockX()).append(',')
                    .append(other.getBlockY()).append(',').append(other.getBlockZ());
        }
        return builder.toString();
    }

    private Entry parse(Location self, String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|");
        boolean alive = parts.length > 1 && "1".equals(parts[1]);
        Location other = parts.length > 2 ? parseLocation(self, parts[2]) : null;
        boolean atDoor = self.getBlock().getType() == org.bukkit.Material.IRON_DOOR;
        return new Entry(parts[0], alive, atDoor ? self : other, atDoor ? other : self);
    }

    private Location parseLocation(Location self, String raw) {
        try {
            String[] coords = raw.split(",");
            if (coords.length < 3) {
                return null;
            }
            return new Location(self.getWorld(), Integer.parseInt(coords[0].trim()),
                    Integer.parseInt(coords[1].trim()), Integer.parseInt(coords[2].trim()));
        } catch (Exception e) {
            return null;
        }
    }
}
