package nwndungeon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 编辑器面板（箱子 GUI）—— 用户要求：**编辑器功能全部做成图标，能点的绝不打字**；
 * 需要"对着什么"的动作改成「**先用准星看着它，再点图标**」，而且
 * **他放的是什么门就存什么门，命令里不写材质参数**。
 *
 * <p>两个页面：
 * <ul>
 *   <li><b>列表页</b>：每个副本一个图标（左键进编辑 / 右键删除）＋ 新建副本 ＋ 说明；</li>
 *   <li><b>标记页</b>：落点 / 首领位 / 出口门 / 关卡门 / 关卡与波次（◀▶）/ 刷怪点 / 检查点 /
 *       补给箱 / 奖励箱 / 标记清单 / 校验 / 显示标记 / 撤销 / 清空 / 保存 / 放弃。</li>
 * </ul>
 */
public final class EditorPanel {

    /** 面板类型。 */
    public enum Kind {
        LIST, EDITOR, INFO, CONFIRM_DELETE, CONFIRM_CANCEL, CONFIRM_CLEAR, REWARD_EDIT, ENTRANCE, CONFIRM_ENTRANCE_DELETE
    }

    /** 面板的 holder：靠它认领点击与关闭事件。 */
    public static final class Holder implements InventoryHolder {
        public final Kind kind;
        public final String dungeonName;
        /** 奖励箱编辑用：supply / reward。 */
        public String rewardKind;
        /** 奖励箱编辑用：箱子的绝对坐标。 */
        public int[] rewardPos;
        private Inventory inventory;

        Holder(Kind kind, String dungeonName) {
            this.kind = kind;
            this.dungeonName = dungeonName;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }


    // 标记页：**一行一个主题**（用户反馈"界面太乱"之后重排的）。
    // 行 0 位置 / 行 1 关卡与波次 / 行 2 点位与箱子 / 行 3 门 / 行 4 状态与辅助 / 行 5 操作
    // 每行隔一格放一个图标，其余用黑色玻璃板填满 —— 这样才看得出分组，而不是图标散落在上半屏。
    private static final int SLOT_START = 1;
    private static final int SLOT_BOSS = 3;
    private static final int SLOT_TP_START = 5;
    private static final int SLOT_TP_BOSS = 7;

    private static final int SLOT_STAGE_PREV = 10;
    private static final int SLOT_STAGE_INFO = 11;
    private static final int SLOT_STAGE_NEXT = 12;
    private static final int SLOT_WAVE_PREV = 14;
    private static final int SLOT_WAVE_INFO = 15;
    private static final int SLOT_WAVE_NEXT = 16;

    private static final int SLOT_SPAWNER = 19;
    private static final int SLOT_CHECKPOINT = 20;
    private static final int SLOT_CLEAR_WAVE = 21;
    private static final int SLOT_CHEST_SUPPLY = 23;
    private static final int SLOT_CHEST_REWARD = 24;
    private static final int SLOT_UNDO = 26;

    private static final int SLOT_GATE = 28;
    private static final int SLOT_GATE_REMOVE = 30;
    private static final int SLOT_EXIT = 32;
    private static final int SLOT_EXIT_REMOVE = 34;

    private static final int SLOT_MARKER_LIST = 37;
    private static final int SLOT_VALIDATE = 39;
    private static final int SLOT_WORLD = 41;
    private static final int SLOT_TOGGLE_VIEW = 43;

    private static final int SLOT_DELETE_DUNGEON = 44;
    private static final int SLOT_ENTRANCE_DELETE = 24;
    private static final int SLOT_CLEAR_ALL = 45;
    private static final int SLOT_SAVE = 46;
    private static final int SLOT_CANCEL = 48;
    private static final int SLOT_BACK = 50;
    private static final int SLOT_HELP = 52;
    private static final int SLOT_CLOSE = 53;


    private final NWNDungeon plugin;
    private final DungeonRegistry registry;
    private final Editor editor;
    private final WorldStore store;
    private final ChatPrompt prompt;

    public EditorPanel(NWNDungeon plugin, DungeonRegistry registry, Editor editor,
                       WorldStore store, ChatPrompt prompt) {
        this.plugin = plugin;
        this.registry = registry;
        this.editor = editor;
        this.store = store;
        this.prompt = prompt;
    }

    // ---------------------------------------------------------------- 列表页

