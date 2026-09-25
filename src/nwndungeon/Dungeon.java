package nwndungeon;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一次副本实例的运行时结构：房间、波次、怪物、门与箱子位置。 */
public final class Dungeon {

    /** 模板副本用的刷怪点：世界坐标 + 怪物模板名（null = 用原版兜底怪）。 */
    public record TemplateSpawn(Location point, String mob) {
    }

    public static final class Room {
        public final int index;
        public int displayIndex;          // 给玩家看的房间号：只数"有怪的房间"，第一间有怪的 = 1
        public final List<UUID> mobs = new ArrayList<>();          // 当前这一波的怪
        public List<List<EntityType>> waves = new ArrayList<>();    // 每波要刷的怪（按顺序）
        public List<List<TemplateSpawn>> plan = new ArrayList<>();  // 模板副本：按标记点刷怪
        public int waveIndex;             // 当前第几波（0 基）
        public boolean wavePending;       // 正在等下一波刷新（3 秒）
        public UUID bossId;
        public boolean bossRoom;
        public boolean cleared;           // 所有波都清完了
        public Location origin;           // 房间原点（rx, oy, oz）：运行时补刷波次用
        public Location doorLower;        // 通往下一间的铁门（最后一间为 null）
        public Location plateSpot;        // 清场后压力板出现的位置（门前地板上）
        public Location chestSpot;        // 补给箱位置
        public Location exitDoor;         // 最后一间的木门（离开用）
        public Location exitPlate;        // 模板副本：通关后出现的离开压力板
        public Location center;           // 房间中心（兜底检查怪物是否还在时用）
        public int kills;                 // 这间一共打死多少只（结算用）
        public int initialMobs;           // 本波刷新时的数量（血条进度用）
        public double initialBossHealth = 60;

        public Room(int index) {
            this.index = index;
        }
    }

    public final List<Room> rooms = new ArrayList<>();
    public final List<Location> checkpoints = new ArrayList<>();
    public Location spawn;
}
