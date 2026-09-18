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
 * 记录格式：<难度>|1 表示有效，<难度>|0 表示这扇门已被破坏、永久失效。
 */
public final class Entrances {

    public record Entry(String tier, boolean alive, Location door) {
    }

    private final NWNDungeon plugin;

    public Entrances(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey key(Location door) {
        return new NamespacedKey(plugin,
                "entrance_" + door.getBlockX() + "_" + door.getBlockY() + "_" + door.getBlockZ());
    }

    public void register(Location door, String tier) {
        PersistentDataContainer pdc = door.getChunk().getPersistentDataContainer();
        pdc.set(key(door), PersistentDataType.STRING, tier + "|1");
    }

    public Entry get(Location door) {
        if (!door.isWorldLoaded()) {
            return null;
        }
        PersistentDataContainer pdc = door.getChunk().getPersistentDataContainer();
        String raw = pdc.get(key(door), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|");
        boolean alive = parts.length > 1 && "1".equals(parts[1]);
        return new Entry(parts[0], alive, door);
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
        return true;
    }
}