    /** 面板 A：副本列表。 */
    /** 面板 A：副本列表。**按内容决定箱子大小**（没内容就 9 格一行，别开 54 格大箱子），按钮居中在最后一行。 */
    public void openList(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        List<DungeonDef> dungeons = new ArrayList<>(registry.all());
        int entries = dungeons.size();
        int size = entries == 0 ? 9 : (entries <= 18 ? 27 : (entries <= 36 ? 45 : 54));
        int actionRow = (size / 9 - 1) * 9;

        Holder holder = new Holder(Kind.LIST, null);
        Inventory inventory = Bukkit.createInventory(holder, size,
                Component.text("副本编辑器 · 自建副本", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        fillFrame(inventory, Material.BLACK_STAINED_GLASS_PANE);

        int slot = 0;
        for (DungeonDef def : dungeons) {
            if (slot >= actionRow) {
                break;
            }
            inventory.setItem(slot++, dungeonIcon(def));
        }
        if (entries == 0) {
            inventory.setItem(3, icon(Material.BOOK, Component.text("还没有自建副本", NamedTextColor.GRAY),
                    Component.text("点右边的「新建副本」开始", NamedTextColor.DARK_GRAY)));
        }
        // 按钮居中在最后一行（9 格宽的一行里放 1/3/5/7）
        inventory.setItem(actionRow + 1, icon(Material.EMERALD,
                Component.text("新建副本", NamedTextColor.GREEN),
                Component.text("点一下，然后在聊天里打副本名", NamedTextColor.GRAY),
                Component.text("（世界名会自动取 ASCII 的）", NamedTextColor.DARK_GRAY)));
        inventory.setItem(actionRow + 3, icon(Material.BRICK,
                Component.text("新建入口建筑", NamedTextColor.GOLD),
                Component.text("给某个已有副本搭一座门楼当入口", NamedTextColor.GRAY),
                Component.text("先打听要挂在哪个副本上", NamedTextColor.DARK_GRAY)));
        inventory.setItem(actionRow + 5, icon(Material.BOOK,
                Component.text("这套怎么用", NamedTextColor.AQUA),
                Component.text("看完整流程与易错点", NamedTextColor.GRAY)));
        inventory.setItem(actionRow + 7, icon(Material.BARRIER,
                Component.text("关闭", NamedTextColor.RED)));
        player.openInventory(inventory);
    }
    private ItemStack dungeonIcon(DungeonDef def) {
        File template = store.templateFolder(def.slug);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("世界名 " + Names.templateWorld(def.slug), NamedTextColor.DARK_GRAY));
        if (WorldStore.looksLikeWorld(template)) {
            long bytes = WorldStore.sizeOf(template);
            lore.add(Component.text("模板 " + WorldStore.describeSize(template),
                    bytes > def.warnWorldSizeMb * 1024L * 1024L ? NamedTextColor.RED : NamedTextColor.GRAY));
        } else {
            lore.add(Component.text("还没有模板（点进去开始搭）", NamedTextColor.YELLOW));
        }
        Markers markers = def.markers;
        lore.add(Component.text("标记 " + markers.total() + " 个 · 关卡 " + markers.stageCount()
                + " · 刷怪点 " + markers.spawners.size()
                + " · 检查点 " + markers.checkpoints.size(), NamedTextColor.GRAY));
        lore.add(Component.text(markers.start == null ? "落点未标（会用世界出生点）"
                : "落点 " + Markers.fmt(markers.start), NamedTextColor.GRAY));
        lore.add(Component.text("限时 " + def.timeLimitMinutes + " 分 · 生成器 " + def.generator
                + " · 并发上限 " + def.maxConcurrentRuns, NamedTextColor.DARK_GRAY));
        lore.add(Component.text("左键 进入编辑", NamedTextColor.GREEN));
        lore.add(Component.text("右键 删除（模板挪进回收站）", NamedTextColor.RED));
        return icon(def.iconMaterial(), Component.text(def.name, NamedTextColor.WHITE), lore);
    }

    // ---------------------------------------------------------------- 标记页

    /** 面板 B：标记面板（编辑态）。 */
    public void openEditor(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Editor.Session session = editor.byPlayer(player.getUniqueId());
        if (session == null) {
            openList(player);
            return;
        }
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            openList(player);
            return;
        }
        Markers markers = def.markers;
        session.stage = editor.clampStage(def, session.stage);
        session.wave = Math.max(1, session.wave);
        int stage = session.stage;
        int wave = session.wave;
        int waveCount = Math.max(wave, markers.waveCount(stage));

        Holder holder = new Holder(Kind.EDITOR, session.dungeonName);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("编辑 · " + session.dungeonName, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        fillFrame(inventory, Material.BLACK_STAINED_GLASS_PANE);

        boolean hasStart = markers.start != null;
        boolean hasExit = markers.exitDoor != null;
        boolean hasBoss = markers.boss != null;

        // 行 0：位置（隔一格放一个，看得出是一组）
        inventory.setItem(SLOT_START, icon(Material.LODESTONE, Component.text("落点", NamedTextColor.AQUA),
                Component.text("点一下 = 把「进本落点」记在你脚下", NamedTextColor.GRAY),
                Component.text(hasStart ? "已标 " + Markers.fmt(markers.start) : "未标（会用世界出生点）",
                        hasStart ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_BOSS, icon(Material.WITHER_SKELETON_SKULL,
                Component.text("首领位", NamedTextColor.LIGHT_PURPLE),
                Component.text("点一下 = 记在你脚下", NamedTextColor.GRAY),
                Component.text("首领属性写在 dungeons.yml 的 boss:", NamedTextColor.DARK_GRAY),
                Component.text(hasBoss ? "已标 " + Markers.fmt(markers.boss) : "未标",
                        hasBoss ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_TP_START, icon(Material.COMPASS,
                Component.text("传送到落点", NamedTextColor.AQUA),
                Component.text(hasStart ? "过去站一下" : "还没标落点", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_TP_BOSS, icon(Material.ENDER_PEARL,
                Component.text("传送到首领位", NamedTextColor.LIGHT_PURPLE),
                Component.text(hasBoss ? "过去站一下" : "还没标首领位", NamedTextColor.GRAY)));

        // 行 1：关卡与波次（左右箭头夹着数字）
        inventory.setItem(SLOT_STAGE_PREV, icon(Material.RED_DYE,
                Component.text("◀ 上一关", NamedTextColor.RED)));
        inventory.setItem(SLOT_STAGE_INFO, icon(Material.PAPER,
                Component.text("第 " + (stage + 1) + " 关 / 共 " + markers.stageCount() + " 关",
                        NamedTextColor.WHITE),
                Component.text(markers.isBossStage(stage) ? "这是首领区（最后一关）" : "普通战斗关",
                        NamedTextColor.GRAY),
                Component.text("关卡数 = 关卡门数 + 1", NamedTextColor.DARK_GRAY),
                Component.text("下面标的刷怪点会记进「第 " + (stage + 1) + " 关」", NamedTextColor.DARK_GRAY)));
        inventory.setItem(SLOT_STAGE_NEXT, icon(Material.LIME_DYE,
                Component.text("▶ 下一关", NamedTextColor.GREEN)));
        inventory.setItem(SLOT_WAVE_PREV, icon(Material.RED_DYE,
                Component.text("◀ 上一波", NamedTextColor.RED)));
        inventory.setItem(SLOT_WAVE_INFO, icon(Material.CLOCK,
                Component.text("第 " + wave + " 波（本关共 " + waveCount + " 波）", NamedTextColor.WHITE),
                Component.text("这一波已经标了 " + markers.spawnersAt(stage, wave).size() + " 个刷怪点",
                        NamedTextColor.GRAY),
                Component.text("清完一波隔 3 秒自动刷下一波", NamedTextColor.DARK_GRAY)));
        inventory.setItem(SLOT_WAVE_NEXT, icon(Material.LIME_DYE,
                Component.text("▶ 下一波", NamedTextColor.GREEN)));

        // 行 2：点位与箱子
        inventory.setItem(SLOT_SPAWNER, icon(Material.ZOMBIE_HEAD,
                Component.text("刷怪点", NamedTextColor.RED),
                Component.text("点一下 = 记在你脚下", NamedTextColor.GRAY),
                Component.text("→ 第 " + (stage + 1) + " 关 · 第 " + wave + " 波", NamedTextColor.YELLOW),
                Component.text("刷什么怪写在 dungeons.yml 的 stages[].waves", NamedTextColor.DARK_GRAY)));
        inventory.setItem(SLOT_CHECKPOINT, icon(Material.RESPAWN_ANCHOR,
                Component.text("检查点", NamedTextColor.AQUA),
                Component.text("点一下 = 记在你脚下（含脚下 3×3 的方块签名）", NamedTextColor.GRAY),
                Component.text("拆掉脚下的方块 → 这个检查点失效", NamedTextColor.DARK_GRAY),
                Component.text("已有 " + markers.checkpoints.size() + " 个", NamedTextColor.GREEN)));
        inventory.setItem(SLOT_CLEAR_WAVE, icon(Material.BARRIER,
                Component.text("清掉本波刷怪点", NamedTextColor.RED),
                Component.text("删掉第 " + (stage + 1) + " 关第 " + wave + " 波的全部刷怪点",
                        NamedTextColor.GRAY)));
        inventory.setItem(SLOT_CHEST_SUPPLY, icon(Material.CHEST,
                Component.text("补给箱", NamedTextColor.GREEN),
                Component.text("看着箱子点这里 → 记位置 + 弹出箱子放东西", NamedTextColor.GRAY),
                Component.text("已登记 " + countChests(markers, "supply") + " 个", NamedTextColor.GREEN)));
        inventory.setItem(SLOT_CHEST_REWARD, icon(Material.ENDER_CHEST,
                Component.text("奖励箱（通关）", NamedTextColor.LIGHT_PURPLE),
                Component.text("看着箱子点这里 → 记位置 + 弹出箱子放东西", NamedTextColor.GRAY),
                Component.text("已登记 " + countChests(markers, "reward") + " 个", NamedTextColor.GREEN)));
        inventory.setItem(SLOT_UNDO, icon(Material.REDSTONE,
                Component.text("撤销上一个标记", NamedTextColor.RED),
                Component.text("撤掉最近打的刷怪点 / 检查点 / 箱子", NamedTextColor.GRAY)));

        // 行 3：门（左键登记、右键删除 —— 同一扇门不能既是关卡门又是出口门）
        inventory.setItem(SLOT_GATE, icon(Material.IRON_DOOR,
                Component.text("关卡门", NamedTextColor.YELLOW),
                Component.text("§f看着一扇门 §7左键 = 登记", NamedTextColor.WHITE),
                Component.text("登记后：清完这一关它才开", NamedTextColor.GRAY),
                Component.text("你放的是什么门就存什么门", NamedTextColor.DARK_GRAY),
                Component.text("已有 " + markers.gates.size() + " 道", NamedTextColor.GREEN)));
        inventory.setItem(SLOT_GATE_REMOVE, icon(Material.BARRIER,
                Component.text("删除关卡门", NamedTextColor.RED),
                Component.text("看着那道门点这里 = 删掉它", NamedTextColor.GRAY),
                Component.text("没看着门时：删「当前这一关」那道门", NamedTextColor.GRAY),
                Component.text("删完后面的关卡会自动往前挪一位", NamedTextColor.DARK_GRAY)));
        inventory.setItem(SLOT_EXIT, icon(Material.OAK_DOOR,
                Component.text("出口门", NamedTextColor.GOLD),
                Component.text("§f看着一扇门 §7左键 = 登记", NamedTextColor.WHITE),
                Component.text("通关后右键它就能离开", NamedTextColor.GRAY),
                Component.text(hasExit ? "已标 " + markers.exitDoorMaterial + " " + Markers.fmt(markers.exitDoor)
                                : "未标（通关时用首领位的木门兜底）",
                        hasExit ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_EXIT_REMOVE, icon(Material.BARRIER,
                Component.text("删除出口门", NamedTextColor.RED),
                Component.text("清掉出口门标记", NamedTextColor.GRAY)));

        // 行 4：状态与辅助
        inventory.setItem(SLOT_MARKER_LIST, icon(Material.MAP,
                Component.text("标记清单", NamedTextColor.YELLOW),
                Component.text("在聊天里逐条列出所有标记", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_VALIDATE, icon(Material.COMPARATOR,
                Component.text("校验", NamedTextColor.YELLOW),
                Component.text("检查缺了什么、有没有失效的", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_WORLD, icon(Material.GRASS_BLOCK,
                Component.text("这个世界", NamedTextColor.YELLOW),
                Component.text("世界名 " + session.worldName, NamedTextColor.GRAY),
                Component.text(WorldStore.describeSize(store.folderOf(session.worldName)),
                        NamedTextColor.GRAY)));
        inventory.setItem(SLOT_TOGGLE_VIEW, icon(session.showMarkers ? Material.GLOWSTONE : Material.GUNPOWDER,
                Component.text(session.showMarkers ? "标记提示：开" : "标记提示：关",
                        session.showMarkers ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                Component.text("用粒子把标过的位置标出来", NamedTextColor.GRAY)));

        // 行 5：操作
        inventory.setItem(SLOT_DELETE_DUNGEON, icon(Material.BARRIER,
                Component.text("删除这个副本", NamedTextColor.DARK_RED),
                Component.text("删掉它的配置与模板登记", NamedTextColor.GRAY),
                Component.text("模板挪进 templates/_deleted/ 留退路，不硬删", NamedTextColor.DARK_GRAY),
                Component.text("点一下还会再问你一次", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_CLEAR_ALL, icon(Material.TNT,
                Component.text("清空全部标记", NamedTextColor.RED),
                Component.text("只清标记，不动世界里的方块", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_SAVE, icon(Material.LIME_DYE,
                Component.text("保存", NamedTextColor.GREEN),
                Component.text("落盘并搬成模板（旧模板自动备份）", NamedTextColor.GRAY),
                Component.text("保存后会退出编辑态", NamedTextColor.DARK_GRAY)));
        inventory.setItem(SLOT_CANCEL, icon(Material.RED_DYE,
                Component.text("放弃这次改动", NamedTextColor.RED),
                Component.text("删掉这次编辑世界，模板原样不动", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_BACK, icon(Material.ARROW,
                Component.text("返回列表", NamedTextColor.WHITE),
                Component.text("编辑态不会退出", NamedTextColor.DARK_GRAY)));
        inventory.setItem(SLOT_HELP, icon(Material.BOOK,
                Component.text("怎么搭一座副本", NamedTextColor.AQUA)));
        player.openInventory(inventory);
        player.openInventory(inventory);
    }

    private int countChests(Markers markers, String kind) {
        int count = 0;
        for (Markers.Chest chest : markers.chests) {
            if (chest.kind.equalsIgnoreCase(kind)) {
                count++;
            }
        }
        return count;
    }

    /** 奖励箱编辑界面：一个虚拟箱子，关掉时把内容写进 loot.yml。 */
    public void openRewardEditor(Player player, String dungeonName, String kind, int[] pos) {
        Holder holder = new Holder(Kind.REWARD_EDIT, dungeonName);
        holder.rewardKind = kind;
        holder.rewardPos = pos;
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("reward".equalsIgnoreCase(kind) ? "奖励箱内容" : "补给箱内容",
                        NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        if (plugin.lootTables().configured(dungeonName,
                "reward".equalsIgnoreCase(kind) ? "reward-chest" : "supply-chest")) {
            List<LootEntry> pool = "reward".equalsIgnoreCase(kind)
                    ? plugin.lootTables().reward(dungeonName) : plugin.lootTables().supply(dungeonName);
            int slot = 0;
            for (LootEntry entry : pool) {
                if (slot >= inventory.getSize()) {
                    break;
                }
                inventory.setItem(slot++, toItem(entry));
            }
        }
        player.openInventory(inventory);
        player.sendMessage("§7把要发的东西放进这个箱子，§f关掉界面§7就会写进 loot.yml 的 §f"
                + "dungeons." + dungeonName + "."
                + ("reward".equalsIgnoreCase(kind) ? "reward-chest" : "supply-chest")
                + "§7；权重/数量之后在 yml 里调。");
    }

    /** 把掉落表里的一项还原成物品（重新打开界面时能看到已经配好的东西）。 */
    private ItemStack toItem(LootEntry entry) {
        int amount = Math.max(1, entry.min() > 0 ? entry.min() : 1);
        ItemStack stack = new ItemStack(entry.material(), amount);
        if (!entry.hasEnchants()) {
            return stack;
        }
        if (stack.getType() == Material.ENCHANTED_BOOK
                && stack.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            entry.enchants().forEach((name, level) -> {
                Enchantment enchantment = enchantment(name);
                if (enchantment != null) {
                    meta.addStoredEnchant(enchantment, Math.max(1, level), true);
                }
            });
            stack.setItemMeta(meta);
            return stack;
        }
        entry.enchants().forEach((name, level) -> {
            Enchantment enchantment = enchantment(name);
            if (enchantment != null) {
                stack.addUnsafeEnchantment(enchantment, Math.max(1, level));
            }
        });
        return stack;
    }

    private Enchantment enchantment(String name) {
        return org.bukkit.Registry.ENCHANTMENT.get(
                org.bukkit.NamespacedKey.minecraft(name.toLowerCase(java.util.Locale.ROOT)));
    }

    private void openInfo(Player player) {
        // 说明页也是 27 格（别为了几行字开 54 格大箱子）
        Holder holder = new Holder(Kind.INFO, null);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("怎么搭一座副本", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        fillFrame(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(1, icon(Material.LODESTONE, Component.text("1. 落点", NamedTextColor.AQUA),
                Component.text("站在「进本后要出现的位置」点一下", NamedTextColor.GRAY)));
        inventory.setItem(3, icon(Material.IRON_DOOR, Component.text("2. 关卡门", NamedTextColor.YELLOW),
                Component.text("看着一扇门点「关卡门」→ 结束当前关", NamedTextColor.GRAY),
                Component.text("左键登记 / 右键删除", NamedTextColor.DARK_GRAY)));
        inventory.setItem(5, icon(Material.ZOMBIE_HEAD, Component.text("3. 刷怪点", NamedTextColor.RED),
                Component.text("◀▶ 选关卡与波次 → 站到位置点一下", NamedTextColor.GRAY)));
        inventory.setItem(7, icon(Material.RESPAWN_ANCHOR, Component.text("4. 检查点", NamedTextColor.AQUA),
                Component.text("站到位置点一下（脚下 3×3 记账）", NamedTextColor.GRAY),
                Component.text("拆掉脚下方块 → 该检查点失效", NamedTextColor.DARK_GRAY)));
        inventory.setItem(10, icon(Material.OAK_DOOR, Component.text("5. 出口门", NamedTextColor.GOLD),
                Component.text("看着一扇门点「出口门」", NamedTextColor.GRAY),
                Component.text("同一扇门不能既是关卡门又是出口门", NamedTextColor.DARK_GRAY)));
        inventory.setItem(12, icon(Material.CHEST, Component.text("6. 箱子", NamedTextColor.GREEN),
                Component.text("看着箱子点「补给箱/奖励箱」→ 放东西", NamedTextColor.GRAY),
                Component.text("关掉界面就写进 loot.yml", NamedTextColor.DARK_GRAY)));
        inventory.setItem(14, icon(Material.LIME_DYE, Component.text("7. 保存", NamedTextColor.GREEN),
                Component.text("点「保存」把编辑世界搬成模板", NamedTextColor.GRAY)));
        inventory.setItem(16, icon(Material.KNOWLEDGE_BOOK, Component.text("要点", NamedTextColor.YELLOW),
                Component.text("· 全程点图标，不用打字", NamedTextColor.GRAY),
                Component.text("· 要指哪个方块：先用准星看着它再点图标", NamedTextColor.GRAY),
                Component.text("· 门不写材质：你放的是什么门就存什么门", NamedTextColor.GRAY),
                Component.text("· 「放弃」不会动已经保存的模板", NamedTextColor.GRAY)));
        inventory.setItem(19, icon(Material.ARROW, Component.text("返回", NamedTextColor.WHITE)));
        inventory.setItem(23, icon(Material.BARRIER, Component.text("关闭", NamedTextColor.RED)));
        player.openInventory(inventory);
    }

    private void openConfirm(Player player, Kind kind, String dungeonName, String title, String hint) {
        Holder holder = new Holder(kind, dungeonName);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text(title, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        fillFrame(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(11, icon(Material.LIME_DYE, Component.text("确认", NamedTextColor.GREEN),
                Component.text(hint, NamedTextColor.GRAY)));
        inventory.setItem(15, icon(Material.RED_DYE, Component.text("取消", NamedTextColor.RED)));
        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 入口搭建页

    /** 入口搭建面板：看着方块点两下 + 保存。 */
    public void openEntranceEditor(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Editor.EntranceSession session = editor.entranceSession(player.getUniqueId());
        if (session == null) {
            openList(player);
            return;
        }
        Holder holder = new Holder(Kind.ENTRANCE, session.dungeonName);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("搭入口 · " + session.dungeonName, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        fillFrame(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(10, icon(Material.OAK_DOOR,
                Component.text("入口门", NamedTextColor.GOLD),
                Component.text("看着一扇门点这里", NamedTextColor.GRAY),
                Component.text("你放的是什么门就存什么门", NamedTextColor.DARK_GRAY),
                Component.text(session.door == null ? "未记"
                                : "已记：" + session.door.getBlock().getType(),
                        session.door == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        inventory.setItem(12, icon(Material.STONE_BUTTON,
                Component.text("触发点", NamedTextColor.AQUA),
                Component.text("看着按钮 / 压力板 / 拉杆点这里", NamedTextColor.GRAY),
                Component.text(session.trigger == null ? "未记"
                                : "已记：" + session.trigger.getBlock().getType(),
                        session.trigger == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        inventory.setItem(14, icon(Material.EMERALD,
                Component.text("保存入口", NamedTextColor.GREEN),
                Component.text("自动框出建筑范围 → 存成结构 → 离开这个搭建世界", NamedTextColor.GRAY)));
        inventory.setItem(16, icon(Material.BARRIER,
                Component.text("放弃", NamedTextColor.RED),
                Component.text("删掉这个搭建世界（已存的结构不动）", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_ENTRANCE_DELETE, icon(Material.TNT,
                Component.text("删除这份入口结构", NamedTextColor.DARK_RED),
                Component.text("把已存的入口结构删掉（.nbt），副本就不能再 spawn 它", NamedTextColor.GRAY),
                Component.text("世界里已经放出去的入口不会消失", NamedTextColor.DARK_GRAY),
                Component.text("删完重新搭一座再保存 = 一份干净的入口", NamedTextColor.GRAY)));
        inventory.setItem(22, icon(Material.BOOK,
                Component.text("提示", NamedTextColor.YELLOW),
                Component.text("· 门与开关要挨着（进本时靠触发器找门）", NamedTextColor.GRAY),
                Component.text("· 建筑别超过 128 格（自动框选有上限）", NamedTextColor.GRAY),
                Component.text("· 保存后用 /nwmdungeon spawn 贴一座出来", NamedTextColor.GRAY)));
        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 图标工具

    private void fillFrame(Inventory inventory, Material material) {
        ItemStack filler = icon(material, Component.text(" "));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack icon(Material material, Component name, Component... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>();
                for (Component line : lore) {
                    lines.add(line.decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack icon(Material material, Component name, List<Component> lore) {
        return icon(material, name, lore.toArray(new Component[0]));
    }

    // ---------------------------------------------------------------- 点击 / 关闭

    /** 处理面板点击；返回 true = 这个事件属于我们的面板。 */
    public boolean handleClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return false;
        }
        if (holder.kind == Kind.REWARD_EDIT) {
            return false;   // 奖励箱界面要能放东西，不拦点击
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return true;
        }
        int slot = event.getRawSlot();
        switch (holder.kind) {
            case LIST -> handleList(player, slot, event.isRightClick());
            case EDITOR -> handleEditor(player, slot, holder.dungeonName, event.isRightClick());
            case ENTRANCE -> handleEntrance(player, slot);
            case INFO -> {
                // 说明页是 27 格版：返回在 19、关闭在 23（不是面板 B 那套 50/53）
                if (slot == 19) {
                    later(player, () -> {
                        if (editor.byPlayer(player.getUniqueId()) != null) {
                            openEditor(player);
                        } else if (editor.entranceSession(player) != null) {
                            openEntranceEditor(player);
                        } else {
                            openList(player);
                        }
                    });
                } else if (slot == 23) {
                    player.closeInventory();
                }
            }
            case CONFIRM_DELETE -> {
                if (slot == 11) {
                    later(player, () -> deleteDungeon(player, holder.dungeonName));
                } else if (slot == 15) {
                    later(player, () -> openList(player));
                }
            }
            case CONFIRM_ENTRANCE_DELETE -> {
                if (slot == 11) {
                    later(player, () -> deleteEntranceStructure(player, holder.dungeonName));
                } else if (slot == 15) {
                    later(player, () -> openEntranceEditor(player));
                }
            }
            case CONFIRM_CANCEL -> {
                if (slot == 11) {
                    later(player, () -> {
                        Editor.Session session = editor.byPlayer(player.getUniqueId());
                        if (session != null) {
                            editor.cancel(player, session);
                        }
                        openList(player);
                    });
                } else if (slot == 15) {
                    later(player, () -> openEditor(player));
                }
            }
            case CONFIRM_CLEAR -> {
                if (slot == 11) {
                    later(player, () -> {
                        DungeonDef def = registry.get(holder.dungeonName);
                        if (def != null) {
                            def.markers.clearAll();
                            registry.save();
                            player.sendMessage("§a已清空「" + def.name + "」的全部标记。");
                        }
                        openEditor(player);
                    });
                } else if (slot == 15) {
                    later(player, () -> openEditor(player));
                }
            }
            default -> {
            }
        }
        return true;
    }

    /** 奖励箱界面关掉 = 把里面的东西写进 loot.yml。 */
    public boolean handleClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return false;
        }
        if (holder.kind != Kind.REWARD_EDIT || !(event.getPlayer() instanceof Player player)) {
            return false;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack stack : event.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                items.add(stack);
            }
        }
        String key = "reward".equalsIgnoreCase(holder.rewardKind) ? "reward-chest" : "supply-chest";
        int written = LootWriter.write(plugin, holder.dungeonName, key, items);
        if (items.isEmpty()) {
            player.sendMessage("§7箱子是空的 → 已把 loot.yml 里 " + holder.dungeonName + "." + key + " 清掉。");
        } else {
            player.sendMessage("§a已把 " + written + " 项写进 loot.yml（"
                    + holder.dungeonName + "." + key + "）。§7权重/数量去那个文件里调。");
        }
        later(player, () -> openEditor(player));
        return true;
    }

    private void later(Player player, Runnable action) {
        // 在 InventoryClickEvent 里换界面要等下一 tick，不然客户端会闪一下甚至不显示
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private void handleList(Player player, int slot, boolean rightClick) {
        List<DungeonDef> list = new ArrayList<>(registry.all());
        int entries = list.size();
        int size = entries == 0 ? 9 : (entries <= 18 ? 27 : (entries <= 36 ? 45 : 54));
        int actionRow = (size / 9 - 1) * 9;
        if (slot == actionRow + 1) {
            prompt.ask(player, "在聊天里打一个副本名（例如 §f熔岩要塞§r）", this::createDungeon);
            return;
        }
        if (slot == actionRow + 3) {
            prompt.ask(player, "要建哪个副本的入口？在聊天里打副本名（例如 §f熔岩要塞§r）",
                    this::createEntrance);
            return;
        }
        if (slot == actionRow + 5) {
            later(player, () -> openInfo(player));
            return;
        }
        if (slot == actionRow + 7) {
            player.closeInventory();
            return;
        }
        if (slot >= entries) {
            return;   // 空白格 / 分隔格
        }
        DungeonDef def = list.get(slot);
        if (rightClick) {
            later(player, () -> openConfirm(player, Kind.CONFIRM_DELETE, def.name,
                    "确认删除", "配置与模板登记都会清掉，模板挪进 templates/_deleted/"));
            return;
        }
        later(player, () -> enterEditing(player, def));
    }

    /** 标记页的点击：左键登记；门和箱子那几个支持右键删除。 */
    private void handleEditor(Player player, int slot, String dungeonName, boolean rightClick) {
        Editor.Session session = editor.byPlayer(player.getUniqueId());
        if (session == null) {
            later(player, () -> openList(player));
            return;
        }
        DungeonDef def = registry.get(dungeonName);
        if (def == null) {
            later(player, () -> openList(player));
            return;
        }
        Markers markers = def.markers;

        if (slot == SLOT_START) {
            editor.markStart(player, session);
            reopen(player);
        } else if (slot == SLOT_BOSS) {
            editor.markBoss(player, session);
            reopen(player);
        } else if (slot == SLOT_TP_START) {
            World world = Bukkit.getWorld(session.worldName);
            if (world != null && markers.start != null) {
                player.teleport(new org.bukkit.Location(world, markers.start[0] + 0.5,
                        markers.start[1], markers.start[2] + 0.5));
            } else {
                player.sendMessage("§7还没标落点。");
            }
            reopen(player);
        } else if (slot == SLOT_TP_BOSS) {
            World world = Bukkit.getWorld(session.worldName);
            if (world != null && markers.boss != null) {
                player.teleport(new org.bukkit.Location(world, markers.boss[0] + 0.5,
                        markers.boss[1], markers.boss[2] + 0.5));
            } else {
                player.sendMessage("§7还没标首领位。");
            }
            reopen(player);
        } else if (slot == SLOT_STAGE_PREV) {
            session.stage = editor.clampStage(def, session.stage - 1);
            session.wave = 1;
            reopen(player);
        } else if (slot == SLOT_STAGE_NEXT) {
            session.stage = editor.clampStage(def, session.stage + 1);
            session.wave = 1;
            reopen(player);
        } else if (slot == SLOT_WAVE_PREV) {
            session.wave = Math.max(1, session.wave - 1);
            reopen(player);
        } else if (slot == SLOT_WAVE_NEXT) {
            session.wave = Math.min(64, session.wave + 1);
            reopen(player);
        } else if (slot == SLOT_SPAWNER) {
            editor.markSpawner(player, session, session.wave);
            reopen(player);
        } else if (slot == SLOT_CHECKPOINT) {
            editor.markCheckpoint(player, session);
            reopen(player);
        } else if (slot == SLOT_CLEAR_WAVE) {
            int stage = editor.clampStage(def, session.stage);
            int removed = 0;
            for (Markers.Spawner spawner : new ArrayList<>(markers.spawnersAt(stage, session.wave))) {
                if (markers.removeSpawner(spawner.pos)) {
                    removed++;
                }
            }
            registry.save();
            player.sendMessage("§a已清掉第 " + (stage + 1) + " 关第 " + session.wave + " 波的 "
                    + removed + " 个刷怪点。");
            reopen(player);
        } else if (slot == SLOT_CHEST_SUPPLY || slot == SLOT_CHEST_REWARD) {
            Block chest = editor.targetedChest(player);
            if (chest == null) {
                player.sendMessage("§c先用准星看着一个箱子（放个箱子再看着它），然后点这个图标。");
                reopen(player);
                return;
            }
            String kind = slot == SLOT_CHEST_REWARD ? "reward" : "supply";
            editor.markChest(player, session, chest, kind);
            later(player, () -> openRewardEditor(player, dungeonName, kind,
                    Markers.of(chest.getLocation())));
        } else if (slot == SLOT_UNDO) {
            String what = markers.undoLast();
            registry.save();
            player.sendMessage(what == null ? "§7没有可撤销的标记。" : "§a已撤销：" + what);
            reopen(player);
        } else if (slot == SLOT_GATE) {
            Block door = editor.targetedDoor(player);
            if (door == null) {
                player.sendMessage("§c先用准星看着一扇门，再点「关卡门」。");
            } else if (rightClick) {
                editor.removeGate(player, session, door);      // 右键 = 删除看着的这道门
            } else {
                editor.markGate(player, session, door);
            }
            reopen(player);
        } else if (slot == SLOT_GATE_REMOVE) {
            editor.removeGate(player, session, editor.targetedDoor(player));
            reopen(player);
        } else if (slot == SLOT_EXIT) {
            if (rightClick) {
                editor.clearExitDoor(player, session);
            } else {
                Block door = editor.targetedDoor(player);
                if (door == null) {
                    player.sendMessage("§c先用准星看着一扇门，再点「出口门」。");
                } else {
                    editor.markExitDoor(player, session, door);
                }
            }
            reopen(player);
        } else if (slot == SLOT_EXIT_REMOVE) {
            editor.clearExitDoor(player, session);
            reopen(player);
        } else if (slot == SLOT_MARKER_LIST) {
            printMarkers(player, def);
            reopen(player);
        } else if (slot == SLOT_VALIDATE) {
            printValidation(player, def, session);
            reopen(player);
        } else if (slot == SLOT_WORLD) {
            player.sendMessage("§7世界名 §f" + session.worldName + "§7，生成器 §f" + session.generator
                    + "§7，体积 §f" + WorldStore.describeSize(store.folderOf(session.worldName)));
            reopen(player);
        } else if (slot == SLOT_TOGGLE_VIEW) {
            session.showMarkers = !session.showMarkers;
            player.sendMessage(session.showMarkers ? "§a标记提示：开" : "§7标记提示：关");
            reopen(player);
        } else if (slot == SLOT_DELETE_DUNGEON) {
            later(player, () -> openConfirm(player, Kind.CONFIRM_DELETE, dungeonName,
                    "确认删除这个副本",
                    "配置与模板登记都会清掉，模板挪进 templates/_deleted/；"
                            + "世界里已经放出去的入口不会自动消失（把门打掉就失效）"));
        } else if (slot == SLOT_CLEAR_ALL) {
            later(player, () -> openConfirm(player, Kind.CONFIRM_CLEAR, dungeonName,
                    "确认清空标记", "只清标记，不会动世界里的方块"));
        } else if (slot == SLOT_SAVE) {
            later(player, () -> editor.save(player, session));
        } else if (slot == SLOT_CANCEL) {
            later(player, () -> openConfirm(player, Kind.CONFIRM_CANCEL, dungeonName,
                    "确认放弃改动", "这次编辑世界会被删掉，已保存的模板不动"));
        } else if (slot == SLOT_BACK) {
            later(player, () -> openList(player));
        } else if (slot == SLOT_HELP) {
            later(player, () -> openInfo(player));
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        }
    }

    /** 入口搭建页的点击：看着门/开关点两下 + 保存。 */
    private void handleEntrance(Player player, int slot) {
        Editor.EntranceSession session = editor.entranceSession(player.getUniqueId());
        if (session == null) {
            later(player, () -> openList(player));
            return;
        }
        if (slot == 10) {
            Block door = editor.targetedDoor(player);
            if (door == null) {
                player.sendMessage("§c先用准星看着一扇门，再点「入口门」。");
            } else {
                editor.markEntranceDoor(player, session, door);
            }
            later(player, () -> openEntranceEditor(player));
        } else if (slot == 12) {
            Block trigger = editor.targetedTrigger(player);
            if (trigger == null) {
                player.sendMessage("§c先用准星看着一个按钮 / 压力板 / 拉杆。");
            } else {
                editor.markEntranceTrigger(player, session, trigger);
            }
            later(player, () -> openEntranceEditor(player));
        } else if (slot == 14) {
            later(player, () -> editor.saveEntrance(player, session));
        } else if (slot == 16) {
            later(player, () -> editor.cancelEntrance(player, session));
        } else if (slot == SLOT_ENTRANCE_DELETE) {
            later(player, () -> openConfirm(player, Kind.CONFIRM_ENTRANCE_DELETE,
                    session.dungeonName, "确认删除这份入口结构",
                    "已存的入口结构文件会被删掉（副本就不能再 spawn / 自然生成它）；"
                            + "世界里已经放出去的那几座不会消失，把门打掉就失效"));
        }
    }

    private void reopen(Player player) {
        later(player, () -> openEditor(player));
    }

    private void printMarkers(Player player, DungeonDef def) {
        Markers markers = def.markers;
        player.sendMessage("§5[编辑器]§r " + def.name + " 的标记（共 " + markers.total() + " 个）：");
        player.sendMessage("§7 落点：§f" + Markers.fmt(markers.start)
                + "§7 · 首领位：§f" + Markers.fmt(markers.boss)
                + "§7 · 出口门：§f" + (markers.exitDoor == null ? "未标"
                : markers.exitDoorMaterial + " " + Markers.fmt(markers.exitDoor)));
        for (int i = 0; i < markers.gates.size(); i++) {
            player.sendMessage("§7 关卡门 " + (i + 1) + "：§f" + Markers.fmt(markers.gates.get(i)));
        }
        for (int stage = 0; stage < markers.stageCount(); stage++) {
            StringBuilder line = new StringBuilder("§7 第 " + (stage + 1) + " 关"
                    + (markers.isBossStage(stage) ? "（首领区）" : "") + "：");
            int waves = markers.waveCount(stage);
            if (waves == 0) {
                line.append("§e没有刷怪点");
            } else {
                for (int wave = 1; wave <= waves; wave++) {
                    line.append("§f第").append(wave).append("波 ")
                            .append(markers.spawnersAt(stage, wave).size()).append(" 点 ");
                }
            }
            player.sendMessage(line.toString());
        }
        for (int i = 0; i < markers.checkpoints.size(); i++) {
            player.sendMessage("§7 检查点 " + (i + 1) + "：§f" + Markers.fmt(markers.checkpoints.get(i).pos));
        }
        for (Markers.Chest chest : markers.chests) {
            player.sendMessage("§7 " + (chest.isReward() ? "奖励箱" : "补给箱")
                    + "（第 " + (chest.stage + 1) + " 关）：§f" + Markers.fmt(chest.pos));
        }
    }

    private void printValidation(Player player, DungeonDef def, Editor.Session session) {
        World world = Bukkit.getWorld(session.worldName);
        List<String> problems = def.markers.validate(world);
        List<String> hints = def.markers.hints();
        if (problems.isEmpty()) {
            player.sendMessage("§5[编辑器]§r §a校验通过：标记齐全，没有失效的。");
        } else {
            player.sendMessage("§5[编辑器]§r 校验发现 " + problems.size() + " 个问题：");
            for (String problem : problems) {
                player.sendMessage("§e · §f" + problem);
            }
        }
        if (!hints.isEmpty()) {
            player.sendMessage("§7提醒（不影响开本）：");
            for (String hint : hints) {
                player.sendMessage("§8 · §7" + hint);
            }
        }
    }

    /** 点「新建入口建筑」：问要塞在哪个副本上，然后开搭建世界。 */
    private void createEntrance(Player player, String text) {
        DungeonDef def = registry.find(text.trim());
        if (def == null) {
            player.sendMessage("§c没找到这个副本。现有的：" + (registry.size() == 0 ? "（无）"
                    : String.join("、", registry.names())));
            prompt.ask(player, "再打一个副本名", this::createEntrance);
            return;
        }
        if (editor.byPlayer(player.getUniqueId()) != null) {
            player.sendMessage("§c你正在编辑副本本体，先保存或放弃再来搭入口。");
            return;
        }
        String error = editor.openEntrance(player, def);
        if (error != null) {
            player.sendMessage("§c" + error);
            openList(player);
            return;
        }
        openEntranceEditor(player);
    }

    private void createDungeon(Player player, String text) {
        String invalid = DungeonRegistry.validateName(text);
        if (invalid != null) {
            player.sendMessage("§c" + invalid);
            prompt.ask(player, "再打一个副本名", this::createDungeon);
            return;
        }
        // 重名就直接进**那个已有的副本**去改（不覆盖、也不新建第二个）。
        // 必须用 findByName：配置里存的是 §f测试要塞，玩家打的是 测试要塞，精确匹配对不上。
        DungeonDef existing = registry.findByName(text);
        if (existing == null) {
            existing = registry.get(text.trim());
        }
        if (existing != null) {
            player.sendMessage("§e已经有「" + existing.name + "§e」了，直接进去编辑它"
                    + "（没有覆盖，也没新建第二个）。");
            enterEditing(player, existing);
            return;
        }
        DungeonDef def = registry.create(text);
        if (def == null) {
            player.sendMessage("§c建不了这个副本（名字重复？）。");
            return;
        }
        player.sendMessage("§a已新建副本「" + def.name + "§a」，世界名 §f" + Names.templateWorld(def.slug));
        enterEditing(player, def);
    }

    /** 进编辑态：模板存在就复制出来继续改，否则建个空世界。 */
    private void enterEditing(Player player, DungeonDef def) {
        File template = store.templateFolder(def.slug);
        boolean fromTemplate = WorldStore.looksLikeWorld(template);
        String error = editor.open(player, def, def.generator, fromTemplate);
        if (error != null) {
            player.sendMessage("§c" + error);
            openList(player);
            return;
        }
        player.sendMessage(fromTemplate
                ? "§a已把模板「" + def.name + "§a」复制出来继续改；§f/nwmdungeon edit§a 打开标记面板。"
                : "§a空世界已就绪；§f/nwmdungeon edit§a 打开标记面板开始搭。");
        openEditor(player);
    }

    private void deleteDungeon(Player player, String name) {
        DungeonDef def = registry.get(name);
        if (def == null) {
            player.sendMessage("§c没有这个副本。");
            openList(player);
            return;
        }
        // 人可能正站在这个副本的编辑世界里：先按「放弃」把会话收掉（会把人送回去、删掉临时世界）
        Editor.Session session = editor.byPlayer(player.getUniqueId());
        if (session != null && session.dungeonName.equalsIgnoreCase(name)) {
            editor.cancel(player, session);
        }
        registry.delete(name, store);
        player.sendMessage("§a已删除「" + def.name + "§a」（模板挪进 templates/_deleted/）。");
        openList(player);
    }

    /**
     * 删掉这个副本的**入口结构**（{@code templates/entrances/<slug>.nbt} 与同名 .yml）。
     *
     * <p>为什么需要：入口结构一旦"越存越多"（重新进去改时把上次那份也贴回来、
     * 保存时一起框进去），spawn 出来就会有好几座。删掉这份重新搭一座再保存，
     * 就能得到干净的一份。世界里已经放出去的那几座不受影响（把门打掉就失效）。
     */
    private void deleteEntranceStructure(Player player, String dungeonName) {
        DungeonDef def = registry.findByName(dungeonName);
        if (def == null) {
            player.sendMessage("§c找不到这个副本。");
            openList(player);
            return;
        }
        File nbt = new File(store.templatesRoot(), "entrances/" + def.slug + ".nbt");
        File yml = new File(store.templatesRoot(), "entrances/" + def.slug + ".yml");
        boolean had = nbt.isFile() || yml.isFile();
        if (nbt.isFile() && !nbt.delete()) {
            player.sendMessage("§c删不掉 " + nbt.getName() + "（文件被占用？）");
            return;
        }
        if (yml.isFile()) {
            yml.delete();
        }
        player.sendMessage(had
                ? "§a已删掉「" + def.name + "§a」的入口结构。§7现在可以重新搭一座："
                + "§f/nwd new entrance create " + DungeonRegistry.stripColors(def.name)
                + "§7，搭好保存后就是干净的一份。"
                : "§7本来就没存过入口结构。");
        if (editor.entranceSession(player) != null) {
            openEntranceEditor(player);
        } else {
            openList(player);
        }
    }
}
