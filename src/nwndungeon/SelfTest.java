package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 自检（{@code /nwmdungeon doctor}）：把 v1.5.0 整套架构的三个未知项一次跑完，**不需要真人在游戏里**。
 *
 * <p>这三条正是 M0 里"只能等真机才能验"的部分（见 {@code internal/v1.5.0-M0-结论.md} §六）：
 * <ol>
 *   <li><b>预置的维度文件夹能不能被 {@code createWorld} 读进来</b> —— 整个"复制世界"路线的核心假设。
 *       做法：建一个虚空世界 → 在已知坐标放一块金块 → 保存 → 卸载 → 复制成新世界名 → 建世界 →
 *       看那块金块在不在。</li>
 *   <li>卸载之后文件夹能不能立刻删掉。</li>
 *   <li>维度文件夹路径认得对不对（{@code <主世界>/dimensions/minecraft/<名字>}）。</li>
 * </ol>
 *
 * <p>全过程用的是临时名字（{@code zzselftest}），跑完会把两个世界都清掉，不留残留、不碰真实副本。
 */
public final class SelfTest {

    private static final String SLUG = "zzselftest";
    private static final String TEMPLATE_WORLD = Names.TEMPLATE_PREFIX + SLUG;
    private static final String INSTANCE_WORLD = Names.INSTANCE_PREFIX + SLUG + "_1";
    /** 标记方块相对世界出生点的水平偏移。 */
    private static final int MARK = 8;

    private final NWNDungeon plugin;
    private final WorldStore store;
    private final Entrances entrances;

    public SelfTest(NWNDungeon plugin, WorldStore store, Entrances entrances) {
        this.plugin = plugin;
        this.store = store;
        this.entrances = entrances;
    }

