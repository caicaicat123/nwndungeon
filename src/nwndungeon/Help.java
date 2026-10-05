package nwndungeon;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 帮助文本的**唯一来源**：每条命令在这里登记一次（用法 / 参数 / 权限 / 说明 / 示例 / 易错点），
 * 帮助页由它生成。
 *
 * <p>这么做的理由：帮助最容易和实现漂移（写了没做、做了没写）。登记表 + 生成，
 * 加命令时顺手登记一条，就不会出现"文档说的和实际不一样"。
 *
 * <p>分组：{@code player} 玩家可见、{@code admin} 管理员、{@code dev} 创作者（自建副本工具）。
 */
public final class Help {

    /** 一条命令。 */
    public record Entry(String group, String key, String usage, String args,
                        String perm, String desc, String example, String pitfall) {
    }

    private static final int PAGE_SIZE = 8;
    private static final String PLAYER = "player";
    private static final String ADMIN = "admin";
    private static final String DEV = "dev";

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static void add(String group, String key, String usage, String args,
                            String perm, String desc, String example, String pitfall) {
        ENTRIES.add(new Entry(group, key, usage, args, perm, desc, example, pitfall));
    }

    static {
        // ---------------- 玩家 ----------------
        add(PLAYER, "leave", "/dungeon leave", "无", "nwndungeon.use",
                "离开当前副本，回到进本前的位置。",
                "/dungeon leave", "副本里禁用其它命令，这条永远可用。");
        add(PLAYER, "stamina", "/dungeon stamina", "无", "无",
                "查看自己的体力（每日次数）。",
                "/dungeon stamina", "体力不够就进不了本，等它慢慢回。");
        add(PLAYER, "locate", "/dungeon locate [难度|any]", "难度名或 any（不写=不限）", "无",
                "找离你最近的副本入口，给方向和坐标。",
                "/dungeon locate 普通", "自然入口是在新区块里随机长出来的，没找到就多探索。");

        // ---------------- 管理员 ----------------
        add(ADMIN, "spawn", "/dungeon spawn <难度> [x y z [世界]]", "难度 = iron/gold/diamond", "nwndungeon.admin",
                "在指定位置生成一座副本入口（不写坐标 = 你面前）。",
                "/dungeon spawn iron", "门被破坏后这个入口就永久失效了，要重生成一座。");
        add(ADMIN, "test", "/dungeon test <难度>", "同上", "nwndungeon.admin",
                "生成一个内置副本槽位用来自测（打完自动释放）。",
                "/dungeon test diamond", "它占的是槽位，不是这个世界；槽位满就没得开。");
        add(ADMIN, "list", "/dungeon list", "无", "nwndungeon.admin",
                "看内置副本的槽位占用情况。",
                "/dungeon list", "只列内置副本的槽位；自建副本是独立世界，看 /nwd list。");
        add(ADMIN, "tp", "/dungeon tp <槽位号>", "槽位号（数字）", "nwndungeon.admin",
                "传送到某个内置副本槽位。",
                "/dungeon tp 0", "槽位空着也能进（传送到它的原点）。");
        add(ADMIN, "release", "/dungeon release <槽位号|all>", "槽位号或 all", "nwndungeon.admin",
                "强制回收副本槽位，里面的人会被送回入口。",
                "/dungeon release all", "回收的是内置副本的槽位；自建副本的临时世界用 /nwd end。");
        add(ADMIN, "reload", "/dungeon reload", "无", "nwndungeon.admin",
                "重载 config.yml / loot.yml / party.yml / mobs.yml。",
                "/dungeon reload", "不重载 dungeons.yml（自建副本的配置改完用 /nwd reload）。");

        // ---------------- 创作者 ----------------
        add(DEV, "edit", "/nwd edit", "无", "nwndungeon.dev",
                "打开编辑器面板：不在编辑态时是副本列表（左键进编辑、右键删除、可新建），"
                        + "在编辑态里是标记面板。",
                "/nwd edit", "面板里所有动作都是点图标，不用打字；要指哪个方块就先用准星看着它。");
        add(DEV, "new world create", "/nwd new world create <名字> [void|flat|normal]",
                "名字最多 24 字、不能有点号；generator 默认 void", "nwndungeon.dev",
                "新建一个模板世界并进入编辑态（创造 + 飞行）。",
                "/nwd new world create 熔岩要塞 void",
                "世界名不能用中文，插件会自动给一个 ASCII 名字（存 registry.yml）。");
        add(DEV, "new world save", "/nwd new world save", "无", "nwndungeon.dev",
                "保存当前编辑的模板：落盘、卸载、把编辑世界搬成模板（旧模板自动备份）。",
                "/nwd new world save", "面板里的「保存」图标和这条等价。");
        add(DEV, "list", "/nwd list", "无", "nwndungeon.dev",
                "列出所有自建副本（名字 / 世界名 / 模板体积 / 落点有没有标）。",
                "/nwd list", "体积超过 warn-world-size-mb 会标红提醒。");
        add(DEV, "delete", "/nwd delete <名字>", "名字或世界名", "nwndungeon.dev",
                "删除一个自建副本；模板文件夹挪进 templates/_deleted/ 留退路，不硬删。",
                "/nwd delete 熔岩要塞", "删之前先确认没有人在里面测试。");
        add(DEV, "test", "/nwd test <名字>", "名字或世界名", "nwndungeon.dev",
                "立刻开一局自建副本自测（复制模板 → 建临时世界 → 把你送进去）。",
                "/nwd test 熔岩要塞", "结束后记得 /nwd end，或者等空场/超时自动回收。");
        add(DEV, "end", "/nwd end [名字]", "不写 = 结束所有局", "nwndungeon.dev",
                "结束正在跑的自建副本（把人送回去、卸载并删掉临时世界）。",
                "/nwd end", "临时世界删不掉时会记进待删清单，下次启动再删。");
        add(DEV, "new start", "/nwd new start", "无", "nwndungeon.dev",
                "把「进本落点」记在你脚下（没标就用世界出生点）。",
                "/nwd new start", "面板里的「落点」图标和这条等价。");
        add(DEV, "new checkpoint", "/nwd new checkpoint", "无", "nwndungeon.dev",
                "把脚下记成检查点（同时记下脚下 3×3 的方块签名）。",
                "/nwd new checkpoint",
                "拆掉脚下的方块，这个检查点就失效（方块说了算：照原样补回来就恢复）。");
        add(DEV, "new spawner", "/nwd new spawner <波次号>", "波次号（不写 = 用面板里当前选的）",
                "nwndungeon.dev",
                "把脚下记成「当前关卡 · 第 N 波」的刷怪点；刷什么怪写在 dungeons.yml 的 stages[].waves。",
                "/nwd new spawner 2",
                "先 /nwd new stage <关卡> 选关卡，再用这条；同一坐标重复标 = 覆盖。");
        add(DEV, "new gate", "/nwd new gate [remove]", "remove = 删掉你看着的那道门", "nwndungeon.dev",
                "登记关卡门：结束当前关、开启下一关；带 remove 就是删除。",
                "/nwd new gate  /  /nwd new gate remove",
                "**不写材质** —— 你放的是什么门就存什么门；未清场时插件不许玩家推开它；"
                        + "删门时后面的关卡会自动往前挪一位；同一扇门不能既是关卡门又是出口门。");
        add(DEV, "new exit", "/nwd new exit [remove]", "remove = 清掉出口门标记", "nwndungeon.dev",
                "登记出口门：通关后右键它就能离开副本；带 remove 就是清掉。",
                "/nwd new exit  /  /nwd new exit remove",
                "同样不写材质；木门、铜门、铁门都行；不能和关卡门是同一扇门。");
        add(DEV, "new boss", "/nwd new boss", "无", "nwndungeon.dev",
                "记下首领登场的位置（属性写在 dungeons.yml 的 boss:）。",
                "/nwd new boss", "首领位通常在最后一关（首领区）的中间。");
        add(DEV, "new reward", "/nwd new reward [supply|reward]", "supply（默认）/ reward",
                "nwndungeon.dev",
                "看着一个箱子执行 → 记下位置并打开箱子界面，把要发的东西放进去；关掉界面就写进 loot.yml。",
                "/nwd new reward reward",
                "物品从 GUI 来、权重/概率从 loot.yml 来；空箱子 = 把这一段清掉。");
        add(DEV, "new stage", "/nwd new stage <关卡号>", "关卡号（从 1 开始，不写 = 1）", "nwndungeon.dev",
                "切换「当前关卡」——之后的刷怪点记到那一关。",
                "/nwd new stage 2", "关卡数 = 关卡门数 + 1，最后一关是首领区。");
        add(DEV, "new wave", "/nwd new wave <波次号>", "波次号（从 1 开始）", "nwndungeon.dev",
                "切换「当前波次」。",
                "/nwd new wave 3", "清完一波隔 3 秒自动刷下一波。");
        add(DEV, "new undo", "/nwd new undo", "无", "nwndungeon.dev",
                "撤销最近打的那个标记（刷怪点 / 检查点 / 箱子）。",
                "/nwd new undo", "落点、关卡门、出口门不在撤销范围里，重新标一次覆盖就行。");
        add(DEV, "new list", "/nwd new list", "无", "nwndungeon.dev",
                "看一眼现在标了多少、分了几关。",
                "/nwd new list", "要明细就用面板里的「标记清单」。");
        add(DEV, "new entrance", "/nwd new entrance <create <副本名字>|save|cancel>",
                "create 后面接副本名", "nwndungeon.dev",
                "搭一座入口建筑并存成结构（绑在某个副本上）。",
                "/nwd new entrance create 熔岩要塞",
                "入口要挂在某个副本上；没存过入口结构时 /nwd spawn 贴不出来。");
        add(DEV, "new door", "/nwd new door", "无（**看着一扇门**）", "nwndungeon.dev",
                "搭入口时登记哪一格是门（材质照原样记）。",
                "/nwd new door", "没记的话保存时会在开关周围 3 格自动找一扇门。");
        add(DEV, "new trigger", "/nwd new trigger", "无（**看着按钮/压力板/拉杆**）",
                "nwndungeon.dev",
                "搭入口时登记触发点（进本就是靠按它）。",
                "/nwd new trigger", "按钮、压力板、拉杆都行 —— 不再只认石头按钮。");
        add(DEV, "spawn", "/nwd spawn <名字>", "名字或世界名", "nwndungeon.dev",
                "在你面前贴一座自建副本的入口（门与触发点顺手登记好）。",
                "/nwd spawn 熔岩要塞", "贴出来的入口也能被 /dungeon locate 找到。");
        add(DEV, "doctor", "/nwd doctor", "无", "nwndungeon.dev",
                "自检：建一个临时世界 → 放标记方块 → 保存 → 复制成新世界 → 检查标记方块还在不在，"
                        + "最后把两个世界都清掉。",
                "/nwd doctor", "它验的是「复制世界」这条路能不能走通；控制台也能跑，不需要玩家在线。");
        add(DEV, "reload", "/nwd reload", "无", "nwndungeon.dev",
                "重载 dungeons.yml / registry.yml。",
                "/nwd reload", "编辑态里改配置会被覆盖，先保存再 reload。");
        add(DEV, "help", "/nwd help [页码|子命令]", "页码（数字）或子命令名", "nwndungeon.dev",
                "看创作者命令帮助。",
                "/nwd help new world create", "帮助内容由命令登记表生成，不会和实现漂移。");
    }

