package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自建副本的**开本与回收**：把模板复制成一个独立的临时世界（{@code nwndinst_<slug>_<n>}）给这一队人打，
 * 结束就卸载并删掉；每个世界里的关卡推进交给 {@link DungeonRun}。
 *
 * <p>为什么是"一局一个世界"而不是"共用一个大世界里切一块地"：M0 实测发现这个版本的存档把**所有维度
 * 装在一个存档里**，复制一份维度文件夹只要几十个文件、通常不到 1 MB，既便宜又能原样带走地形、
 * 方块实体、告示牌、展示框等一切；而"往共享世界里贴结构"只能带走方块与实体，还得把场地先清平。
 */
public final class InstanceWorlds {

    /** 空场多久就回收（毫秒）。 */
    private static final long EMPTY_GRACE_MS = 10_000L;

    private final NWNDungeon plugin;
    private final WorldStore store;
    private final DungeonRegistry registry;
    private final Editor editor;
    private final Map<String, DungeonRun> runs = new LinkedHashMap<>();

    public InstanceWorlds(NWNDungeon plugin, WorldStore store, DungeonRegistry registry, Editor editor) {
        this.plugin = plugin;
        this.store = store;
        this.registry = registry;
        this.editor = editor;
    }

    // ---------------------------------------------------------------- 查

    /** 这个玩家正在打的那一局（不在任何局里返回 null）。 */
    public DungeonRun byPlayer(UUID uuid) {
        for (DungeonRun run : runs.values()) {
            if (run.info(uuid) != null) {
                return run;
            }
        }
        return null;
    }

    public DungeonRun byWorld(World world) {
        return world == null ? null : runs.get(world.getName());
    }

    public boolean isInstanceWorld(World world) {
        return world != null && runs.containsKey(world.getName());
    }

    public int activeCount() {
        return runs.size();
    }

    public List<DungeonRun> all() {
        return new ArrayList<>(runs.values());
    }

    /** 这个名字是不是插件的临时世界（哪怕这一局已经不在名单里了）。 */
    public static boolean looksLikeInstanceWorld(World world) {
        return world != null && world.getName().startsWith(Names.INSTANCE_PREFIX);
    }

    // ---------------------------------------------------------------- 开一局

    /**
     * 开一局：查体力 → 复制模板 → 建世界 → 送人进去 → 跑关卡。
     *
     * <p>复制是**异步**的（纯文件 IO），建世界、送人、刷怪回主线程做。
     */
    public void start(DungeonDef def, List<Player> players, boolean manualHold) {
        if (def == null || players.isEmpty()) {
            return;
        }
        if (!def.worldEnabled) {
            message(players, "§c「" + def.name + "」现在是关闭状态（world.enabled: false）。");
            return;
        }
        File template = store.templateFolder(def.slug);
        if (!WorldStore.looksLikeWorld(template)) {
            message(players, "§c「" + def.name + "」还没有保存过模板，先用 §f/nwmdungeon edit§c 搭一座。");
            return;
        }
        List<String> problems = def.markers.validate(null);
        for (String problem : problems) {
            plugin.getLogger().warning("开本前校验（" + def.name + "）：" + problem);
        }
        int active = 0;
        for (DungeonRun run : runs.values()) {
            if (run.def().name.equals(def.name)) {
                active++;
            }
        }
        if (active >= def.maxConcurrentRuns) {
            message(players, "§c「" + def.name + "」同时最多 " + def.maxConcurrentRuns
                    + " 局，现在满了，稍后再来。");
            return;
        }
        // 体力：谁不够就不给进（够的话建好世界后统一扣）
        int cost = staminaCost(def);
        if (cost > 0) {
            List<String> poor = new ArrayList<>();
            for (Player player : players) {
                if (plugin.stamina().current(player) < cost) {
                    poor.add(player.getName() + "(" + plugin.stamina().current(player) + ")");
                }
            }
            if (!poor.isEmpty()) {
                message(players, "§c体力不足，本次需要 " + cost + " 点：" + String.join("、", poor));
                return;
            }
        }
        int index = nextIndex(def.slug);
        String worldName = Names.instanceWorld(def.slug, index);
        File folder = store.folderOf(worldName);
        if (folder.exists() && !WorldStore.deleteFolder(folder)) {
            message(players, "§c上一次的临时世界删不掉：" + worldName);
            return;
        }
        List<UUID> party = new ArrayList<>();
        for (Player player : players) {
            party.add(player.getUniqueId());
        }
        message(players, "§7正在复制副本世界…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                store.copyFolder(template, folder);
            } catch (Exception e) {
                plugin.getLogger().warning("复制模板失败：" + e);
                Bukkit.getScheduler().runTask(plugin, () -> message(players, "§c复制副本世界失败，看控制台。"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> enter(def, worldName, party, manualHold, cost));
        });
    }

