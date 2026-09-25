package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 副本编辑器：在独立的编辑世界（nwndungeon_edit）里手搭副本 → 用命令打标记 → 存成模板。
 *
 * 每个模板占一块 256×256 的空地（按名字 hash 定位）；再次编辑会把已保存的结构贴回工作区，
 * 标记也一并还原。保存时把选区（pos1/pos2）里的方块与实体抓成 templates/&lt;名字&gt;.nbt，
 * 参数与标记写进 templates/&lt;名字&gt;.yml —— 用的是 Paper 自带的结构 API，不需要 WorldEdit。
 */
public final class TemplateEditor {

    public static final String EDIT_WORLD = "nwndungeon_edit";
    private static final int PLOT_SIZE = 256;
    private static final int PLOT_GRID = 8;
    private static final int MAX_SPAN = 256;

    /** 一个刷怪点标记。 */
    public record Mark(Location point, String mob) {
    }

    /** 一间的标记（门 / 压力板 / 补给箱 / 各波刷怪点）。 */
    public static final class RoomMark {
        public Location door;
        public Location plate;
        public Location chest;
        public final Map<Integer, List<Mark>> waves = new LinkedHashMap<>();
    }

    /** 一次编辑会话。 */
    public static final class Session {
        public final UUID player;
        public final String name;
        public final Location plot;
        public final Location backLocation;
        public final GameMode backMode;
        public Location pos1;
        public Location pos2;
        public Location spawn;
        public final List<Location> checkpoints = new ArrayList<>();
        public Location exitPlate;
        public int room = 1;
        public int wave = 1;
        public String mob;
        public String display = "§f副本";
        public int timeLimit = 20;
        public final Map<Integer, RoomMark> rooms = new LinkedHashMap<>();

        Session(UUID player, String name, Location plot, Location backLocation, GameMode backMode) {
            this.player = player;
            this.name = name;
            this.plot = plot;
            this.backLocation = backLocation;
            this.backMode = backMode;
        }

        public RoomMark currentRoom() {
            return rooms.computeIfAbsent(room, key -> new RoomMark());
        }
    }

    private final NWNDungeon plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private World world;

