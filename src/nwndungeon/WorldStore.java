package nwndungeon;

import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 世界文件夹与世界的生命周期：复制、创建、卸载、删除、启动清扫。
 *
 * <p><b>M0 实测定论</b>（见 {@code internal/v1.5.0-M0-结论.md}）：这个版本的存档把**所有维度装在一个
 * 存档里** —— {@code world/level.dat} 只存全局信息、**不登记维度**，每个维度自带
 * {@code data/minecraft/world_gen_settings.dat}，躺在 {@code world/dimensions/minecraft/<世界名>/} 下。
 * 所以：
 * <ul>
 *   <li>"复制一份世界" = 复制**一个维度文件夹**（{@code paper-world.yml} + {@code data/} +
 *       {@code region/} + {@code entities/} + {@code poi/}），**不用碰 level.dat**；</li>
 *   <li>文件夹放进 {@code dimensions/minecraft/} 就会被当成维度加载 → 这正是"预置模板文件夹 + createWorld"；</li>
 *   <li>维度文件夹里**没有** {@code session.lock} / {@code playerdata} / {@code advancements} / {@code stats}
 *       （那些全在存档根），所以不需要排除它们。</li>
 * </ul>
 */
public final class WorldStore {

    /** 复制时跳过的文件：运行时票据（每局不同）+ 系统垃圾。 */
    private static final List<String> COPY_SKIP = List.of(
            "session.lock", "chunk_tickets.dat", "metadata.dat");

    private final NWNDungeon plugin;

    public WorldStore(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 路径

    /**
     * 维度文件夹所在目录。
     *
     * <p>优先级（本地测试服实测过一次"新服务器上判错"的坑，所以这里不只看目录存不存在）：
     * <ol>
     *   <li>任何**已加载**世界的文件夹落在 {@code <X>/dimensions/minecraft/<名字>} 下 → 用那个 {@code <X>/dimensions/minecraft}
     *       （运行期最可靠：插件自己的世界就在那儿）；</li>
     *   <li>主世界文件夹下 {@code dimensions/minecraft} 存在 → 用它；</li>
     *   <li>**目录还没建**（全新服务器、还没生成过任何自建世界）：只要主世界文件夹里**没有 {@code region/}**
     *       就说明是新布局（新布局里主世界的区块在 {@code dimensions/minecraft/overworld/region}，
     *       老布局才有 {@code <世界>/region}）→ 仍然按新布局算；</li>
     *   <li>否则退回服务器根目录（老布局：每个世界一个文件夹）。</li>
     * </ol>
     */
    public File dimensionsRoot() {
        for (World world : Bukkit.getWorlds()) {
            File folder = world.getWorldFolder();
            if (folder == null) {
                continue;
            }
            File parent = folder.getParentFile();
            if (parent != null && "minecraft".equals(parent.getName())) {
                File grand = parent.getParentFile();
                if (grand != null && "dimensions".equals(grand.getName())) {
                    return parent;
                }
            }
        }
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            File main = worlds.get(0).getWorldFolder();
            File dimensions = new File(main, "dimensions/minecraft");
            if (dimensions.isDirectory() || !new File(main, "region").isDirectory()) {
                return dimensions;
            }
        }
        return Bukkit.getWorldContainer();
    }

    public File folderOf(String worldName) {
        return new File(dimensionsRoot(), worldName);
    }