    private int staminaCost(DungeonDef def) {
        if (def.staminaCost <= 0 || plugin.stamina() == null || !plugin.stamina().enabled()) {
            return 0;
        }
        return def.staminaCost;
    }

    private int nextIndex(String slug) {
        for (int i = 1; i < 10_000; i++) {
            String worldName = Names.instanceWorld(slug, i);
            if (!runs.containsKey(worldName) && !store.folderOf(worldName).exists()) {
                return i;
            }
        }
        return 1;
    }

    /** 回主线程：建世界、送人、开跑。 */
    private void enter(DungeonDef def, String worldName, List<UUID> party, boolean manualHold, int cost) {
        World world = store.create(worldName, def.generator, false, true);
        if (world == null) {
            plugin.getLogger().warning("实例世界创建失败：" + worldName);
            messageByIds(party, "§c副本世界创建失败，看控制台。");
            WorldStore.deleteFolder(store.folderOf(worldName));
            return;
        }
        // M2 的核心假设在这里被检查：世界是不是**读到了我们预置的那份拷贝**
        verifyCopy(def, world);

        DungeonRun run = new DungeonRun(plugin, def, world, store, manualHold);
        runs.put(worldName, run);
        for (UUID uuid : party) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (cost > 0 && !plugin.stamina().spend(player, cost)) {
                player.sendMessage("§5[副本]§r §c体力不足，本次没扣（需要 " + cost + " 点）。");
            }
            run.track(player);
            if (def.forceAdventure) {
                player.setGameMode(GameMode.ADVENTURE);
            }
            player.teleport(run.startLocation());
            player.sendMessage("§5[副本]§r 进入「" + def.coloredDisplay() + "§r」，限时 "
                    + def.timeLimitMinutes + " 分钟。");
            player.sendMessage("§7随时可以 §f/dungeon leave§7 离开；死亡会回到最近的检查点。");
            player.sendMessage("§7体力：" + plugin.stamina().describe(player));
        }
        run.begin();
        plugin.getLogger().info("副本开局：" + def.name + " → " + worldName
                + "（" + party.size() + " 人" + (manualHold ? "，管理员自测" : "") + "）");
    }

    /**
     * 检查这一局的世界有没有真的读到模板的拷贝。
     *
     * <p>判据：两边 {@code region/} 的字节数。拷到位就该差不多；差得太多说明 {@code createWorld}
     * 没有加载预置文件夹（那就得退回"先建空世界 → 卸载 → 覆盖 region → 再建"）。只打日志、不拦人。
     */
    private void verifyCopy(DungeonDef def, World world) {
        long expected = WorldStore.sizeOf(new File(store.templateFolder(def.slug), "region"));
        long actual = WorldStore.sizeOf(new File(world.getWorldFolder(), "region"));
        plugin.getLogger().info("实例世界 " + world.getName() + " 的 region 数据 "
                + (actual / 1024) + " KB（模板 " + (expected / 1024) + " KB）");
        if (expected > 0 && actual * 2 < expected) {
            plugin.getLogger().warning("⚠ 实例世界的 region 数据比模板少很多（" + (actual / 1024)
                    + " KB vs " + (expected / 1024) + " KB）—— 预置的模板文件夹可能没有被读进来，"
                    + "检查 createWorld 是否加载了已存在的维度文件夹");
        }
    }

    // ---------------------------------------------------------------- 收一局

    public void release(DungeonRun run, String reason) {
        if (run == null || !runs.containsKey(run.world().getName())) {
            return;
        }
        runs.remove(run.world().getName());
        for (UUID uuid : run.playerIds()) {
            Player player = Bukkit.getPlayer(uuid);
            DungeonRun.PlayerInfo info = run.info(uuid);
            if (player == null || !player.isOnline() || info == null) {
                continue;
            }
            Location back = plugin.returnToEntrance() ? info.entrance : null;
            player.teleport(back != null ? back : Bukkit.getWorlds().get(0).getSpawnLocation());
            if (info.mode != null) {
                player.setGameMode(info.mode);
            }
            player.setAllowFlight(info.allowFlight);
            player.sendMessage("§5[副本]§r " + reason);
        }
        run.cleanup();
        File folder = run.world().getWorldFolder();
        store.unload(run.world(), false);
        if (!WorldStore.deleteFolder(folder)) {
            plugin.addPendingDelete(folder);
            plugin.getLogger().warning("实例世界删不掉，已记进待删清单：" + folder.getName());
        }
        plugin.getLogger().info("副本结束：" + run.def().name + " / " + run.world().getName()
                + "（" + reason + "）");
    }

    /** 玩家主动离开当前这一局（还在副本世界里的其他人不受影响）。 */
    public boolean leave(Player player, String reason) {
        DungeonRun run = byPlayer(player.getUniqueId());
        if (run == null) {
            return false;
        }
        DungeonRun.PlayerInfo info = run.untrack(player.getUniqueId());
        if (info != null) {
            if (info.entrance != null) {
                player.teleport(info.entrance);
            }
            if (info.mode != null) {
                player.setGameMode(info.mode);
            }
            player.setAllowFlight(info.allowFlight);
        }
        player.sendMessage("§5[副本]§r " + reason);
        if (run.playerCount() == 0) {
            release(run, "副本里没人了，这一局已结束。");
        }
        return true;
    }

    /** 管理员强制结束（不写名字 = 全结束）。 */
    public int forceEnd(String dungeonKey) {
        int closed = 0;
        for (DungeonRun run : all()) {
            if (dungeonKey == null || run.def().name.equalsIgnoreCase(dungeonKey)
                    || (run.def().slug != null && run.def().slug.equalsIgnoreCase(dungeonKey))) {
                release(run, "管理员结束了这一局。");
                closed++;
            }
        }
        return closed;
    }

    /** 玩家上线：如果他站在插件的临时世界里却没有对应的这一局，把他送回主城。 */
    public void resume(Player player) {
        if (byPlayer(player.getUniqueId()) != null) {
            return;
        }
        World world = player.getWorld();
        if (world == null || !Names.isOwnWorld(world.getName())) {
            return;
        }
        if (editor.byWorld(world.getName()) != null || editor.entranceByWorld(world.getName()) != null) {
            return;   // 编辑/入口搭建世界由 Editor 自己管
        }
        player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        player.sendMessage("§5[副本]§r §7你不在任何一局副本里了，已把你送回主城。");
    }

    // ---------------------------------------------------------------- 每秒一次

    /**
     * 关门的快循环（每 5 tick 一次）。
     *
     * <p>为什么单独有这么一条：右键那一层已经在事件里拦掉了，但**压力板/红石**是另一条路 ——
     * 门是瞬开的，如果只在每秒的主循环里关一次，玩家踩一下板子就有一秒的窗口能挤过去。
     * 5 tick 一关把窗口压到 250ms 以内，再加上"门口 3 格内的板子/按钮直接拦掉"，就够稳了。
     */
    public void fastTick() {
        for (DungeonRun run : new ArrayList<>(runs.values())) {
            run.enforceGates();
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (DungeonRun run : new ArrayList<>(runs.values())) {
            if (now > run.deadline) {
                release(run, "时间到，副本结束。");
                continue;
            }
            boolean anyoneInside = false;
            for (UUID uuid : run.playerIds()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && player.getWorld().equals(run.world())) {
                    anyoneInside = true;
                    break;
                }
            }
            if (anyoneInside) {
                run.emptySince = 0;
                run.tick();
                continue;
            }
            if (run.emptySince == 0) {
                run.emptySince = now;
            } else if (now - run.emptySince > EMPTY_GRACE_MS) {
                release(run, "副本里没人了，这一局已结束。");
            }
        }
    }

    private void message(List<Player> players, String text) {
        for (Player player : players) {
            player.sendMessage(text);
        }
    }

    private void messageByIds(List<UUID> uuids, String text) {
        for (UUID uuid : uuids) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(text);
            }
        }
    }
}