    public TemplateEditor(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    public File folder() {
        File folder = new File(plugin.getDataFolder(), "templates");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("模板目录建不出来：" + folder);
        }
        return folder;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        File[] files = folder().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                out.add(file.getName().substring(0, file.getName().length() - 4));
            }
        }
        Collections.sort(out);
        return out;
    }

    public boolean exists(String name) {
        return new File(folder(), name + ".yml").exists();
    }

    public Template template(String name) {
        return exists(name) ? Template.load(folder(), name) : null;
    }

    public Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean delete(String name) {
        boolean ok = new File(folder(), name + ".yml").delete();
        new File(folder(), name + ".nbt").delete();
        return ok;
    }

    // ---------------------------------------------------------------- 编辑世界

    public World world() {
        if (world != null) {
            return world;
        }
        World existing = Bukkit.getWorld(EDIT_WORLD);
        if (existing == null) {
            existing = new WorldCreator(EDIT_WORLD)
                    .type(WorldType.FLAT)
                    .generatorSettings("{\"layers\":[],\"biome\":\"minecraft:plains\",\"structure_overrides\":[]}")
                    .generateStructures(false)
                    .createWorld();
        }
        if (existing == null) {
            return null;
        }
        existing.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        existing.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        existing.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        existing.setGameRule(GameRule.MOB_GRIEFING, false);
        existing.setGameRule(GameRule.KEEP_INVENTORY, true);
        existing.setAutoSave(true);
        world = existing;
        return world;
    }

    public Location plotOf(String name) {
        int index = Math.floorMod(name.hashCode(), PLOT_GRID * PLOT_GRID);
        return new Location(world(), (index % PLOT_GRID) * PLOT_SIZE, 64, (index / PLOT_GRID) * PLOT_SIZE);
    }

    /** 打开（或新建）工作区：已有模板会贴回工作区继续改。 */
    public Session open(Player player, String name) {
        World editWorld = world();
        if (editWorld == null) {
            player.sendMessage("§c编辑世界创建失败，看控制台日志。");
            return null;
        }
        Location plot = plotOf(name);
        Session session = new Session(player.getUniqueId(), name, plot, player.getLocation(), player.getGameMode());
        if (exists(name)) {
            Template existing = template(name);
            session.display = existing.display;
            session.timeLimit = existing.timeLimitMinutes;
            try {
                existing.paste(folder(), plot);
                session.spawn = existing.at(editWorld, plot, existing.spawn);
                for (Template.Point point : existing.checkpoints) {
                    session.checkpoints.add(existing.at(editWorld, plot, point));
                }
                if (existing.exitPlate != null) {
                    session.exitPlate = existing.at(editWorld, plot, existing.exitPlate);
                }
                int roomIndex = 1;
                for (Template.Room room : existing.rooms) {
                    RoomMark mark = new RoomMark();
                    if (room.door != null) {
                        mark.door = existing.at(editWorld, plot, room.door);
                    }
                    if (room.plate != null) {
                        mark.plate = existing.at(editWorld, plot, room.plate);
                    }
                    if (room.chest != null) {
                        mark.chest = existing.at(editWorld, plot, room.chest);
                    }
                    int waveIndex = 1;
                    for (List<Template.Spawn> wave : room.waves) {
                        List<Mark> marks = new ArrayList<>();
                        for (Template.Spawn spawn : wave) {
                            marks.add(new Mark(existing.at(editWorld, plot, spawn.point()), spawn.mob()));
                        }
                        mark.waves.put(waveIndex++, marks);
                    }
                    session.rooms.put(roomIndex++, mark);
                }
                Structure structure = Bukkit.getStructureManager()
                        .loadStructure(existing.structureFile(folder()));
                BlockVector size = structure.getSize();
                session.pos1 = plot.clone();
                session.pos2 = plot.clone().add(size.getBlockX() - 1, size.getBlockY() - 1, size.getBlockZ() - 1);
                player.sendMessage("§a已把模板 §f" + name + "§a 贴回工作区（选区自动框好），改完 /dungeon edit save 覆盖。");
            } catch (Exception e) {
                plugin.getLogger().warning("贴回模板 " + name + " 失败：" + e.getMessage());
                player.sendMessage("§c旧结构贴回失败（" + e.getMessage() + "），这次当新建处理。");
            }
        } else {
            player.sendMessage("§a已新建工作区 §f" + name + "§a：在这块空地上搭副本，搭完 /dungeon edit save。");
        }
        sessions.put(player.getUniqueId(), session);
        player.setGameMode(GameMode.CREATIVE);
        player.teleport(session.spawn != null ? session.spawn : plot.clone().add(8.5, 1, 8.5));
        return session;
    }

    /** 退出编辑：送回原来的位置与游戏模式。 */
    public void close(Player player, Session session) {
        sessions.remove(player.getUniqueId());
        player.setGameMode(session.backMode);
        player.teleport(session.backLocation);
        player.sendMessage("§7已退出编辑器。");
    }

    // ---------------------------------------------------------------- 保存

    public boolean save(Player player, Session session) {
        if (session.pos1 == null || session.pos2 == null) {
            player.sendMessage("§c先选两个对角：站到角上执行 §f/dungeon edit pos1 §c和 §fpos2§c。");
            return false;
        }
        int minX = Math.min(session.pos1.getBlockX(), session.pos2.getBlockX());
        int minY = Math.min(session.pos1.getBlockY(), session.pos2.getBlockY());
        int minZ = Math.min(session.pos1.getBlockZ(), session.pos2.getBlockZ());
        int maxX = Math.max(session.pos1.getBlockX(), session.pos2.getBlockX());
        int maxY = Math.max(session.pos1.getBlockY(), session.pos2.getBlockY());
        int maxZ = Math.max(session.pos1.getBlockZ(), session.pos2.getBlockZ());
        if (maxX - minX > MAX_SPAN || maxY - minY > MAX_SPAN || maxZ - minZ > MAX_SPAN) {
            player.sendMessage("§c选区太大了（上限 " + MAX_SPAN + " 格），缩小一点再存。");
            return false;
        }
        Location min = new Location(world(), minX, minY, minZ);
        try {
            StructureManager manager = Bukkit.getStructureManager();
            Structure structure = manager.createStructure();
            structure.fill(new Location(world(), minX, minY, minZ), new Location(world(), maxX, maxY, maxZ), true);
            manager.saveStructure(new File(folder(), session.name + ".nbt"), structure);

            Template template = new Template(session.name);
            template.display = session.display;
            template.timeLimitMinutes = session.timeLimit;
            if (session.spawn != null) {
                template.spawn = rel(min, session.spawn);
            }
            for (Location point : session.checkpoints) {
                template.checkpoints.add(rel(min, point));
            }
            if (session.exitPlate != null) {
                template.exitPlate = rel(min, session.exitPlate);
            }
            List<Integer> roomKeys = new ArrayList<>(session.rooms.keySet());
            Collections.sort(roomKeys);
            for (int key : roomKeys) {
                RoomMark mark = session.rooms.get(key);
                Template.Room room = new Template.Room();
                if (mark.door != null) {
                    room.door = rel(min, mark.door);
                }
                if (mark.plate != null) {
                    room.plate = rel(min, mark.plate);
                }
                if (mark.chest != null) {
                    room.chest = rel(min, mark.chest);
                }
                List<Integer> waveKeys = new ArrayList<>(mark.waves.keySet());
                Collections.sort(waveKeys);
                for (int waveKey : waveKeys) {
                    List<Template.Spawn> wave = new ArrayList<>();
                    for (Mark entry : mark.waves.get(waveKey)) {
                        wave.add(new Template.Spawn(rel(min, entry.point()), entry.mob()));
                    }
                    if (!wave.isEmpty()) {
                        room.waves.add(wave);
                    }
                }
                template.rooms.add(room);
            }
            if (!template.rooms.isEmpty()) {
                template.rooms.get(template.rooms.size() - 1).boss = true;   // 最后一间自动当首领房
            }
            template.save(folder());
            player.sendMessage("§a已保存模板 §f" + session.name + "§a：" + template.rooms.size()
                    + " 间 / " + template.totalWaves() + " 波 / " + template.checkpoints.size() + " 个检查点。");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("保存模板 " + session.name + " 失败：" + e);
            player.sendMessage("§c保存失败：" + e.getMessage());
            return false;
        }
    }

    private Template.Point rel(Location min, Location point) {
        return new Template.Point(point.getX() - min.getBlockX(),
                point.getY() - min.getBlockY(),
                point.getZ() - min.getBlockZ());
    }
}
