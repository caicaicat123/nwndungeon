package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.util.List;
import java.util.Random;

/**
 * 一处「自建入口建筑」：管理员自己搭一座门楼（门 + 按钮/压力板），存成结构，需要时贴到主世界。
 *
 * <p>存两个文件（沿用老 {@link Template} 的思路，但只服务于入口 —— 副本本体走的是"复制整个世界"）：
 * <ul>
 *   <li>{@code templates/entrances/<slug>.nbt} —— 建筑本身（Paper 自带的结构 API，不需要 WorldEdit）；</li>
 *   <li>{@code templates/entrances/<slug>.yml} —— 绑定的副本名、门与触发点**相对选区最小角**的坐标、
 *       门的材质。</li>
 * </ul>
 *
 * <p>关键点：**门与触发点显式记角色**（谁站在哪一格是"门"、哪一格是"开关"），
 * 不再像老实现那样靠"这格是不是铁门"反推 —— 这是计划书 R2 要修的东西，
 * 所以你放木门、铜门、拉杆都行。
 */
public final class EntranceTemplate {

    /** 绑定的副本名（配置段名）。 */
    public final String dungeonName;
    public String slug;
    public String display = "§f入口";
    /** 相对选区最小角。 */
    public int[] doorRel;
    public int[] triggerRel;
    /** 门的材质名（贴出来之后用来核对 / 补摆）。 */
    public String doorMaterial;
    /** 门的朝向与铰链（结构粘贴实测**不带门**，所以要靠这几个字段自己补摆）。 */
    public String doorFacing;
    public String doorHinge;
    /** 选区尺寸（贴的时候用来算清理范围）。 */
    public int[] size = new int[]{0, 0, 0};

    public EntranceTemplate(String dungeonName) {
        this.dungeonName = dungeonName;
    }

