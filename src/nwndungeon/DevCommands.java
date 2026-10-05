package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 创作者命令 {@code /nwmdungeon}（别名 {@code /nwd}）。
 *
 * <p>它是"自建副本"工具链的入口，日常用 {@code /nwmdungeon edit} 打开图标面板；
 * 这里保留同名的斜杠命令版本，给习惯打字的人、脚本和控制台用。
 */
public final class DevCommands {

    private final NWNDungeon plugin;
    private final DungeonRegistry registry;
    private final WorldStore store;
    private final Editor editor;
    private final InstanceWorlds instances;
    private final EditorPanel panel;
    private final ChatPrompt prompt;

    public DevCommands(NWNDungeon plugin, DungeonRegistry registry, WorldStore store, Editor editor,
                       InstanceWorlds instances, EditorPanel panel, ChatPrompt prompt) {
        this.plugin = plugin;
        this.registry = registry;
        this.store = store;
        this.editor = editor;
        this.instances = instances;
        this.panel = panel;
        this.prompt = prompt;
    }

    // ---------------------------------------------------------------- 执行

    public boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            Help.send(sender, "/nwd", Help.GROUP_DEV, args);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> Help.send(sender, "/nwd", Help.GROUP_DEV, args);
            case "edit" -> edit(sender);
            case "list" -> list(sender);
            case "new" -> newSomething(sender, args);
            case "delete" -> delete(sender, args);
            case "test" -> test(sender, args);
            case "spawn" -> spawn(sender, args);
            case "end" -> end(sender, args);
            case "doctor", "selftest" -> doctor(sender);
            case "reload" -> reload(sender);
            default -> Help.send(sender, "/nwd", Help.GROUP_DEV, new String[]{"help"});
        }
        return true;
    }

    private boolean requireDev(CommandSender sender) {
        if (!sender.hasPermission("nwd.dev") && !sender.hasPermission("nwndungeon.dev")) {
            sender.sendMessage("§c需要 nwndungeon.dev 权限（创作者工具）。");
            return false;
        }
        return true;
    }

    private boolean requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c这条只能由玩家执行（要知道你站在哪、看着哪）。");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 各子命令

    private void edit(CommandSender sender) {
        if (!requireDev(sender) || !requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (editor.entranceSession(player) != null) {
            panel.openEntranceEditor(player);
            return;
        }
        Editor.Session session = editor.byPlayer(player.getUniqueId());
        if (session != null) {
            panel.openEditor(player);
            return;
        }
        panel.openList(player);
    }

    private void list(CommandSender sender) {
        if (!requireDev(sender)) {
            return;
        }
        if (registry.size() == 0) {
            sender.sendMessage("§7还没有自建副本。用 §f/nwmdungeon edit§7 打开面板新建一个。");
            return;
        }
        sender.sendMessage("§5[副本]§r 自建副本（" + registry.size() + " 个）：");
        for (DungeonDef def : registry.all()) {
            File template = store.templateFolder(def.slug);
            boolean ready = WorldStore.looksLikeWorld(template);
            sender.sendMessage("§7 · §f" + def.name + " §8[" + Names.templateWorld(def.slug) + "]§7 "
                    + (ready ? "模板 " + WorldStore.describeSize(template) : "§e无模板")
                    + " · 落点 " + (def.markers.start == null ? "未标" : "已标")
                    + " · 限时 " + def.timeLimitMinutes + " 分"
                    + " · 生成器 " + def.generator);
        }
    }

    private void newSomething(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法：/nwmdungeon new world create <名字> [void|flat|normal]");
            return;
        }
        String what = args[1].toLowerCase(Locale.ROOT);
        if (what.equals("world")) {
            if (args.length >= 3 && args[2].equalsIgnoreCase("create")) {
                newWorld(sender, args);
                return;
            }
            if (args.length >= 3 && args[2].equalsIgnoreCase("save")) {
                saveWorld(sender);
                return;
            }
            sender.sendMessage("§c用法：/nwmdungeon new world <create|save> …");
            return;
        }
        if (what.equals("entrance")) {
            entrance(sender, args);
            return;
        }
        if (what.equals("door") || what.equals("trigger")) {
            entranceMark(sender, what);
            return;
        }
        // 其余都是标记类命令：斜杠版本，等价于点面板上的图标
        mark(sender, what, args);
    }

    // ---------------------------------------------------------------- 入口建筑

    /** {@code new entrance create <副本名字> / save / cancel}。 */
    private void entrance(CommandSender sender, String[] args) {
        if (!requireDev(sender) || !requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "create" -> {
                if (args.length < 4) {
                    player.sendMessage("§c用法：/nwmdungeon new entrance create <副本名字>");
                    return;
                }
                DungeonDef def = registry.find(args[3]);
                if (def == null) {
                    player.sendMessage("§c没找到这个副本。现有的：" + namesOrNone() + "（入口要挂在某个副本上）");
                    return;
                }
                if (editor.entranceSession(player) != null) {
                    player.sendMessage("§c你已经在搭一个入口了，先 §f/nwmdungeon new entrance save§c 或放弃。");
                    return;
                }
                if (editor.byPlayer(player.getUniqueId()) != null) {
                    player.sendMessage("§c你正在编辑副本本体，先保存或放弃再来搭入口。");
                    return;
                }
                String error = editor.openEntrance(player, def);
                if (error != null) {
                    player.sendMessage("§c" + error);
                    return;
                }
                panel.openEntranceEditor(player);
            }
            case "save" -> {
                Editor.EntranceSession session = editor.entranceSession(player);
                if (session == null) {
                    player.sendMessage("§c你不在搭入口。先 §f/nwmdungeon new entrance create <副本名字>§c。");
                    return;
                }
                editor.saveEntrance(player, session);
            }
            case "cancel" -> {
                Editor.EntranceSession session = editor.entranceSession(player);
                if (session == null) {
                    player.sendMessage("§c你不在搭入口。");
                    return;
                }
                editor.cancelEntrance(player, session);
            }
            default -> player.sendMessage("§c用法：/nwmdungeon new entrance <create <副本名字>|save|cancel>");
        }
    }

    /** 看着门 / 看着开关登记入口的两个关键方块。 */
    private void entranceMark(CommandSender sender, String what) {
        if (!requireDev(sender) || !requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        Editor.EntranceSession session = editor.entranceSession(player);
        if (session == null) {
            player.sendMessage("§c你不在搭入口。先 §f/nwmdungeon new entrance create <副本名字>§c。");
            return;
        }
        if (what.equals("door")) {
            Block door = editor.targetedDoor(player);
            if (door == null) {
                player.sendMessage("§c先用准星看着一扇门。");
                return;
            }
            editor.markEntranceDoor(player, session, door);
        } else {
            Block trigger = editor.targetedTrigger(player);
            if (trigger == null) {
                player.sendMessage("§c先用准星看着一个按钮 / 压力板 / 拉杆。");
                return;
            }
            editor.markEntranceTrigger(player, session, trigger);
        }
    }

    /**
     * 标记类命令（{@code new start / checkpoint / spawner / gate / exit / boss / reward / stage / wave / undo / list}）。
     *
     * <p>和面板图标一一对应；区别只是"用命令说"还是"点图标"。需要指方块的（关卡门 / 出口门 / 箱子）
     * 一律**看你准星指着的那一格**，而且**不写材质参数** —— 你放的是什么门就存什么门。
     */
    private void mark(CommandSender sender, String what, String[] args) {
        if (!requireDev(sender) || !requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        Editor.Session session = editor.byPlayer(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c你不在编辑态里。先 §f/nwmdungeon new world create <名字>§c"
                    + " 或 §f/nwmdungeon edit§c。");
            return;
        }
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            player.sendMessage("§c这个副本的配置已经不在了。");
            return;
        }
        switch (what) {
            case "start" -> editor.markStart(player, session);
            case "checkpoint" -> editor.markCheckpoint(player, session);
            case "spawner" -> editor.markSpawner(player, session,
                    args.length >= 3 ? parseInt(args[2], session.wave) : session.wave);
            case "gate" -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("remove")) {
                    editor.removeGate(player, session, editor.targetedDoor(player));
                    return;
                }
                Block door = editor.targetedDoor(player);
                if (door == null) {
                    player.sendMessage("§c先用准星看着一扇门。");
                    return;
                }
                editor.markGate(player, session, door);
            }
            case "exit" -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("remove")) {
                    editor.clearExitDoor(player, session);
                    return;
                }
                Block door = editor.targetedDoor(player);
                if (door == null) {
                    player.sendMessage("§c先用准星看着一扇门。");
                    return;
                }
                editor.markExitDoor(player, session, door);
            }
            case "boss" -> editor.markBoss(player, session);
            case "reward", "chest" -> {
                Block chest = editor.targetedChest(player);
                if (chest == null) {
                    player.sendMessage("§c先用准星看着一个箱子。");
                    return;
                }
                String kind = args.length >= 3 && args[2].equalsIgnoreCase("reward") ? "reward" : "supply";
                editor.markChest(player, session, chest, kind);
                panel.openRewardEditor(player, def.name, kind, Markers.of(chest.getLocation()));
            }
            case "stage" -> {
                session.stage = editor.clampStage(def,
                        parseInt(args.length >= 3 ? args[2] : "1", 1) - 1);
                session.wave = 1;
                player.sendMessage("§a当前关卡 = 第 " + (session.stage + 1) + " 关（共 "
                        + def.markers.stageCount() + " 关）。");
            }
            case "wave" -> {
                session.wave = Math.max(1, parseInt(args.length >= 3 ? args[2] : "1", 1));
                player.sendMessage("§a当前波次 = 第 " + session.wave + " 波。");
            }
            case "undo" -> {
                String undone = def.markers.undoLast();
                registry.save();
                player.sendMessage(undone == null ? "§7没有可撤销的标记。" : "§a已撤销：" + undone);
            }
            case "list" -> player.sendMessage("§5[编辑器]§r " + def.name + "：共 " + def.markers.total()
                    + " 个标记，" + def.markers.stageCount() + " 关。用面板的「标记清单」看明细。");
            default -> player.sendMessage("§c不认识这个标记命令。可用的：start / checkpoint / "
                    + "spawner <波次> / gate / exit / boss / reward [supply|reward] / "
                    + "stage <关卡> / wave <波次> / undo / list");
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void newWorld(CommandSender sender, String[] args) {
        if (!requireDev(sender) || !requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (editor.byPlayer(player.getUniqueId()) != null) {
            player.sendMessage("§c你已经在编辑态里了，先 §f/nwmdungeon new world save§c 或放弃。");
            return;
        }
        if (args.length < 4) {
            player.sendMessage("§c用法：/nwmdungeon new world create <名字> [void|flat|normal]");
            return;
        }
        String name = args[3];
        String invalid = DungeonRegistry.validateName(name);
        if (invalid != null) {
            player.sendMessage("§c" + invalid);
            return;
        }
        String generator = args.length >= 5 ? DungeonDef.normalizeGenerator(args[4]) : "void";
        DungeonDef def = registry.findByName(name);
        if (def == null) {
            def = registry.create(name);
        }
        if (def == null) {
            player.sendMessage("§c建不了这个副本（名字重复？）。");
            return;
        }
        File template = store.templateFolder(def.slug);
        boolean fromTemplate = WorldStore.looksLikeWorld(template);
        if (fromTemplate) {
            player.sendMessage("§7已有模板，这次是把它复制出来继续改。");
        }
        String error = editor.open(player, def, generator, fromTemplate);
        if (error != null) {
            player.sendMessage("§c" + error);
            return;
        }
        player.sendMessage("§a编辑态已就绪（世界名 §f" + Names.templateWorld(def.slug) + "§a）。"
                + "§7用 §f/nwmdungeon new world save§7 保存，或 §f/nwmdungeon edit§7 打开面板。");
    }

    private void saveWorld(CommandSender sender) {
        if (!requireDev(sender) || !requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        Editor.Session session = editor.byPlayer(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c你不在编辑态里。");
            return;
        }
        editor.save(player, session);
    }

    private void delete(CommandSender sender, String[] args) {
        if (!requireDev(sender)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法：/nwmdungeon delete <名字>");
            return;
        }
        DungeonDef def = registry.find(args[1]);
        if (def == null) {
            sender.sendMessage("§c没找到这个副本。现有的：" + namesOrNone());
            return;
        }
        String joined = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        if (!joined.equals(def.name) && !joined.equalsIgnoreCase(def.slug)) {
            sender.sendMessage("§c要确认的话请把名字打全：§f/nwmdungeon delete " + def.name);
            return;
        }
        registry.delete(def.name, store);
        sender.sendMessage("§a已删除「" + def.name + "§a」（模板挪进 templates/_deleted/，没硬删）。");
    }

    private void test(CommandSender sender, String[] args) {
        if (!requireDev(sender)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法：/nwmdungeon test <名字>");
            return;
        }
        DungeonDef def = registry.find(args[1]);
        if (def == null) {
            sender.sendMessage("§c没找到这个副本。现有的：" + namesOrNone());
            return;
        }
        List<Player> party = new ArrayList<>();
        if (sender instanceof Player player) {
            party.add(player);
        } else {
            // 控制台：把在线且有 dev 权限的人拉进去（方便"我在这儿看日志"）
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("nwd.dev") || online.hasPermission("nwndungeon.dev")) {
                    party.add(online);
                }
            }
        }
        if (party.isEmpty()) {
            sender.sendMessage("§c没有可以进本的人。");
            return;
        }
        instances.start(def, party, true);
        sender.sendMessage("§7正在开「" + def.name + "」的测试局…");
    }

    /** 在你面前贴一座自建副本的入口（门 + 触发器都顺手登记好）。 */
    private void spawn(CommandSender sender, String[] args) {
        if (!requireDev(sender) || !requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (args.length < 2) {
            player.sendMessage("§c用法：/nwmdungeon spawn <名字>");
            return;
        }
        DungeonDef def = registry.find(args[1]);
        if (def == null) {
            player.sendMessage("§c没找到这个副本。现有的：" + namesOrNone());
            return;
        }
        Location front = player.getLocation().clone();
        org.bukkit.util.Vector direction = front.getDirection().setY(0);
        if (direction.lengthSquared() > 0) {
            direction.normalize().multiply(3);
        }
        Location at = front.add(direction);
        if (plugin.placeEntrance(def, at)) {
            player.sendMessage("§a已在你面前贴出「" + def.coloredDisplay()
                    + "§a」的入口，门与触发点都登记好了（去踩/按它就能进本）。");
        } else {
            player.sendMessage("§c贴不出来：这个副本还没存过入口结构，先 §f/nwmdungeon new entrance create "
                    + def.name + "§c 搭一座。");
        }
    }

    private void end(CommandSender sender, String[] args) {
        if (!requireDev(sender)) {
            return;
        }
        String key = args.length >= 2 ? args[1] : null;
        int closed = instances.forceEnd(key);
        sender.sendMessage(closed == 0 ? "§7没有正在跑的局。" : "§a已结束 " + closed + " 局。");
    }
    private void reload(CommandSender sender) {
        if (!requireDev(sender)) {
            return;
        }
        registry.load();
        sender.sendMessage("§a已重载 dungeons.yml / registry.yml：" + registry.size() + " 个自建副本。");
    }

    /** 自检：把"复制世界"路线的几个未知项跑一遍（控制台也能跑，不需要玩家）。 */
    private void doctor(CommandSender sender) {
        if (!requireDev(sender)) {
            return;
        }
        plugin.runSelfTest(sender);
    }

    private String namesOrNone() {
        List<String> names = registry.names();
        return names.isEmpty() ? "（无）" : String.join("、", names);
    }

    // ---------------------------------------------------------------- 补全

    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String key : List.of("help", "edit", "list", "new", "delete", "test", "end", "doctor", "reload")) {
                if (key.startsWith(prefix)) {
                    out.add(key);
                }
            }
            return out;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (first.equals("new")) {
                return prefixMatch(args[1], List.of("world", "entrance", "door", "trigger",
                        "start", "checkpoint", "spawner", "gate", "exit", "boss", "reward",
                        "stage", "wave", "undo", "list"));
            }
            if (first.equals("delete") || first.equals("test") || first.equals("end")
                    || first.equals("spawn")) {
                return prefixMatch(args[1], registry.names());
            }
            if (first.equals("help")) {
                return prefixMatch(args[1], Help.keys(Help.GROUP_DEV));
            }
            return out;
        }
        if (first.equals("new") && args.length == 3 && args[1].equalsIgnoreCase("world")) {
            return prefixMatch(args[2], List.of("create", "save"));
        }
        if (first.equals("new") && args.length == 4 && args[1].equalsIgnoreCase("world")
                && args[2].equalsIgnoreCase("create")) {
            return prefixMatch(args[3], registry.names());
        }
        if (first.equals("new") && args.length == 5 && args[1].equalsIgnoreCase("world")
                && args[2].equalsIgnoreCase("create")) {
            return prefixMatch(args[4], List.of("void", "flat", "normal"));
        }
        if (first.equals("new") && args.length == 3 && args[1].equalsIgnoreCase("reward")) {
            return prefixMatch(args[2], List.of("supply", "reward"));
        }
        if (first.equals("new") && args.length == 3 && args[1].equalsIgnoreCase("entrance")) {
            return prefixMatch(args[2], List.of("create", "save", "cancel"));
        }
        if (first.equals("new") && args.length == 4 && args[1].equalsIgnoreCase("entrance")
                && args[2].equalsIgnoreCase("create")) {
            return prefixMatch(args[3], registry.names());
        }
        return out;
    }

    private List<String> prefixMatch(String prefix, List<String> candidates) {
        List<String> out = new ArrayList<>();
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