    /** 模板存档目录：{@code plugins/NWNDungeon/templates/worlds/}（不是世界，只是文件夹）。 */
    public File templatesRoot() {
        File folder = new File(plugin.getDataFolder(), "templates/worlds");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().warning("模板目录建不出来：" + folder);
        }
        return folder;
    }

    public File templateFolder(String slug) {
        return new File(templatesRoot(), slug);
    }

    /** 一个文件夹看起来是不是"存过的世界"（有 region/ 或 data/ 才算）。 */
    public static boolean looksLikeWorld(File folder) {
        return folder != null && folder.isDirectory()
                && (new File(folder, "region").isDirectory() || new File(folder, "data").isDirectory());
    }

    // ---------------------------------------------------------------- 体积

    public static long sizeOf(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return 0L;
        }
        long total = 0L;
        File[] children = folder.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            total += child.isDirectory() ? sizeOf(child) : child.length();
        }
        return total;
    }

    public static int filesIn(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return 0;
        }
        int total = 0;
        File[] children = folder.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            total += child.isDirectory() ? filesIn(child) : 1;
        }
        return total;
    }

    /** region 文件个数（给管理员看"这个模板铺了多少区块带"）。 */
    public static int regionCount(File folder) {
        File region = new File(folder, "region");
        File[] files = region.listFiles((dir, name) -> name.endsWith(".mca"));
        return files == null ? 0 : files.length;
    }

    public static String describeSize(File folder) {
        long bytes = sizeOf(folder);
        String size = bytes >= 1024 * 1024
                ? String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
                : String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return size + " / " + filesIn(folder) + " 个文件 / " + regionCount(folder) + " 个 region";
    }

    // ---------------------------------------------------------------- 文件夹复制 / 删除

    /** 递归复制文件夹（纯文件 IO，**可以异步调用**，不要在里面碰 Bukkit API）。 */
    public void copyFolder(File from, File to) throws IOException {
        if (!from.isDirectory()) {
            throw new IOException("源文件夹不存在：" + from);
        }
        if (to.exists()) {
            deleteFolder(to);
        }
        final Path source = from.toPath();
        final Path target = to.toPath();
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (COPY_SKIP.contains(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;   // 运行时票据之类的不带过去
                }
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 递归删除文件夹。删不掉不算失败（返回 false），调用方写进待删清单下次再试 ——
     * 服务器是 Linux，正常情况下 unload 之后就能删干净。
     */
    public static boolean deleteFolder(File folder) {
        if (folder == null || !folder.exists()) {
            return true;
        }
        File[] children = folder.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteFolder(child);
            }
        }
        return folder.delete();
    }

    // ---------------------------------------------------------------- 建 / 卸

    /**
     * 建（或加载）一个世界。
     *
     * <p>注意：**如果磁盘上已经有这个世界的文件夹，这一步就是"加载它"**，
     * 这正是"预置模板文件夹 → 开一局"要用的路径。
     *
     * @param generator {@code void}（空世界，默认）/ {@code flat}（超平坦）/ {@code normal}（普通地形）
     */
    public World create(String worldName, String generator, boolean autoSave, boolean instance) {
        WorldCreator creator = new WorldCreator(worldName);
        applyGenerator(creator, generator);
        creator.generateStructures(false);
        // 每个实例/编辑世界都不该把出生点区块钉在内存里
        creator.keepSpawnLoaded(TriState.FALSE);
        World world = Bukkit.createWorld(creator);
        if (world == null) {
            return null;
        }
        world.setAutoSave(autoSave);
        // 虚空世界的默认出生点在**世界底部**（minHeight+2，实测 -62）。这有两个坏处：
        //  ① 管理员一进编辑世界就像掉在地底，很不舒服；
        //  ② 万一这个临时世界被删掉（重启时自动回收 / 保存 / 放弃）而人还在里面，
        //     下次登录会被"按原坐标"落到主世界 —— 也就是 y=-62 的地底（真踩过一次）。
        // 所以新建的虚空世界统一把出生点抬到 y=64 的地面上。
        // 注意：只有**新建**时才动；从模板复制出来的实例世界必须原样保留模板的出生点。
        if (!instance && !"normal".equalsIgnoreCase(generator == null ? "void" : generator)) {
            Location spawn = world.getSpawnLocation();
            if (spawn.getBlockY() < 0) {
                world.setSpawnLocation(spawn.getBlockX(), 64, spawn.getBlockZ());
            }
        }
        applyRules(world, instance);
        return world;
    }

    private void applyGenerator(WorldCreator creator, String generator) {
        String mode = generator == null ? "void" : generator.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "normal" -> creator.type(WorldType.NORMAL);
            case "flat" -> creator.type(WorldType.FLAT);
            default -> {
                creator.type(WorldType.FLAT);
                creator.generatorSettings(
                        "{\"layers\":[],\"biome\":\"minecraft:plains\",\"structure_overrides\":[]}");
            }
        }
    }

    /** 世界规则：编辑世界与实例世界都要的公共部分。 */
    public void applyRules(World world, boolean instance) {
        setRule(world, GameRule.DO_DAYLIGHT_CYCLE, false);
        setRule(world, GameRule.DO_WEATHER_CYCLE, false);
        setRule(world, GameRule.DO_FIRE_TICK, false);
        setRule(world, GameRule.MOB_GRIEFING, false);
        setRule(world, GameRule.ANNOUNCE_ADVANCEMENTS, false);
        if (instance) {
            setRule(world, GameRule.DO_MOB_SPAWNING, false);
            setRule(world, GameRule.KEEP_INVENTORY, false);
            world.setTime(6000L);
            world.setStorm(false);
            world.setThundering(false);
        } else {
            setRule(world, GameRule.KEEP_INVENTORY, true);   // 编辑时别掉东西
        }
    }

    private static void setRule(World world, GameRule<Boolean> rule, boolean value) {
        try {
            world.setGameRule(rule, value);
        } catch (Throwable ignored) {
            // 规则名在不同版本可能有差异，设不上就算了
        }
    }

    /** 卸载一个世界（保存与否由调用方决定）。 */
    public boolean unload(World world, boolean save) {
        if (world == null) {
            return true;
        }
        return Bukkit.unloadWorld(world, save);
    }

    // ---------------------------------------------------------------- 启动清扫

    /**
     * 清扫插件自己的临时世界残留（崩溃/断电留下的）。
     *
     * <p><b>必须做</b>：这个版本服务器启动时会**自动加载** {@code dimensions/minecraft/} 下的所有维度，
     * 残留的 {@code nwndinst_*} / {@code nwndtpl_*} 会被一起加载，白占内存。
     *
     * <p>两类残留处理不一样：
     * <ul>
     *   <li>{@code nwndinst_*}（每局的临时世界）—— 直接删；</li>
     *   <li>{@code nwndtpl_*}（正在编辑的模板）—— **不删**，搬进
     *       {@code templates/_recovered/<名字>-<时间>/}：里面可能是管理员搭了一半没保存的活，
     *       留一份让他自己决定（符合项目"留退路"的一贯做法）。</li>
     * </ul>
     */
    public List<String> cleanupLeftovers() {
        List<String> cleaned = new ArrayList<>();
        File root = dimensionsRoot();
        File[] children = root.listFiles();
        if (children == null) {
            return cleaned;
        }
        File recovered = new File(templatesRoot().getParentFile(), "_recovered");
        for (File child : children) {
            String name = child.getName();
            if (!child.isDirectory() || !Names.isOwnWorld(name)) {
                continue;
            }
            World loaded = Bukkit.getWorld(name);
            if (loaded != null) {
                unload(loaded, false);
            }
            if (name.startsWith(Names.TEMPLATE_PREFIX) || name.startsWith(Names.ENTRANCE_PREFIX)) {
                if (!recovered.isDirectory()) {
                    recovered.mkdirs();
                }
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                File target = new File(recovered, name + "-" + stamp);
                if (child.renameTo(target)) {
                    plugin.getLogger().warning("上次的编辑世界没保存就结束了，已挪到 templates/_recovered/"
                            + target.getName());
                    cleaned.add(name);
                } else {
                    plugin.addPendingDelete(child);
                }
                continue;
            }
            if (deleteFolder(child)) {
                cleaned.add(name);
                plugin.getLogger().info("清掉了上次没收拾干净的临时世界：" + name);
            } else {
                plugin.addPendingDelete(child);
                plugin.getLogger().warning("临时世界删不掉，已记进待删清单：" + name);
            }
        }
        return cleaned;
    }
}
