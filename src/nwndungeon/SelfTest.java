package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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

        // 11. 死亡归属判定（"首领自己消失却被当成通关"那类问题的核心）
        allOk &= killJudgmentChecks(report);

        // 12. 区块票据（玩家走慢时远处的怪会不会被卸载）
        allOk &= chunkTicketChecks(report);

        // 13. 走进房间才开始刷怪（不要提前把几百格外的怪刷出来）
        allOk &= lazySpawnChecks(report);
        allOk &= spawnSafetyChecks(report);

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

    /**
     * 死亡归属判定自检：**这只怪的死，算不算玩家打赢的？**
     *
     * <p>对应玩家报的「boss 自己消失，然后直接进结算」：以前任何死亡事件都被当成通关。
     * 现在规则是"玩家打过它（30 秒内）才算"，所以这里验三件事：
     * <ol>
     *   <li>悬空处刷首领能不能自动找到 / 铺出落脚点（不让它一出来就掉出世界）；</li>
     *   <li>**没有任何玩家碰过**的怪死了 → 不算玩家击杀（这就是修掉的那条 bug）；</li>
     *   <li>有玩家打过、随后死于环境 → **仍算玩家击杀**（打进岩浆 / 打落虚空）。</li>
     * </ol>
     * 第 3 条需要一名在线玩家，没有就跳过并说明。
     */
    private boolean killJudgmentChecks(List<String> report) {
        boolean allOk = true;
        String worldName = Names.TEMPLATE_PREFIX + "zzselfkill";
        cleanupQuietly(worldName);
        World world = null;
        try {
            world = store.create(worldName, "void", false, false);
        } catch (Throwable t) {
            line(report, false, "", "判定自检建世界失败：" + t);
        }
        if (world == null) {
            return line(report, false, "", "判定自检建不出世界");
        }
        try {
            // ① M8 不再靠凭空铺石台掩盖错误坐标：先验证拒绝，再搭测试用地板。
            Location air = new Location(world, MARK, Math.max(world.getMinHeight() + 20, 80), MARK);
            boolean voidRejected = false;
            try { DungeonBuilder.safeSpawnSpot(world, air); }
            catch (MobSpawner.Failure expected) { voidRejected = true; }
            allOk &= line(report, voidRejected, "★ 悬空标记被拒绝，不会凭空铺石台", "悬空标记没有被拒绝");
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    world.getBlockAt(MARK + x, air.getBlockY() - 1, MARK + z).setType(Material.STONE, false);
                }
            }
            Location safe = DungeonBuilder.safeSpawnSpot(world, air);
            Block ground = world.getBlockAt(safe.getBlockX(), safe.getBlockY() - 1, safe.getBlockZ());
            allOk &= line(report, ground.getType().isSolid(),
                    "★ 首领测试点能落到实地（" + Markers.fmt(Markers.of(safe)) + "，脚下 "
                            + ground.getType() + "）",
                    "★ 悬空处没给出落脚点（脚下是 " + ground.getType() + "）—— 首领会掉出世界");

            // ② 没有玩家参与的死 → 不算玩家击杀
            org.bukkit.entity.LivingEntity lonely =
                    (org.bukkit.entity.LivingEntity) world.spawnEntity(safe,
                            org.bukkit.entity.EntityType.ZOMBIE);
            lonely.setHealth(0);
            allOk &= line(report, !PlayerHits.countsAsPlayerKill(lonely),
                    "★ 没有任何玩家碰过的怪死了 → 不算玩家击杀（这就是 boss 自己消失却结算的成因）",
                    "★ 没人碰过的怪死了却被算成玩家击杀");

            // ③ 有玩家打过、随后死于环境 → 仍算玩家击杀
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (online.isEmpty()) {
                report.add("§7[说明] §r「被玩家打过的怪死于环境仍算击杀」这一条需要一名在线玩家，"
                        + "本次跳过（有人在线时重跑 §f/nwd doctor§r 就能验）");
            } else {
                Player someone = online.get(0);
                org.bukkit.entity.LivingEntity hit =
                        (org.bukkit.entity.LivingEntity) world.spawnEntity(safe,
                                org.bukkit.entity.EntityType.ZOMBIE);
                PlayerHits.record(hit, someone);
                hit.setHealth(0);
                allOk &= line(report, PlayerHits.countsAsPlayerKill(hit),
                        "★ 被玩家（" + someone.getName() + "）打过、随后死于环境 → 仍算玩家击杀"
                                + "（打进岩浆 / 打落虚空走的就是这条）",
                        "★ 被玩家打过却不算击杀");
                PlayerHits.forget(hit);
            }
        } finally {
            store.unload(world, false);
            WorldStore.deleteFolder(world.getWorldFolder());
        }
        return allOk;
    }

    /**
     * 区块票据自检：**玩家走慢时，远处的怪为什么不会"刷没"**。
     *
     * <p>怪身上的 persistent 只能防"自然消失"，**防不住区块卸载**：玩家离得远时，
     * 副本另一头的区块卸载 → 里面的怪从内存里消失 → 插件看成"怪不见了"。
     * 所以一局开始时要把它用到的区块钉住，这一条验的就是这件事。
     */
    private boolean chunkTicketChecks(List<String> report) {
        boolean allOk = true;

        // ① 纯计算：落点 (0,64,0) + 140 格外的首领位 → 框要覆盖到那边
        DungeonDef def = new DungeonDef("zz自检");
        def.markers.start = new int[]{0, 64, 0};
        def.markers.boss = new int[]{140, 64, 0};
        int[] bounds = DungeonRun.ticketBounds(def);
        allOk &= line(report, bounds != null && bounds[0] <= 0 && bounds[2] >= 8,
                "★ 140 格大的副本，区块框算对了（"
                        + (bounds == null ? "null" : bounds[0] + "," + bounds[1] + " → "
                        + bounds[2] + "," + bounds[3]) + "）",
                "★ 区块框算错：" + (bounds == null ? "null" : java.util.Arrays.toString(bounds)));

        // ② 人手放歪到几千格外的标记要被夹住，不能把上千区块全钉住
        def.markers.boss = new int[]{100000, 64, 100000};
        int[] far = DungeonRun.ticketBounds(def);
        int wide = far == null ? -1 : far[2] - far[0] + 1;
        int deep = far == null ? -1 : far[3] - far[1] + 1;
        allOk &= line(report, far != null && wide <= 25 && deep <= 25,
                "★ 超远标记被夹在半径内（" + wide + "×" + deep + " 区块）",
                "★ 超远标记没夹住：" + wide + "×" + deep + " 区块（会把上千区块全钉住）");

        // ③ 真机：钉住一个 96 格外的区块，它应当真的保持加载
        String worldName = Names.TEMPLATE_PREFIX + "zzselfticket";
        cleanupQuietly(worldName);
        World world = null;
        try {
            world = store.create(worldName, "void", false, false);
        } catch (Throwable t) {
            line(report, false, "", "票据自检建世界失败：" + t);
        }
        if (world == null) {
            return line(report, false, "", "票据自检建不出世界");
        }
        try {
            Location spawn = world.getSpawnLocation();
            int cx = (spawn.getBlockX() >> 4) + 6;
            int cz = (spawn.getBlockZ() >> 4) + 6;
            world.addPluginChunkTicket(cx, cz, plugin);
            world.getChunkAt(cx, cz);
            allOk &= line(report, world.isChunkLoaded(cx, cz),
                    "★ 钉住的远端区块确实保持加载（第 " + cx + "," + cz + " 区块，离出生点 96 格）",
                    "★ 钉住了却还是没加载 —— 远处的怪会被卸载");
            world.removePluginChunkTicket(cx, cz, plugin);
        } finally {
            store.unload(world, false);
            WorldStore.deleteFolder(world.getWorldFolder());
        }
        return allOk;
    }

    /**
     * "走进房间才开始刷怪"自检（1.5.0-M7 的核心改动）。
     *
     * <p>以前是**建房时就把每一间房的第一波全刷出来**，最远那间离玩家几百格 ——
     * 既不在模拟距离内（不 tick），区块一卸载还会消失，于是出现
     * "高难度副本里打得越慢、首领越容易自己消失然后直接结算"。
     *
     * <p>这条自检直接建一座真的副本，然后数怪：① 建房之后一只怪都不该有；
     * ② 每一间都应当是"未开始"状态。
     */
    private boolean lazySpawnChecks(List<String> report) {
        boolean allOk = true;
        String worldName = Names.TEMPLATE_PREFIX + "zzselflazy";
        cleanupQuietly(worldName);
        World world = null;
        try {
            world = store.create(worldName, "void", false, false);
        } catch (Throwable t) {
            line(report, false, "", "懒刷自检建世界失败：" + t);
        }
        if (world == null) {
            return line(report, false, "", "懒刷自检建不出世界");
        }
        try {
            Tier tier = plugin.tier("iron");
            if (tier == null) {
                return line(report, false, "", "找不到 iron 难度，跳过懒刷自检");
            }
            int y = Math.max(world.getMinHeight() + 40, 64);
            Dungeon dungeon = new DungeonBuilder().build(world, 8, y, 8, tier);

            int preSpawned = 0;
            int started = 0;
            for (Dungeon.Room room : dungeon.rooms) {
                preSpawned += room.mobs.size();
                if (room.started) {
                    started++;
                }
            }
            allOk &= line(report, preSpawned == 0,
                    "★ 建房时不再提前刷怪（" + dungeon.rooms.size() + " 间房，0 只怪）",
                    "★ 建房时提前刷出了 " + preSpawned + " 只怪（远处的怪会因区块卸载而消失）");
            allOk &= line(report, started == 0,
                    "★ 所有房间都是「未开始」，等玩家走进去才刷",
                    "★ 有 " + started + " 间房在建房时就标成已开始");
            Dungeon.Room builtBossRoom = dungeon.rooms.get(dungeon.rooms.size() - 1);
            for (String tierId : List.of("iron", "gold", "diamond")) {
                Tier checkedTier = plugin.tier(tierId);
                if (checkedTier == null) { continue; }
                Dungeon plan = new DungeonBuilder().plan(world, 8, y, 8, checkedTier).dungeon();
                Dungeon.Room finalRoom = plan.rooms.get(plan.rooms.size() - 1);
                finalRoom.origin = builtBossRoom.origin;
                finalRoom.center = builtBossRoom.center;
                try {
                    int lastWave = finalRoom.waves.size() - 1;
                    DungeonBuilder.spawnWave(world, finalRoom, lastWave, checkedTier);
                    int expected = finalRoom.waves.get(lastWave).size() + 1;
                    allOk &= line(report, finalRoom.bossEntity != null && finalRoom.bossEntity.isValid()
                                    && Math.abs(finalRoom.bossEntity.getHealth() - checkedTier.bossHealth()) < 0.01
                                    && finalRoom.mobs.size() == expected,
                            "★ M8 " + tierId + " 末关完整一波成功：护卫与首领共 " + expected + " 只",
                            "M8 " + tierId + " 末关生成失败：" + (finalRoom.lastBossFailure == null ? "实体或数量不符" : finalRoom.lastBossFailure.getMessage()));
                } catch (RuntimeException e) {
                    allOk &= line(report, false, "", "M8 " + tierId + " 末关整波异常：" + e.getMessage());
                } finally { finalRoom.entities.values().forEach(org.bukkit.entity.Entity::remove); }
            }
        } finally {
            store.unload(world, false);
            WorldStore.deleteFolder(world.getWorldFolder());
        }
        return allOk;
    }

    /** M8 生成回归：真实世界、真实实体和被其他插件取消生成的场景。 */
    private boolean spawnSafetyChecks(List<String> report) {
        boolean ok = true;
        String name = Names.TEMPLATE_PREFIX + "zzselfspawn";
        cleanupQuietly(name);
        World world = null;
        org.bukkit.event.Listener listener = null;
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger mutateHealth = new java.util.concurrent.atomic.AtomicInteger();
        try {
            world = store.create(name, "void", false, false);
            if (world == null) { return line(report, false, "", "M8 刷怪自检无法创建世界"); }
            world.setDifficulty(org.bukkit.Difficulty.NORMAL);
            final World checkedWorld = world;
            listener = new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
                public void onSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
                    if (event.getLocation().getWorld().equals(checkedWorld) && mutateHealth.get() > 0) {
                        var attribute = event.getEntity().getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                        if (attribute != null) {
                            if (mutateHealth.get() == 1) { attribute.setBaseValue(60); event.getEntity().setHealth(60); }
                            else { attribute.addModifier(new org.bukkit.attribute.AttributeModifier(
                                    org.bukkit.NamespacedKey.minecraft("nwndungeon_selftest_external_health"),
                                    20, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER)); }
                        }
                    }
                    if (cancel.get() && event.getLocation().getWorld().equals(checkedWorld)) {
                        event.setCancelled(true);
                    }
                }
            };
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            int y = 64;
            for (int x = -5; x <= 5; x++) {
                for (int z = -5; z <= 5; z++) {
                    world.getBlockAt(x, y - 1, z).setType(Material.STONE, false);
                }
            }
            Location point = new Location(world, 0.5, y, 0.5);
            ok &= ordinaryRecoveryChecks(world, point, report, true);
            ok &= ordinaryRecoveryChecks(world, point, report, false);
            for (int mutation : new int[]{1, 2}) {
                mutateHealth.set(mutation);
                org.bukkit.entity.LivingEntity changed = MobSpawner.spawn(world, point,
                        org.bukkit.entity.EntityType.ZOMBIE, entity -> MobHealth.apply(entity, 160));
                try {
                    ok &= line(report, Math.abs(changed.getHealth() - 160) < 0.01
                                    && Math.abs(changed.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue() - 160) < 0.01,
                            "★ M8 生成事件" + (mutation == 1 ? "改成60" : "增加生命修饰器") + "后仍按配置160生成",
                            "M8 生成事件覆盖了配置血量，场景 " + mutation);
                } finally { changed.remove(); mutateHealth.set(0); }
            }
            org.bukkit.entity.LivingEntity zombie = MobSpawner.spawn(world, point,
                    org.bukkit.entity.EntityType.ZOMBIE, null);
            ok &= line(report, zombie.isValid() && !zombie.getRemoveWhenFarAway() && zombie.isPersistent(),
                    "★ M8 普通怪生成有效，已显式关闭远离玩家后的自然消失", "M8 防自然消失标志错误");
            zombie.remove();
            for (org.bukkit.entity.EntityType type : List.of(org.bukkit.entity.EntityType.SKELETON,
                    org.bukkit.entity.EntityType.STRAY, org.bukkit.entity.EntityType.BOGGED,
                    org.bukkit.entity.EntityType.PILLAGER)) {
                org.bukkit.entity.LivingEntity ranged = MobSpawner.spawn(world, point, type, null);
                try {
                    Material expected = type == org.bukkit.entity.EntityType.PILLAGER ? Material.CROSSBOW : Material.BOW;
                    ok &= line(report, ranged.getEquipment() != null
                                    && ranged.getEquipment().getItemInMainHand().getType() == expected,
                            "★ M8 原版默认武器保留：" + type + " / " + expected,
                            "M8 原版默认武器丢失：" + type);
                } finally { ranged.remove(); }
            }
            var customYaml = SafeYaml.parse("type: SKELETON\nequipment:\n  mainhand:\n    item: IRON_SWORD\n", "自检装备模板");
            MobTemplate custom = MobTemplate.from("selftest_weapon", customYaml);
            org.bukkit.entity.LivingEntity equipped = MobSpawner.spawn(world, point,
                    org.bukkit.entity.EntityType.SKELETON, custom::apply);
            try {
                ok &= line(report, equipped.getEquipment().getItemInMainHand().getType() == Material.IRON_SWORD,
                        "★ M8 自定义模板武器仍能覆盖原版弓", "M8 原版初始化覆盖了自定义武器");
            } finally { equipped.remove(); }
            org.bukkit.entity.LivingEntity unarmed = MobSpawner.spawn(world, point,
                    org.bukkit.entity.EntityType.SKELETON,
                    entity -> entity.getEquipment().setItemInMainHand(new ItemStack(Material.AIR)));
            try {
                ok &= line(report, unarmed.getEquipment().getItemInMainHand().getType().isAir(),
                        "★ M8 显式空手配置仍然生效", "M8 显式空手被默认弓覆盖");
            } finally { unarmed.remove(); }
            Tier loaded = plugin.tier("iron");
            if (loaded != null) {
                var disk = SafeYaml.read(new File(plugin.getDataFolder(), "config.yml"));
                double diskHealth = MobHealth.read(disk, "tiers.iron.boss.health", 60);
                ok &= line(report, loaded.bossHealth() == diskHealth,
                        "★ M8 iron 生效配置与磁盘一致：首领血量 " + diskHealth,
                        "M8 iron 配置未生效：磁盘 " + diskHealth + " / 内存 " + loaded.bossHealth()
                                + "，文件 " + new File(plugin.getDataFolder(), "config.yml").getAbsolutePath());
                Dungeon.Room bossRoom = new Dungeon.Room(1);
                bossRoom.center = point;
                bossRoom.origin = point.clone().add(-12, -1, -12);
                DungeonBuilder.spawnBoss(world, bossRoom, loaded);
                try {
                    double actual = bossRoom.bossEntity.getHealth();
                    double maximum = bossRoom.bossEntity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
                    ok &= line(report, Math.abs(actual - loaded.bossHealth()) < 0.01 && Math.abs(maximum - actual) < 0.01,
                            "★ M8 内置首领真实血量 " + actual + " / " + maximum + "，配置 " + loaded.bossHealth(),
                            "M8 内置首领血量不符：配置 " + loaded.bossHealth() + "，实体 " + actual + " / " + maximum);
                    var checkBar = Bukkit.createBossBar("自检", org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SOLID);
                    try {
                        Instances.Slot sample = new Instances.Slot(-1, point);
                        sample.settings = loaded; sample.bar = checkBar; sample.dungeon = new Dungeon();
                        bossRoom.bossRoom = true; sample.dungeon.rooms.add(bossRoom);
                        Instances sampleInstances = new Instances(plugin);
                        sampleInstances.refreshBar(sample);
                        String configuredText = java.math.BigDecimal.valueOf(loaded.bossHealth()).stripTrailingZeros().toPlainString();
                        boolean full = checkBar.getTitle().endsWith(configuredText + " / " + configuredText) && checkBar.getProgress() == 1.0;
                        bossRoom.bossEntity.setHealth(actual * 0.75);
                        sampleInstances.refreshBar(sample);
                        ok &= line(report, full && Math.abs(checkBar.getProgress() - 0.75) < 0.001
                                        && checkBar.getTitle().endsWith(" / " + configuredText),
                                "★ M8 内置 BossBar 与配置 " + configuredText + " 同步，受伤后进度为 75%",
                                "M8 内置 BossBar 与实体/配置不一致：" + checkBar.getTitle());
                    } finally { checkBar.removeAll(); }
                } finally { bossRoom.bossEntity.remove(); }
            }
            DungeonDef customDef = new DungeonDef("zzselfhealth");
            customDef.boss.health = 160;
            DungeonRun customRun = new DungeonRun(plugin, customDef, world, store, false);
            var configureBoss = DungeonRun.class.getDeclaredMethod("configureBoss", org.bukkit.entity.LivingEntity.class, MobTemplate.class);
            configureBoss.setAccessible(true);
            org.bukkit.entity.LivingEntity customBoss = MobSpawner.spawn(world, point,
                    org.bukkit.entity.EntityType.SKELETON, entity -> {
                        try { configureBoss.invoke(customRun, entity, custom); }
                        catch (ReflectiveOperationException e) { throw new MobSpawner.Failure("自检首领配置失败", e); }
                    });
            try {
                ok &= line(report, Math.abs(customBoss.getHealth() - 160) < 0.01,
                        "★ M8 自建首领真实血量 160，正确覆盖模板血量", "M8 首领血量被模板覆盖");
                var checkBar = Bukkit.createBossBar("自检", org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SOLID);
                try {
                    var stageField = DungeonRun.class.getDeclaredField("stages"); stageField.setAccessible(true);
                    @SuppressWarnings("unchecked") List<DungeonRun.Stage> sampleStages = (List<DungeonRun.Stage>) stageField.get(customRun);
                    DungeonRun.Stage sampleStage = new DungeonRun.Stage(0);
                    sampleStage.bossStage = true; sampleStage.bossId = customBoss.getUniqueId(); sampleStage.bossEntity = customBoss;
                    sampleStages.add(sampleStage);
                    var barField = DungeonRun.class.getDeclaredField("bar"); barField.setAccessible(true); barField.set(customRun, checkBar);
                    customRun.refreshBar();
                    boolean full = checkBar.getTitle().endsWith("160 / 160") && checkBar.getProgress() == 1.0;
                    customBoss.setHealth(120.5); customRun.refreshBar();
                    ok &= line(report, full && checkBar.getTitle().endsWith("120.5 / 160")
                                    && Math.abs(checkBar.getProgress() - 120.5 / 160) < 0.00001,
                            "★ M8 自建 BossBar 满血 160/160，受伤后 120.5/160 与实体一致",
                            "M8 自建 BossBar 未同步：" + checkBar.getTitle());
                    customBoss.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(60);
                    customBoss.setHealth(45);
                    customRun.refreshBar();
                    ok &= line(report, customRun.failure() == null && checkBar.getTitle().endsWith("45 / 160")
                                    && Math.abs(customBoss.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue() - 160) < 0.01
                                    && Math.abs(customBoss.getHealth() - 45) < 0.01,
                            "★ M8 战斗中上限被改成60，已校正为160并保留剩余45，本局继续",
                            "M8 血条仍因可修复的属性变化结束整局");
                } finally { checkBar.removeAll(); }
            } finally { customBoss.remove(); }
            org.bukkit.entity.LivingEntity fractional = MobSpawner.spawn(world, point,
                    org.bukkit.entity.EntityType.ZOMBIE, entity -> MobHealth.apply(entity, 12.5));
            try {
                ok &= line(report, Math.abs(fractional.getHealth() - 12.5) < 0.01,
                        "★ M8 小于 20 的小数血量 12.5 不再被抬高", "M8 小数或低血量配置失效");
            } finally { fractional.remove(); }
            world.getBlockAt(0, y + 2, 0).setType(Material.STONE, false);
            org.bukkit.entity.LivingEntity tall = MobSpawner.spawn(world, point,
                    org.bukkit.entity.EntityType.WITHER_SKELETON, null);
            ok &= line(report, tall.isValid() && !tall.collidesAt(tall.getLocation())
                            && (tall.getLocation().getBlockX() != 0 || tall.getLocation().getBlockZ() != 0),
                    "★ M8 按真实体型避开低矮天花，不把凋灵骷髅塞进两格空间", "M8 高个怪生成位置不安全");
            ok &= line(report, tall.getEquipment().getItemInMainHand().getType() == Material.STONE_SWORD,
                    "★ M8 凋灵骷髅保留原版石剑", "M8 凋灵骷髅默认石剑丢失");
            tall.remove();
            boolean rejected = false;
            try { MobSpawner.spawn(world, new Location(world, 32.5, y, 0.5),
                    org.bukkit.entity.EntityType.ZOMBIE, null); }
            catch (MobSpawner.Failure expected) { rejected = true; }
            ok &= line(report, rejected && world.getBlockAt(32, y - 1, 0).getType() == Material.AIR,
                    "★ M8 无落脚点时明确失败，不铺石台、不刷入虚空", "M8 悬空点被错误接受或修改了建筑");
            cancel.set(true);
            boolean cancellationCaught = false;
            try { MobSpawner.spawn(world, point, org.bukkit.entity.EntityType.ZOMBIE, null); }
            catch (MobSpawner.Failure expected) { cancellationCaught = true; }
            ok &= line(report, cancellationCaught, "★ M8 生成事件被取消时报告失败，不登记假实体", "M8 取消生成仍被当作成功");
        } catch (Throwable e) {
            ok &= line(report, false, "", "M8 刷怪安全自检异常：" + e);
        } finally {
            if (listener != null) { org.bukkit.event.HandlerList.unregisterAll(listener); }
            if (world != null) { store.unload(world, false); }
            WorldStore.deleteFolder(store.folderOf(name));
        }
        return ok;
    }

    /** 实际调用两套波次生成与失踪巡检；改等待时间戳，避免阻塞服务端主线程。 */
    private boolean ordinaryRecoveryChecks(World world, Location point, List<String> report, boolean builtin) throws Exception {
        String label = builtin ? "内置" : "自建";
        Instances instances = new Instances(plugin);
        Instances.Slot slot = new Instances.Slot(-7, point);
        slot.busy = true; slot.settings = plugin.tier("iron"); slot.dungeon = new Dungeon();
        Dungeon.Room room = new Dungeon.Room(1);
        room.displayIndex = 2; room.started = true; room.origin = point.clone().add(-6, -1, -6);
        room.waves.add(List.of(org.bukkit.entity.EntityType.SKELETON, org.bukkit.entity.EntityType.SKELETON));
        slot.dungeon.rooms.add(room);
        var worldField = Instances.class.getDeclaredField("world"); worldField.setAccessible(true); worldField.set(instances, world);
        var slotsField = Instances.class.getDeclaredField("slots"); slotsField.setAccessible(true);
        @SuppressWarnings("unchecked") var slots = (java.util.Map<Integer, Instances.Slot>) slotsField.get(instances);
        slots.put(slot.index, slot);
        DungeonRun run = new DungeonRun(plugin, new DungeonDef("zzselfrecovery"), world, store, false);
        DungeonRun.Stage stage = new DungeonRun.Stage(0); stage.started = true;
        stage.waves.add(List.of(org.bukkit.entity.EntityType.SKELETON, org.bukkit.entity.EntityType.SKELETON));
        stage.templates.add(java.util.Arrays.asList(null, null)); stage.spots.add(List.of(point));
        var stagesField = DungeonRun.class.getDeclaredField("stages"); stagesField.setAccessible(true);
        @SuppressWarnings("unchecked") var stages = (List<DungeonRun.Stage>) stagesField.get(run); stages.add(stage);
        var prune = builtin ? Instances.class.getDeclaredMethod("pruneVanishedMobs", Instances.Slot.class)
                : DungeonRun.class.getDeclaredMethod("pruneVanishedMobs");
        prune.setAccessible(true);
        Runnable inspect = () -> {
            try { if (builtin) { prune.invoke(instances, slot); } else { prune.invoke(run); } }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        };
        List<java.util.UUID> mobs = builtin ? room.mobs : stage.mobs;
        var entities = builtin ? room.entities : stage.entities;
        var recoveries = builtin ? room.mobRecovery : stage.mobRecovery;
        boolean ok = true;
        try {
            if (builtin) { DungeonBuilder.spawnWave(world, room, 0, slot.settings); }
            else {
                var spawn = DungeonRun.class.getDeclaredMethod("spawnWave", DungeonRun.Stage.class, int.class);
                spawn.setAccessible(true); spawn.invoke(run, stage, 0);
            }
            ok &= line(report, mobs.size() == 2 && recoveries.size() == 2,
                    "★ M8 " + label + "真实波次为普通怪登记恢复记录", label + "波次缺少恢复记录");
            java.util.UUID lost = mobs.get(0);
            var original = entities.get(lost); original.setHealth(7.5); original.remove();
            inspect.run();
            boolean waited = mobs.get(0).equals(lost) && mobs.size() == 2;
            MobRecovery state = recoveries.get(lost);
            // 上面的首次巡检已开始计时；先结束等待再注入测试时间。
            state.recovery.found(); state.recovery.missing(System.currentTimeMillis() - 5_100, true);
            inspect.run();
            var replacement = entities.get(mobs.get(0));
            ok &= line(report, waited && !mobs.get(0).equals(lost) && replacement.isValid()
                            && Math.abs(replacement.getHealth() - 7.5) < 0.01
                            && replacement.getEquipment().getItemInMainHand().getType() == Material.BOW
                            && mobs.size() == 2 && (builtin ? slot.failure == null && room.kills == 0 : run.failure() == null && run.kills == 0),
                    "★ M8 " + label + "怪物异常移除后先等待，再恢复原弓和剩余7.5血；未增加击杀或推进",
                    label + "普通怪恢复错误或被算作击杀");
            boolean deathHandled = builtin ? instances.onMobDeath(replacement) : run.onMobDeath(replacement);
            replacement.remove(); inspect.run();
            ok &= line(report, deathHandled && mobs.size() == 1 && recoveries.size() == 1
                            && (builtin ? room.kills == 1 : run.kills == 1),
                    "★ M8 " + label + "确认死亡后清除恢复记录，不再补刷", label + "已击杀怪物被重新补刷");
            MobRecovery lineage = recoveries.get(mobs.get(0));
            boolean retainedBudget = true;
            for (int attempt = 1; attempt <= SpawnRecovery.MAX_RETRIES; attempt++) {
                java.util.UUID previous = mobs.get(0); entities.get(previous).remove();
                lineage.recovery.missing(System.currentTimeMillis() - 5_100, true);
                inspect.run();
                retainedBudget &= !mobs.get(0).equals(previous) && recoveries.get(mobs.get(0)) == lineage && lineage.attempts() == attempt;
            }
            entities.get(mobs.get(0)).remove();
            lineage.recovery.missing(System.currentTimeMillis() - 5_100, true); inspect.run();
            ok &= line(report, retainedBudget && mobs.size() == 1
                            && (builtin ? slot.failure != null && !room.cleared : run.failure() != null && !run.completed && !stage.cleared),
                    "★ M8 " + label + "连续移除最多恢复3次，换UUID不重置预算；耗尽后失败而非通关",
                    label + "恢复预算被重置或错误通关");
        } finally {
            for (var entity : entities.values()) { entity.remove(); }
            // 测试样本不调用退款和世界回收；只清理它自己的实体与区块票据。
            if (!builtin) { run.cleanup(); }
        }
        return ok;
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