    /** 跑一遍自检，把结果（逐条 ok/FAIL）发给调用者。返回是否全部通过。 */
    public boolean run(CommandSender sender) {
        List<String> report = new ArrayList<>();
        boolean allOk = true;
        int markY = -1;
        // 注意：不能拿 markY > 0 当"埋好了"的判据 —— 虚空世界的出生点 y 是负的（本地实测 -62），
        // 用正数守卫会把**最关键的"复制世界"那一段整段跳过**（这个坑真踩过一次）。
        boolean markPlaced = false;

        // 0. 先清掉上次可能的残留
        cleanupQuietly(TEMPLATE_WORLD);
        cleanupQuietly(INSTANCE_WORLD);
        store.templateFolder(SLUG).delete();
        WorldStore.deleteFolder(store.templateFolder(SLUG));

        // 1. 路径
        File root = store.dimensionsRoot();
        allOk &= line(report, root.isDirectory(),
                "维度文件夹根目录 = " + root,
                "找不到维度文件夹根目录（世界布局和我们预期的不一样）");

        // 2. 建模板世界 + 放标记方块
        World template = null;
        try {
            template = store.create(TEMPLATE_WORLD, "void", true, false);
        } catch (Throwable t) {
            allOk &= line(report, false, "", "建模板世界时抛异常：" + t);
        }
        if (template == null) {
            allOk &= line(report, false, "", "建不出模板世界 " + TEMPLATE_WORLD + "（看控制台日志）");
        } else {
            Location spawn = template.getSpawnLocation();
            markY = Math.max(template.getMinHeight() + 2, spawn.getBlockY());
            template.getBlockAt(MARK, markY - 1, MARK).setType(Material.GOLD_BLOCK, false);
            template.getBlockAt(MARK, markY - 2, MARK).setType(Material.LODESTONE, false);
            template.setSpawnLocation(MARK, markY, MARK);
            template.save();
            markPlaced = true;
            boolean folderOk = WorldStore.looksLikeWorld(template.getWorldFolder());
            allOk &= line(report, folderOk,
                    "模板世界文件夹：" + template.getWorldFolder(),
                    "世界文件夹里没有 region/ 或 data/，看着不像存过的世界");
        }

        // 3. 卸载 + 挪成"模板"
        if (template != null) {
            File folder = template.getWorldFolder();
            store.unload(template, true);
            boolean unloaded = Bukkit.getWorld(TEMPLATE_WORLD) == null;
            allOk &= line(report, unloaded, "卸载后 getWorld() 取不到了", "卸载之后世界还在？");
            File templateFolder = store.templateFolder(SLUG);
            WorldStore.deleteFolder(templateFolder);
            boolean moved = folder.renameTo(templateFolder);
            allOk &= line(report, moved, "已把世界文件夹挪成模板：" + templateFolder.getName(),
                    "把世界文件夹挪成模板失败：" + folder);
        }

        // 4. 复制模板 → 新世界名 → 建世界（**核心假设**）
        if (markPlaced) {
            File templateFolder = store.templateFolder(SLUG);
            File instanceFolder = store.folderOf(INSTANCE_WORLD);
            boolean copied;
            try {
                store.copyFolder(templateFolder, instanceFolder);
                copied = WorldStore.looksLikeWorld(instanceFolder);
            } catch (Exception e) {
                copied = false;
                line(report, false, "", "复制模板文件夹失败：" + e);
            }
            allOk &= line(report, copied,
                    "已把模板复制成 " + instanceFolder.getName(),
                    "复制模板文件夹之后看着不像世界");

            World instance = null;
            try {
                instance = store.create(INSTANCE_WORLD, "void", false, true);
            } catch (Throwable t) {
                line(report, false, "", "建实例世界时抛异常：" + t);
            }
            if (instance == null) {
                allOk &= line(report, false, "", "建不出实例世界 " + INSTANCE_WORLD);
            } else {
                Location found = findMark(instance, markY);
                allOk &= line(report, found != null,
                        "★ 预置文件夹被读进来了：金块在 " + fmt(found) + "（预期 "
                                + MARK + "," + (markY - 1) + "," + MARK + "）",
                        "★ 建出来的世界里**找不到那块金块** —— 说明 createWorld 没有加载预置的维度文件夹，"
                                + "得退回「先建空世界 → 卸载 → 覆盖 region 文件 → 再建」这条路");
                File actual = instance.getWorldFolder();
                allOk &= line(report, actual.getAbsolutePath().equals(instanceFolder.getAbsolutePath()),
                        "实例世界文件夹 = " + actual,
                        "实例世界文件夹和预期不一致（实际 " + actual + "）");
                store.unload(instance, false);
                boolean deleted = WorldStore.deleteFolder(actual);
                allOk &= line(report, deleted && !actual.exists(),
                        "卸载后文件夹已删除",
                        "卸载之后文件夹删不掉（要保留 pending-delete 兜底）");
            }
            // 5. 收尾：删掉模板
            boolean templateDeleted = WorldStore.deleteFolder(templateFolder);
            allOk &= line(report, templateDeleted, "自检用的模板已删除",
                    "自检模板删不掉，请手动删：" + templateFolder);
        }

        // 6. 最后确认没留残留
        StringBuilder dirty = new StringBuilder();
        if (Bukkit.getWorld(TEMPLATE_WORLD) != null) {
            dirty.append("模板世界还在加载 ");
        }
        if (Bukkit.getWorld(INSTANCE_WORLD) != null) {
            dirty.append("实例世界还在加载 ");
        }
        if (store.folderOf(TEMPLATE_WORLD).exists()) {
            dirty.append("残留文件夹 ").append(store.folderOf(TEMPLATE_WORLD)).append(" ");
        }
        if (store.folderOf(INSTANCE_WORLD).exists()) {
            dirty.append("残留文件夹 ").append(store.folderOf(INSTANCE_WORLD)).append(" ");
        }
        if (store.templateFolder(SLUG).exists()) {
            dirty.append("残留模板 ").append(store.templateFolder(SLUG)).append(" ");
        }
        if (dirty.length() > 0) {
            // Windows 上刚卸载的世界，文件句柄可能还占着一小会儿 —— 重试几次再下结论
            for (int i = 0; i < 8 && dirty.length() > 0; i++) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                WorldStore.deleteFolder(store.folderOf(TEMPLATE_WORLD));
                WorldStore.deleteFolder(store.folderOf(INSTANCE_WORLD));
                WorldStore.deleteFolder(store.templateFolder(SLUG));
                dirty.setLength(0);
                if (store.folderOf(TEMPLATE_WORLD).exists()) {
                    dirty.append("残留文件夹 ").append(store.folderOf(TEMPLATE_WORLD)).append(" ");
                }
                if (store.templateFolder(SLUG).exists()) {
                    dirty.append("残留模板 ").append(store.templateFolder(SLUG)).append(" ");
                }
            }
            if (dirty.length() > 0) {
                // 实在删不掉也不该当失败：生产里会记进待删清单下次启动再删（Windows 特有）
                plugin.addPendingDelete(store.templateFolder(SLUG));
                report.add("§7[说明] §r有文件删不掉（Windows 上句柄没释放）：" + dirty
                        + "—— 已按设计记进待删清单；服务器是 Linux，不会遇到这个");
                dirty.setLength(0);
            }
        }
        allOk &= line(report, dirty.length() == 0, "没有留下任何残留",
                "有残留：" + dirty);