    private Help() {
    }

    public static List<Entry> entries(String group) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.group().equals(group)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 子命令名（给 tab 补全）。 */
    public static List<String> keys(String group) {
        List<String> out = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.group().equals(group)) {
                out.add(entry.key());
            }
        }
        return out;
    }

    /** 可补全的用法片段（取每条命令第一个词，去重）。 */
    public static List<String> usageHeads(String group) {
        List<String> out = new ArrayList<>();
        for (Entry entry : entries(group)) {
            String[] parts = entry.usage().split(" ");
            // parts[0] = "/nwd" 或 "/dungeon"
            if (parts.length > 1 && !out.contains(parts[1])) {
                out.add(parts[1]);
            }
        }
        return out;
    }

    /** 打印帮助：args[1] 是页码或子命令名（可空）。 */
    public static void send(CommandSender sender, String root, String group, String[] args) {
        String title = group.equals(PLAYER) ? "玩家" : (group.equals(ADMIN) ? "管理员" : "创作者");
        if (args.length > 1 && !args[1].isBlank() && !isNumber(args[1])) {
            String query = join(args, 1);
            Entry match = find(group, query);
            if (match == null) {
                sender.sendMessage("§c没找到这条命令：「" + query + "」。可用的："
                        + String.join("、", keys(group)));
                return;
            }
            sender.sendMessage("§5[副本]§r §f" + match.usage());
            sender.sendMessage("§7 参数：§f" + match.args());
            sender.sendMessage("§7 权限：§f" + match.perm());
            sender.sendMessage("§7 说明：§f" + match.desc());
            sender.sendMessage("§7 示例：§f" + match.example());
            sender.sendMessage("§e 易错点：§f" + match.pitfall());
            return;
        }
        List<Entry> list = entries(group);
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = args.length > 1 ? Math.max(1, parseInt(args[1], 1)) : 1;
        if (page > pages) {
            page = pages;
        }
        sender.sendMessage("§5[副本]§r §f" + title + "命令§7（第 " + page + "/" + pages
                + " 页，§f" + root + " help <页码|子命令>§7 看详情）");
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(list.size(), from + PAGE_SIZE); i++) {
            Entry entry = list.get(i);
            sender.sendMessage("§e " + entry.usage() + " §7— §f" + entry.desc());
        }
        if (group.equals(ADMIN)) {
            sender.sendMessage("§7 自建副本（创作者）看：§f/nwd help dev");
        }
    }

    private static Entry find(String group, String query) {
        String needle = query.toLowerCase(Locale.ROOT).trim();
        for (Entry entry : entries(group)) {
            if (entry.key().equalsIgnoreCase(needle)) {
                return entry;
            }
        }
        for (Entry entry : entries(group)) {
            if (entry.key().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.usage().toLowerCase(Locale.ROOT).contains(needle)) {
                return entry;
            }
        }
        return null;
    }

    private static String join(String[] args, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private static boolean isNumber(String text) {
        try {
            Integer.parseInt(text.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static final String GROUP_PLAYER = PLAYER;
    public static final String GROUP_ADMIN = ADMIN;
    public static final String GROUP_DEV = DEV;
}