    public static File folder(NWNDungeon plugin) {
        File dir = new File(plugin.getDataFolder(), "templates/entrances");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("入口模板目录建不出来：" + dir);
        }
        return dir;
    }

    public File structureFile(NWNDungeon plugin) {
        return new File(folder(plugin), slug + ".nbt");
    }

    public File configFile(NWNDungeon plugin) {
        return new File(folder(plugin), slug + ".yml");
    }

    public boolean saved(NWNDungeon plugin) {
        return configFile(plugin).isFile() && structureFile(plugin).isFile();
    }

    // ---------------------------------------------------------------- 存取

    public void save(NWNDungeon plugin, World world, Location min, Location max) throws Exception {
        StructureManager manager = Bukkit.getStructureManager();
        Structure structure = manager.createStructure();
        // 本地测试服实测：Structure#fill 的选区是**左闭右开**的 —— 传进去的 max 那一层不算。
        // 我们手里的 max 是"最后一个方块"（闭区间），所以要 +1 再传，否则贴出来的建筑
        // 会在三条 max 轴上各少一层（门正好在边上就是整扇门没了）。
        structure.fill(min, max.clone().add(1, 1, 1), true);
        manager.saveStructure(structureFile(plugin), structure);
        BlockVector vector = structure.getSize();
        size = new int[]{vector.getBlockX(), vector.getBlockY(), vector.getBlockZ()};

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("dungeon", dungeonName);
        yaml.set("display", display);
        yaml.set("slug", slug);
        yaml.set("door", doorRel == null ? null : List.of(doorRel[0], doorRel[1], doorRel[2]));
        yaml.set("trigger", triggerRel == null ? null : List.of(triggerRel[0], triggerRel[1], triggerRel[2]));
        yaml.set("door-material", doorMaterial);
        yaml.set("door-facing", doorFacing);
        yaml.set("door-hinge", doorHinge);
        yaml.set("size", List.of(size[0], size[1], size[2]));
        yaml.save(configFile(plugin));
    }

    public static EntranceTemplate load(NWNDungeon plugin, String slug, String dungeonName) {
        EntranceTemplate template = new EntranceTemplate(dungeonName);
        template.slug = slug;
        File file = template.configFile(plugin);
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        template.display = yaml.getString("display", "§f入口");
        template.doorMaterial = yaml.getString("door-material");
        template.doorFacing = yaml.getString("door-facing");
        template.doorHinge = yaml.getString("door-hinge");
        template.doorRel = readPos(yaml.get("door"));
        template.triggerRel = readPos(yaml.get("trigger"));
        template.size = readPos(yaml.get("size"));
        if (template.size == null) {
            template.size = new int[]{0, 0, 0};
        }
        return template;
    }

    public static void delete(NWNDungeon plugin, String slug) {
        new File(folder(plugin), slug + ".nbt").delete();
        new File(folder(plugin), slug + ".yml").delete();
    }

    public static List<String> listSlugs(NWNDungeon plugin) {
        File[] files = folder(plugin).listFiles((dir, name) -> name.endsWith(".yml"));
        List<String> out = new java.util.ArrayList<>();
        if (files != null) {
            for (File file : files) {
                out.add(file.getName().substring(0, file.getName().length() - 4));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    // ---------------------------------------------------------------- 贴出去

    /** 贴出来的结果：门与触发点在主世界里的实际位置。 */
    public record Placed(Location door, Location trigger, int[] size) {
    }

    /**
     * 把这座入口贴到指定位置（选区最小角对齐到 {@code at}）。
     *
     * @return 门/触发点的实际坐标；结构文件读不出来时返回 null
     */
    public Placed paste(NWNDungeon plugin, Location at) {
        try {
            StructureManager manager = Bukkit.getStructureManager();
            Structure structure = manager.loadStructure(structureFile(plugin));
            structure.place(at, true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
            BlockVector vector = structure.getSize();
            size = new int[]{vector.getBlockX(), vector.getBlockY(), vector.getBlockZ()};
            Location door = doorRel == null ? null : at.clone().add(doorRel[0], doorRel[1], doorRel[2]);
            Location trigger = triggerRel == null ? null
                    : at.clone().add(triggerRel[0], triggerRel[1], triggerRel[2]);
            return new Placed(door, trigger, size);
        } catch (Exception e) {
            plugin.getLogger().warning("贴入口结构失败（" + slug + "）：" + e);
            return null;
        }
    }

    /** 结构要在"以某点为中心"的方式贴时，先把最小角算出来。 */
    public Location alignTo(Location center) {
        int halfX = Math.max(0, size[0] / 2);
        int halfZ = Math.max(0, size[2] / 2);
        return new Location(center.getWorld(),
                center.getBlockX() - halfX, center.getBlockY(), center.getBlockZ() - halfZ);
    }

    // ---------------------------------------------------------------- 自动找选区

    /**
     * 在入口搭建世界里**自动框出建筑范围**：所有已加载区块里非空气方块的包围盒。
     *
     * <p>为什么要自动：计划书要求"点图标就能存"，不想让管理员先框选再保存。
     * 入口世界是虚空世界、只有管理员搭的那点东西，所以在已加载区块里扫一遍就能拿到准确范围。
     * Y 只在出生点上下 {@code yRadius} 格内扫（建筑本来就该建在那儿），免得扫满 384 格高。
     *
     * @return {@code {minX,minY,minZ,maxX,maxY,maxZ}}；什么都没找到返回 null
     */
    public static int[] boundsOf(World world, Location center, int yRadius, int maxSpan) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int lowY = Math.max(world.getMinHeight(), center.getBlockY() - yRadius);
        int highY = Math.min(world.getMaxHeight() - 1, center.getBlockY() + yRadius);
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            int baseX = chunk.getX() << 4;
            int baseZ = chunk.getZ() << 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = lowY; y <= highY; y++) {
                        if (snapshot.getBlockType(x, y, z).isAir()) {
                            continue;
                        }
                        int worldX = baseX + x;
                        int worldZ = baseZ + z;
                        if (worldX < minX) {
                            minX = worldX;
                        }
                        if (worldX > maxX) {
                            maxX = worldX;
                        }
                        if (worldZ < minZ) {
                            minZ = worldZ;
                        }
                        if (worldZ > maxZ) {
                            maxZ = worldZ;
                        }
                        if (y < minY) {
                            minY = y;
                        }
                        if (y > maxY) {
                            maxY = y;
                        }
                    }
                }
            }
        }
        if (maxX < minX) {
            return null;
        }
        if (maxX - minX + 1 > maxSpan || maxY - minY + 1 > maxSpan || maxZ - minZ + 1 > maxSpan) {
            return new int[]{minX, minY, minZ, maxX, maxY, maxZ, 1};   // 最后一位 = 太大
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ, 0};
    }

    /**
     * 自己把门摆到指定位置（贴结构时用它兜底）。
     *
     * <p>为什么要这一步：本地测试服实测这一版 Paper 的 {@code Structure#fill/place}
     * **不会把门带过去**（源世界里门是好的，贴出来那一格是空气）——
     * 而入口的全部意义就是一扇能按的门。所以贴完结构后，插件按记下来的
     * 材质 / 朝向 / 铰链**把门自己摆回去**。
     */
    public static boolean placeDoor(World world, Location lower, String materialName,
                                    String facingName, String hingeName) {
        if (world == null || lower == null) {
            return false;
        }
        Material material = materialName == null ? Material.OAK_DOOR : Material.matchMaterial(materialName);
        if (material == null || !Entrances.isDoorMaterial(material)) {
            return false;
        }
        org.bukkit.block.BlockFace facing;
        try {
            facing = facingName == null ? org.bukkit.block.BlockFace.NORTH
                    : org.bukkit.block.BlockFace.valueOf(facingName);
        } catch (Exception e) {
            facing = org.bukkit.block.BlockFace.NORTH;
        }
        Door.Hinge hinge;
        try {
            hinge = hingeName == null ? Door.Hinge.LEFT : Door.Hinge.valueOf(hingeName);
        } catch (Exception e) {
            hinge = Door.Hinge.LEFT;
        }
        for (int i = 0; i <= 1; i++) {
            Block block = world.getBlockAt(lower.getBlockX(), lower.getBlockY() + i, lower.getBlockZ());
            block.setType(material, false);
            if (block.getBlockData() instanceof Door door) {
                door.setFacing(facing);
                door.setHinge(hinge);
                door.setHalf(i == 0 ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
                door.setOpen(false);
                block.setBlockData(door, false);
            }
        }
        return world.getBlockAt(lower.getBlockX(), lower.getBlockY(), lower.getBlockZ())
                .getBlockData() instanceof Door;
    }

    /** 门方块取下半格（存坐标时用）。 */
    public static Location doorLower(Block block) {
        Location location = block.getLocation();
        if (block.getBlockData() instanceof Door door && door.getHalf() == Bisected.Half.TOP) {
            location = location.clone().subtract(0, 1, 0);
        }
        return location;
    }

    public static Material materialAt(Location location) {
        return location == null || location.getWorld() == null ? null : location.getBlock().getType();
    }

    private static int[] readPos(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 3) {
            return null;
        }
        try {
            return new int[]{((Number) list.get(0)).intValue(),
                    ((Number) list.get(1)).intValue(),
                    ((Number) list.get(2)).intValue()};
        } catch (Exception e) {
            return null;
        }
    }
}