        // 7. 标记机制（不需要玩家）：签名失效、门的上下半格
        allOk &= markerChecks(report);

        // 8. 结构读写（入口建筑靠它）：抓取 → 存文件 → 读回来 → 贴到别处 → 核对
        allOk &= structureChecks(report);

        // 9. 入口登记（真 NBT 存储，不是纯字符串）：写进区块 PDC → 从门查 → 从开关反查 → 破坏作废
        allOk &= entranceChecks(report);

        // 10. 自然生成池（只读诊断：告诉管理员"为什么我的入口不生成"）
        for (String text : plugin.describeGenerationPool()) {
            report.add("§7[池] §r" + text);
        }

        String header = "§5[副本]§r 自检结果：" + (allOk ? "§a全部通过" : "§c有失败项");
        sender.sendMessage(header);
        for (String text : report) {
            sender.sendMessage(text);
        }
        plugin.getLogger().info("自检（/nwmdungeon doctor）" + (allOk ? "全部通过" : "有失败项"));
        for (String text : report) {
            plugin.getLogger().info(text.replaceAll("§.", ""));
        }
        return allOk;
    }

    /**
     * 标记机制自检（同样不需要玩家）：
     * <ol>
     *   <li>检查点签名：记下脚下 3×3 → 拆一块 → 判定必须变成"失效" → 补回去 → 必须恢复；</li>
     *   <li>门的上下半格：放一扇门，从上半格取下半格要能取对（关卡门 / 出口门都靠这个）；</li>
     *   <li>校验：空标记要能挑出问题，标齐了要 0 问题。</li>
     * </ol>
     */
    private boolean markerChecks(List<String> report) {
        boolean allOk = true;
        World world = null;
        try {
            world = store.create(TEMPLATE_WORLD, "void", false, false);
        } catch (Throwable t) {
            line(report, false, "", "标记自检建世界失败：" + t);
        }
        if (world == null) {
            return line(report, false, "", "标记自检建不出世界");
        }
        try {
            int y = Math.max(world.getMinHeight() + 2, world.getSpawnLocation().getBlockY());
            // 铺一块 3×3 当检查点脚下
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getBlockAt(MARK + dx, y - 1, MARK + dz).setType(Material.STONE_BRICKS, false);
                }
            }
            int[] pos = new int[]{MARK, y, MARK};
            Markers markers = new Markers();
            markers.addCheckpoint(pos, Markers.signatureOf(world, pos));
            Markers.Checkpoint checkpoint = markers.checkpoints.get(0);
            allOk &= line(report, checkpoint.signature.size() == 9,
                    "检查点签名记了 9 格",
                    "签名格数不对：" + checkpoint.signature.size());
            allOk &= line(report, Markers.signatureMatches(world, checkpoint),
                    "刚标完的检查点有效",
                    "刚标完就判失效了");
            world.getBlockAt(MARK + 1, y - 1, MARK).setType(Material.AIR, false);
            allOk &= line(report, !Markers.signatureMatches(world, checkpoint),
                    "★ 拆掉脚下一块 → 检查点立即失效",
                    "★ 拆了方块却还判有效（签名失效机制没生效）");
            world.getBlockAt(MARK + 1, y - 1, MARK).setType(Material.STONE_BRICKS, false);
            allOk &= line(report, Markers.signatureMatches(world, checkpoint),
                    "补回原样 → 检查点恢复",
                    "补回原样却还是失效");

            // 门的上下半格
            Block lower = world.getBlockAt(MARK + 4, y, MARK);
            Block upper = world.getBlockAt(MARK + 4, y + 1, MARK);
            for (Block block : new Block[]{lower, upper}) {
                block.setType(Material.OAK_DOOR, false);
                if (block.getBlockData() instanceof org.bukkit.block.data.type.Door door) {
                    door.setFacing(org.bukkit.block.BlockFace.NORTH);
                    door.setHalf(block.equals(lower)
                            ? org.bukkit.block.data.Bisected.Half.BOTTOM
                            : org.bukkit.block.data.Bisected.Half.TOP);
                    block.setBlockData(door, false);
                }
            }
            Location fromTop = Markers.doorLower(upper);
            Location fromBottom = Markers.doorLower(lower);
            allOk &= line(report, fromTop.getBlockY() == y && fromBottom.getBlockY() == y,
                    "★ 看着门的上半格/下半格都能取到下半格（" + Markers.fmt(Markers.of(fromTop)) + "）",
                    "门的上下半格取错了：上→" + fromTop.getBlockY() + " 下→" + fromBottom.getBlockY()
                            + "（期望都是 " + y + "）");

            // 校验
            Markers empty = new Markers();
            allOk &= line(report, !empty.validate(world).isEmpty(),
                    "空标记会被校验挑出问题（" + empty.validate(world).size() + " 条）",
                    "空标记居然校验通过");
            Markers complete = new Markers();
            complete.setStart(pos);
            complete.boss = new int[]{MARK, y, MARK + 8};
            complete.exitDoor = new int[]{MARK + 4, y, MARK};
            complete.exitDoorMaterial = "OAK_DOOR";
            complete.addSpawner(new int[]{MARK + 6, y, MARK}, 0, 1);
            List<String> problems = complete.validate(world);
            allOk &= line(report, problems.isEmpty(),
                    "标齐了的副本校验通过（含「门还在不在」的检查）",
                    "标齐了还报问题：" + problems);
        } finally {
            store.unload(world, false);
            WorldStore.deleteFolder(store.folderOf(TEMPLATE_WORLD));
        }
        return allOk;
    }

    /**
     * 结构读写自检（入口建筑靠它，不需要玩家）：
     * 铺一小块带**箱子内容**的场地 → {@code Structure.fill} 抓取 → 存成 .nbt →
     * 读回来 → 贴到另一处 → 核对金块、门朝向、**箱子里的东西还在不在**。
     *
     * <p>这条正是 M0 里"只能等真机"的一项（计划书第十三节第 4 条）：结构粘贴到底保不保真。
     * 入口只是装饰，但箱子内容是 1.4.12 修过的坑，值得一起验。
     */
    private boolean structureChecks(List<String> report) {
        boolean allOk = true;
        String worldName = Names.TEMPLATE_PREFIX + "zzselfstruct";
        cleanupQuietly(worldName);
        File structureFile = new File(store.templatesRoot(), "zzselfstruct.nbt");
        structureFile.delete();

        World world = null;
        try {
            world = store.create(worldName, "void", false, false);
        } catch (Throwable t) {
            line(report, false, "", "结构自检建世界失败：" + t);
        }
        if (world == null) {
            return line(report, false, "", "结构自检建不出世界");
        }
        try {
            int y = Math.max(world.getMinHeight() + 2, world.getSpawnLocation().getBlockY());
            int baseX = MARK;
            int baseZ = MARK;
            // 场地：3×3 金块 + 一扇木门（朝北）+ 一个装着东西的箱子
            for (int dx = 0; dx <= 2; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    world.getBlockAt(baseX + dx, y - 1, baseZ + dz).setType(Material.GOLD_BLOCK, false);
                }
            }
            Block lower = world.getBlockAt(baseX, y, baseZ + 4);
            Block upper = world.getBlockAt(baseX, y + 1, baseZ + 4);
            for (Block block : new Block[]{lower, upper}) {
                block.setType(Material.OAK_DOOR, false);
                if (block.getBlockData() instanceof org.bukkit.block.data.type.Door door) {
                    door.setFacing(org.bukkit.block.BlockFace.NORTH);
                    door.setHalf(block.equals(lower)
                            ? org.bukkit.block.data.Bisected.Half.BOTTOM
                            : org.bukkit.block.data.Bisected.Half.TOP);
                    block.setBlockData(door, false);
                }
            }
            Block chestBlock = world.getBlockAt(baseX + 2, y, baseZ + 2);
            chestBlock.setType(Material.CHEST, false);
            if (chestBlock.getState() instanceof org.bukkit.block.Chest chest) {
                chest.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
                chest.update(true, false);
            }

            // 抓取 → 存 → 读回来
            allOk &= line(report, lower.getBlockData() instanceof org.bukkit.block.data.type.Door
                            && upper.getBlockData() instanceof org.bukkit.block.data.type.Door,
                    "（源世界里门是好的：" + lower.getType() + "/" + upper.getType() + "）",
                    "★ 源世界的门本身就没放好：" + lower.getType() + "/" + upper.getType());

            org.bukkit.structure.StructureManager manager = Bukkit.getStructureManager();
            org.bukkit.structure.Structure structure = manager.createStructure();
            Location min = new Location(world, baseX - 1, y - 2, baseZ - 1);
            Location max = new Location(world, baseX + 3, y + 2, baseZ + 5);
            // 与 EntranceTemplate.save 同一约定：fill 右开，所以传 max+1（期望尺寸 = max-min+1）
            structure.fill(min, max.clone().add(1, 1, 1), true);
            try {
                manager.saveStructure(structureFile, structure);
            } catch (java.io.IOException e) {
                allOk &= line(report, false, "", "结构写文件失败：" + e.getMessage());
            }
            allOk &= line(report, structureFile.isFile() && structureFile.length() > 0,
                    "★ 结构存成了文件（" + structureFile.length() + " 字节）",
                    "结构文件没写出来");

            org.bukkit.structure.Structure loaded = null;
            try {
                loaded = manager.loadStructure(structureFile);
            } catch (java.io.IOException e) {
                allOk &= line(report, false, "", "结构读文件失败：" + e.getMessage());
            }
            if (loaded == null) {
                store.unload(world, false);
                WorldStore.deleteFolder(world.getWorldFolder());
                structureFile.delete();
                return allOk;
            }
            org.bukkit.util.BlockVector size = loaded.getSize();
            allOk &= line(report, size.getBlockX() == 5 && size.getBlockZ() == 7,
                    "结构尺寸对得上（" + size.getBlockX() + "×" + size.getBlockY() + "×"
                            + size.getBlockZ() + "）",
                    "结构尺寸不对：" + size);

            // 贴到远处（x+40），核对方块与箱子内容
            Location target = new Location(world, baseX + 40, y - 2, baseZ - 1);
            loaded.place(target, true, org.bukkit.block.structure.StructureRotation.NONE,
                    org.bukkit.block.structure.Mirror.NONE, 0, 1.0f, new java.util.Random());
            int pastedX = baseX + 41;
            int pastedZ = baseZ;
            Block pastedGold = world.getBlockAt(pastedX, y - 1, pastedZ);
            allOk &= line(report, pastedGold.getType() == Material.GOLD_BLOCK,
                    "★ 贴出来的金块在（" + pastedGold.getType() + "）",
                    "★ 贴出来那一格是 " + pastedGold.getType() + "（期望 GOLD_BLOCK）—— 结构粘贴不保真");

            Block pastedDoor = world.getBlockAt(pastedX, y, pastedZ + 4);
            boolean doorFromStructure = pastedDoor.getBlockData() instanceof org.bukkit.block.data.type.Door;
            report.add("§7[说明] §r结构粘贴" + (doorFromStructure ? "**带回了**门" : "**不带门**（贴出来是空气）")
                    + " —— 位置 " + pastedX + "," + y + "," + (pastedZ + 4));
            // 不管结构带不带，插件都得能把门摆回来（入口的意义就是一扇能按的门）
            boolean reDoor = EntranceTemplate.placeDoor(world,
                    new Location(world, pastedX, y, pastedZ + 4), "OAK_DOOR", "NORTH", "LEFT");
            Block afterRe = world.getBlockAt(pastedX, y, pastedZ + 4);
            Block afterReTop = world.getBlockAt(pastedX, y + 1, pastedZ + 4);
            boolean reDoorOk = reDoor && afterRe.getBlockData() instanceof org.bukkit.block.data.type.Door d
                    && d.getFacing() == org.bukkit.block.BlockFace.NORTH
                    && afterReTop.getBlockData() instanceof org.bukkit.block.data.type.Door;
            allOk &= line(report, reDoorOk,
                    "★ 插件能把门补摆回去（下半格 + 上半格 + 朝向 " + "NORTH" + "）",
                    "★ 补摆门失败：" + afterRe.getType() + "/" + afterReTop.getType()
                            + " " + afterRe.getBlockData().getAsString());

            // 箱子内容：实测这一版 Paper 的 Structure#fill **不带方块实体里的物品**（箱子贴出来是空的）。
            // 这只影响"把带东西的箱子放进入口结构"这种用法 —— 入口是装饰，补给/奖励箱都走标记 + loot.yml，
            // 所以这里只报告、不算失败，但要写下来让管理员知道别指望它。
            // 诊断：在贴出来的区域里扫一遍，看门到底出现在哪个坐标（或压根没贴出来）
            StringBuilder doorScan = new StringBuilder();
            int doorsFound = 0;
            for (int dx = 0; dx <= 4; dx++) {
                for (int dz = 0; dz <= 6; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        Block probe = world.getBlockAt(baseX + 40 + dx, y - 2 + dy, baseZ - 1 + dz);
                        if (probe.getBlockData() instanceof org.bukkit.block.data.type.Door door) {
                            doorsFound++;
                            if (doorsFound <= 3) {
                                doorScan.append("(").append(baseX + 40 + dx).append(",").append(y - 2 + dy)
                                        .append(",").append(baseZ - 1 + dz).append(" ").append(door.getFacing())
                                        .append("/").append(door.getHalf()).append(") ");
                            }
                        }
                    }
                }
            }
            report.add("§7[说明] §r贴出来区域里找到 " + doorsFound + " 个门方块 " + doorScan
                    + "（期望位置 " + pastedX + "," + y + "," + (pastedZ + 4) + "）");

            Block pastedChest = world.getBlockAt(pastedX + 2, y, pastedZ + 2);
            org.bukkit.inventory.ItemStack inChest = null;
            if (pastedChest.getState() instanceof org.bukkit.block.Chest chest) {
                inChest = chest.getInventory().getItem(0);
            }
            boolean chestKept = inChest != null && inChest.getType() == Material.DIAMOND
                    && inChest.getAmount() == 7;
            report.add("§7[说明] §r结构粘贴**不带箱子里的物品**（贴出来是空箱子）—— "
                    + (chestKept ? "本次居然带过来了，可能版本变了" : "与预期一致；入口是装饰，"
                    + "奖励/补给箱请用标记 + loot.yml，别往入口结构的箱子里放东西"));
        } finally {
            store.unload(world, false);
            WorldStore.deleteFolder(world.getWorldFolder());
            structureFile.delete();
        }
        return allOk;
    }

    /**
     * 入口登记自检（**这一条只有真机才验得到**）：入口记录是写在**区块的持久化数据**里的
     * （门是普通方块，存不了 NBT），所以离线只能验"字符串编解码"，验不了"写进世界再读回来"。
     *
     * <p>这里放一扇木门 + 一个石按钮 → 登记 → 从门查 → 从按钮反查 → 打掉门作废。
     */
    private boolean entranceChecks(List<String> report) {
        boolean allOk = true;
        String worldName = Names.TEMPLATE_PREFIX + "zzselfentrance";
        cleanupQuietly(worldName);
        World world = null;
        try {
            world = store.create(worldName, "void", false, false);
        } catch (Throwable t) {
            line(report, false, "", "入口自检建世界失败：" + t);
        }
        if (world == null) {
            return line(report, false, "", "入口自检建不出世界");
        }
        try {
            int y = Math.max(world.getMinHeight() + 2, world.getSpawnLocation().getBlockY());
            // 木门（下半格 + 上半格）+ 门旁一个石按钮
            Block lower = world.getBlockAt(MARK, y, MARK);
            Block upper = world.getBlockAt(MARK, y + 1, MARK);
            for (Block block : new Block[]{lower, upper}) {
                block.setType(Material.OAK_DOOR, false);
                if (block.getBlockData() instanceof org.bukkit.block.data.type.Door door) {
                    door.setFacing(org.bukkit.block.BlockFace.NORTH);
                    door.setHalf(block.equals(lower)
                            ? org.bukkit.block.data.Bisected.Half.BOTTOM
                            : org.bukkit.block.data.Bisected.Half.TOP);
                    block.setBlockData(door, false);
                }
            }
            Block button = world.getBlockAt(MARK + 1, y, MARK);
            button.setType(Material.STONE_BUTTON, false);

            Location doorLower = EntranceTemplate.doorLower(upper);   // 从上半格取下半格
            entrances.register(doorLower, Entrances.KIND_DUNGEON, "zz自检副本",
                    button.getLocation(), Material.OAK_DOOR);

            Entrances.Entry byDoor = entrances.get(doorLower);
            allOk &= line(report, byDoor != null && byDoor.alive(),
                    "★ 入口记录写进区块 PDC 又读回来了（从门查）",
                    "★ 从门查不到入口记录 —— PDC 存储没生效");
            if (byDoor != null) {
                allOk &= line(report, byDoor.isDungeon() && "zz自检副本".equals(byDoor.target()),
                        "★ 自建副本入口的 kind/target 都对（dungeon / zz自检副本）",
                        "kind 或 target 不对：" + byDoor.kind() + " / " + byDoor.target());
                allOk &= line(report, "OAK_DOOR".equals(byDoor.material()),
                        "★ 木门当入口门也记得住（" + byDoor.material() + "）",
                        "门的材质没记对：" + byDoor.material());
                allOk &= line(report, byDoor.trigger() != null
                                && byDoor.trigger().getBlockX() == MARK + 1,
                        "门这边也知道开关在哪（" + fmt(byDoor.trigger()) + "）",
                        "门这边没记下开关的位置");
            }

            Entrances.Entry byTrigger = entrances.byTrigger(button.getLocation());
            allOk &= line(report, byTrigger != null && byTrigger.alive()
                            && byTrigger.door() != null
                            && byTrigger.door().getBlockX() == MARK
                            && byTrigger.door().getBlockY() == y,
                    "★ 从开关反查得到门（" + fmt(byTrigger == null ? null : byTrigger.door()) + "）",
                    "★ 从开关反查不到门 —— 按了按钮也进不去本");

            boolean broken = entrances.markBroken(doorLower);
            Entrances.Entry afterBreak = entrances.get(doorLower);
            allOk &= line(report, broken && afterBreak != null && !afterBreak.alive(),
                    "★ 门被破坏后永久失效（alive=0）",
                    "markBroken 没生效：" + (afterBreak == null ? "记录没了" : "还活着"));
        } finally {
            store.unload(world, false);
            WorldStore.deleteFolder(world.getWorldFolder());
        }
        return allOk;
    }

    /** 在被拷过来的世界里找那块金块（允许 ±3 的 y 误差，方便诊断）。 */
    private Location findMark(World world, int markY) {
        for (int dy = -2; dy <= 3; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Location probe = new Location(world, MARK + dx, markY - 1 + dy, MARK + dz);
                    if (probe.getBlock().getType() == Material.GOLD_BLOCK) {
                        return probe;
                    }
                }
            }
        }
        return null;
    }

    private void cleanupQuietly(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            store.unload(world, false);
        }
        WorldStore.deleteFolder(store.folderOf(worldName));
    }

    private boolean line(List<String> report, boolean ok, String okText, String failText) {
        report.add((ok ? "§a[ok] §r" : "§c[FAIL] §r") + (ok ? okText : failText));
        return ok;
    }

    private static String fmt(Location location) {
        return location == null ? "（找不到）"
                : location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
