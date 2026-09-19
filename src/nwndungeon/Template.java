package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 一个"模板副本"。
 *
 * 结构（方块 + 实体）存在 {@code templates/<名字>.nbt}（Paper 自带的结构格式，不需要 WorldEdit），
 * 参数与标记存在 {@code templates/<名字>.yml}，坐标一律**相对选区最小角**。
 */
public final class Template {

    /** 相对坐标（相对选区最小角）。 */
    public record Point(double x, double y, double z) {
    }

    /** 刷怪点：位置 + 怪物模板名（null = 用原版兜底怪）。 */
    public record Spawn(Point point, String mob) {
    }

    /** 一间：通往下一间的门 + 门前压力板 + 补给箱 + 若干波刷怪点。 */
    public static final class Room {
        public Point door;
        public Point plate;
        public Point chest;
        public boolean boss;
        public final List<List<Spawn>> waves = new ArrayList<>();
    }

    public final String name;
    public String display = "§f副本";
    public int timeLimitMinutes = 20;
    public Point spawn = new Point(0.5, 1, 0.5);
    public final List<Point> checkpoints = new ArrayList<>();
    public Point exitPlate;
    public final List<Room> rooms = new ArrayList<>();

    public Template(String name) {
        this.name = name;
    }

    public Location at(World world, Location origin, Point point) {
        return new Location(world,
                origin.getBlockX() + point.x(),
                origin.getBlockY() + point.y(),
                origin.getBlockZ() + point.z());
    }

    /** 总波数（用于提示与日志）。 */
    public int totalWaves() {
        int total = 0;
        for (Room room : rooms) {
            total += room.waves.size();
        }
        return total;
    }

    // ---------------------------------------------------------------- 存取

    public File structureFile(File folder) {
        return new File(folder, name + ".nbt");
    }

    public File configFile(File folder) {
        return new File(folder, name + ".yml");
    }

    /** 把 .nbt 结构贴到指定位置（含实体），返回结构尺寸（便于之后清理）。 */
    public org.bukkit.util.BlockVector paste(File folder, Location location) throws IOException {
        StructureManager manager = Bukkit.getStructureManager();
        Structure structure = manager.loadStructure(structureFile(folder));
        structure.place(location, true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
        return structure.getSize();
    }

    public void save(File folder) throws IOException {
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("建目录失败：" + folder);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", name);
        yaml.set("display", display);
        yaml.set("time-limit-minutes", timeLimitMinutes);
        yaml.set("spawn", listOf(spawn));
        List<List<Double>> checkpointOut = new ArrayList<>();
        for (Point point : checkpoints) {
            checkpointOut.add(listOf(point));
        }
        yaml.set("checkpoints", checkpointOut);
        if (exitPlate != null) {
            yaml.set("exit-plate", listOf(exitPlate));
        }
        List<Map<String, Object>> roomsOut = new ArrayList<>();
        for (Room room : rooms) {
            Map<String, Object> roomMap = new LinkedHashMap<>();
            if (room.door != null) {
                roomMap.put("door", listOf(room.door));
            }
            if (room.plate != null) {
                roomMap.put("plate", listOf(room.plate));
            }
            if (room.chest != null) {
                roomMap.put("chest", listOf(room.chest));
            }
            if (room.boss) {
                roomMap.put("boss", true);
            }
            List<List<Map<String, Object>>> wavesOut = new ArrayList<>();
            for (List<Spawn> wave : room.waves) {
                List<Map<String, Object>> waveOut = new ArrayList<>();
                for (Spawn spawn : wave) {
                    Map<String, Object> spawnMap = new LinkedHashMap<>();
                    spawnMap.put("point", listOf(spawn.point()));
                    if (spawn.mob() != null) {
                        spawnMap.put("mob", spawn.mob());
                    }
                    waveOut.add(spawnMap);
                }
                wavesOut.add(waveOut);
            }
            roomMap.put("waves", wavesOut);
            roomsOut.add(roomMap);
        }
        yaml.set("rooms", roomsOut);
        yaml.save(configFile(folder));
    }

    public static Template load(File folder, String name) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(folder, name + ".yml"));
        Template template = new Template(name);
        template.display = yaml.getString("display", "§f副本");
        template.timeLimitMinutes = Math.max(1, yaml.getInt("time-limit-minutes", 20));
        Point spawn = pointOf(yaml.get("spawn"));
        if (spawn != null) {
            template.spawn = spawn;
        }
        for (Object raw : yaml.getList("checkpoints", List.of())) {
            Point point = pointOf(raw);
            if (point != null) {
                template.checkpoints.add(point);
            }
        }
        template.exitPlate = pointOf(yaml.get("exit-plate"));
        for (Map<?, ?> rawRoom : yaml.getMapList("rooms")) {
            Room room = new Room();
            room.door = pointOf(rawRoom.get("door"));
            room.plate = pointOf(rawRoom.get("plate"));
            room.chest = pointOf(rawRoom.get("chest"));
            room.boss = Boolean.TRUE.equals(rawRoom.get("boss"));
            if (rawRoom.get("waves") instanceof List<?> waves) {
                for (Object rawWave : waves) {
                    List<Spawn> wave = new ArrayList<>();
                    if (rawWave instanceof List<?> spawns) {
                        for (Object rawSpawn : spawns) {
                            if (rawSpawn instanceof Map<?, ?> spawnMap) {
                                Point point = pointOf(spawnMap.get("point"));
                                if (point != null) {
                                    Object mob = spawnMap.get("mob");
                                    wave.add(new Spawn(point, mob == null ? null : String.valueOf(mob)));
                                }
                            }
                        }
                    }
                    if (!wave.isEmpty()) {
                        room.waves.add(wave);
                    }
                }
            }
            template.rooms.add(room);
        }
        return template;
    }

    private static Point pointOf(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 3) {
            return null;
        }
        try {
            return new Point(((Number) list.get(0)).doubleValue(),
                    ((Number) list.get(1)).doubleValue(),
                    ((Number) list.get(2)).doubleValue());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Double> listOf(Point point) {
        return List.of(round(point.x()), round(point.y()), round(point.z()));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
