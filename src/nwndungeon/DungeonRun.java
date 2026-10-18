package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 一局自建副本的**关卡运行时**：把管理员标好的标记 + {@code dungeons.yml} 里的阵容，跑成
 * "进关 → 分波刷怪 → 清完开门 → 进下一关 → … → 首领确认死亡 → 结算 → 收掉"。
 *
 * <p>几个关键设计（都是计划书 §7 的落地）：
 * <ul>
 *   <li><b>分段</b>：关卡数 = 关卡门数 + 1，第 {@code gates.get(i)} 道门是"第 i 关的出口门"，
 *       最后一关是首领区（没有关卡门，用出口门）。</li>
 *   <li><b>清完当前关才解锁下一关，玩家接近下一关才开始刷怪</b>，不提前启动后面的房间。</li>
 *   <li><b>关卡门由插件控制开合</b>：没清场时每 tick 强制关上 + 取消玩家的右键
 *       （只靠"每秒关一次"拦不住 —— 门是瞬开的，那一秒足够挤过去）。</li>
 *   <li><b>首领必须"确认击杀"</b>：沿用 v1.4.13 的判定（收到死亡事件才算），找不回就原地补刷（上限 3 次），
 *       绝不用"名单空了"代替。</li>
 *   <li><b>检查点认方块签名</b>：脚下的方块被拆过就不再可用（现场比对，不记额外状态）。</li>
 * </ul>
 */
public final class DungeonRun {

    /** 一波清完到下一波刷出的间隔（tick）；与内置副本保持一致。 */
    private static final long WAVE_DELAY_TICKS = 60L;
    /** 在"已加载的区块里"连续查不到这么多秒才允许把这支怪从名单剔除（v1.4.13）。 */
    /** 走到离本关第一个刷怪点多近就算到位（格）。 */
    private static final double STAGE_START_RADIUS = 24.0;
    /** 名单空着超过这么久还没推进 → 判定卡死，强制推进（v1.4.13）。 */
    private static final long STUCK_ROOM_MS = 10_000L;
    /** 等下一波远超 3 秒（刷怪任务没跑成）→ 强制重刷（v1.4.13）。 */
    private static final long STUCK_WAVE_MS = 15_000L;
    /** 踩到检查点的判定半径。 */
    private static final double CHECKPOINT_RADIUS = 2.5;

    /** 一个玩家的本局记录。 */
    public static final class PlayerInfo {
        public final Location entrance;
        public final org.bukkit.GameMode mode;
        public final boolean allowFlight;
        public int deaths;
        /** 最近踩到的检查点（位置）。 */
        public Location checkpoint;

        PlayerInfo(Location entrance, org.bukkit.GameMode mode, boolean allowFlight) {
            this.entrance = entrance;
            this.mode = mode;
            this.allowFlight = allowFlight;
        }
    }

    /** 一关的运行状态。 */
    public static final class Stage {
        public final int index;
        /** 每波要刷的怪（顺序保留）。 */
        public final List<List<EntityType>> waves = new ArrayList<>();
        /** 与 {@link #waves} 平行：每只怪用的 {@code mobs.yml} 模板名（null = 直接用 EntityType）。 */
        public final List<List<String>> templates = new ArrayList<>();
        /** 与 {@link #waves} 平行：这一波的刷怪点。 */
        public final List<List<Location>> spots = new ArrayList<>();
        public boolean bossStage;
        /** 这一关是否已经开始（走进这一关才刷怪，见 startStagesNearPlayers）。 */
        public boolean started;
        public boolean cleared;
        public int waveIndex;
        public boolean wavePending;
        public int pendingWave = -1;
        public long emptySince;
        public final List<UUID> mobs = new ArrayList<>();
        public final Map<UUID, Location> lastSeen = new HashMap<>();
        public final Map<UUID, Integer> missingSeconds = new HashMap<>();
        public UUID bossId;
        public LivingEntity bossEntity;
        public RuntimeException lastBossFailure;
        public final SpawnRecovery bossRecovery = new SpawnRecovery();
        public final Map<UUID, LivingEntity> entities = new HashMap<>();
        public final Map<UUID, MobRecovery> mobRecovery = new HashMap<>();
        public org.bukkit.scheduler.BukkitTask waveTask;
        public boolean spawning;
        public boolean bossKilled;
        public int bossRespawns;
        /** 结算这一关时要开的门；null = 没门（例如没标关卡门的单关副本）。 */
        public Location gate;

        Stage(int index) {
            this.index = index;
        }

        public int waveCount() {
            return waves.size();
        }
    }

    /** 结算界面的占位 holder（拦掉点击）。 */
    public static final class SummaryHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final NWNDungeon plugin;
    private final DungeonDef def;
    private final World world;
    private final WorldStore store;
    private final Random random = new Random();
    private final List<Stage> stages = new ArrayList<>();
    private final Map<UUID, PlayerInfo> players = new LinkedHashMap<>();
    private final Set<UUID> rewarded = new HashSet<>();

    public final boolean manualHold;
    public final long startedAt = System.currentTimeMillis();
    public long deadline;
    public int currentStage;
    public int kills;
    public boolean completed;
    private boolean closed;
    private String failure;
    private final Map<UUID, Integer> staminaPaid = new HashMap<>();
    public long emptySince;
    private org.bukkit.boss.BossBar bar;

    public DungeonRun(NWNDungeon plugin, DungeonDef def, World world, WorldStore store, boolean manualHold) {
        this.plugin = plugin;
        this.def = def;
        this.world = world;
        this.store = store;
        this.manualHold = manualHold;
    }

    public World world() {
        return world;
    }

    public DungeonDef def() {
        return def;
    }

    public boolean hasBoss(UUID uuid) { return stages.stream().anyMatch(stage -> uuid.equals(stage.bossId)); }

    public String failure() { return failure; }

    public void paid(UUID uuid, int amount) {
        if (amount > 0) { staminaPaid.put(uuid, amount); }
    }

    public void fail(String reason) {
        fail(reason, null);
    }

    private void fail(String reason, Throwable cause) {
        if (failure == null && !closed && !completed) {
            failure = reason;
            plugin.getLogger().severe("副本「" + def.name + "」异常终止：" + reason);
            FailureDiagnostics.record(plugin, "自建副本终止 名称=" + def.name + " 世界="
                    + (world == null ? "未创建" : world.getName()), reason, cause);
        }
    }

    public int playerCount() {
        return players.size();
    }

    public Set<UUID> playerIds() {
        return new LinkedHashSet<>(players.keySet());
    }

    public PlayerInfo info(UUID uuid) {
        return players.get(uuid);
    }

    public PlayerInfo track(Player player) {
        PlayerInfo info = new PlayerInfo(player.getLocation().clone(), player.getGameMode(),
                player.getAllowFlight());
        players.put(player.getUniqueId(), info);
        if (bar != null) {
            bar.addPlayer(player);
        }
        return info;
    }

    public PlayerInfo untrack(UUID uuid) {
        PlayerInfo info = players.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (bar != null && player != null) {
            bar.removePlayer(player);
        }
        return info;
    }

    // ---------------------------------------------------------------- 开局

    /** 建关卡表、刷第 1 关第 1 波、挂血条。 */
    // ---------------------------------------------------------------- 区块票据

    /**
     * 以落点为中心最多钉住多少区块半径（12 区块 = 192 格）。
     *
     * <p>自建副本的标记是**人手放的**，放歪到几千格外也不奇怪 ——
     * 那种情况不该把整片区块钉住，而是夹住 + 提醒管理员副本太大了。
     */
    private static final int MAX_TICKET_RADIUS_CHUNKS = 12;

    /** 是否持有本局的区块票据。 */
    private boolean ticketed;
    private final Set<Long> chunkTickets = new LinkedHashSet<>();

    /**
     * 算出"这一局要把哪些区块钉住"。
     *
     * <p>返回 {@code {minChunkX, minChunkZ, maxChunkX, maxChunkZ}}；没有任何标记时返回 null。
     * 这是**纯计算**（不碰服务器），所以能离线自检。
     */
    public static int[] ticketBounds(DungeonDef def) {
        if (def == null || def.markers == null) {
            return null;
        }
        Markers m = def.markers;
        List<int[]> points = new ArrayList<>();
        if (m.start != null) {
            points.add(m.start);
        }
        if (m.boss != null) {
            points.add(m.boss);
        }
        if (m.exitDoor != null) {
            points.add(m.exitDoor);
        }
        points.addAll(m.gates);
        for (Markers.Spawner spawner : m.spawners) {
            points.add(spawner.pos);
        }
        for (Markers.Checkpoint checkpoint : m.checkpoints) {
            points.add(checkpoint.pos);
        }
        for (Markers.Chest chest : m.chests) {
            points.add(chest.pos);
        }
        if (points.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] p : points) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        // 与内置副本一致的 16 格外扩（怪会跑、房间有墙）
        minX -= 16;
        maxX += 16;
        minZ -= 16;
        maxZ += 16;
        // 以落点（没标就用中心）为圆心夹住半径
        int centerX = m.start != null ? m.start[0] : (minX + maxX) / 2;
        int centerZ = m.start != null ? m.start[2] : (minZ + maxZ) / 2;
        int limit = MAX_TICKET_RADIUS_CHUNKS * 16;
        minX = Math.max(minX, centerX - limit);
        maxX = Math.min(maxX, centerX + limit);
        minZ = Math.max(minZ, centerZ - limit);
        maxZ = Math.min(maxZ, centerZ + limit);
        return new int[]{minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4};
    }

    /**
     * 把这一局用到的区块**钉住**，直到本局结束。
     *
     * <p>M8 按落点与已启动波次的实际位置逐点保加载，不预先加载整张副本。
     * 区块加载、实体激活和自然消失是不同机制：这里仅负责区块生命周期。
     */
    private void keepChunksLoaded() {
        keepAt(startLocation());
    }

    /** 按实际使用的点钉区块，不再把远处的标记截断到落点周围 192 格。 */
    private void keepAt(Location point) {
        int x = point.getBlockX() >> 4;
        int z = point.getBlockZ() >> 4;
        for (int cx = x - 1; cx <= x + 1; cx++) {
            for (int cz = z - 1; cz <= z + 1; cz++) {
                long key = ((long) cx << 32) | (cz & 0xffffffffL);
                if (chunkTickets.contains(key)) { continue; }
                if (chunkTickets.size() >= 512) {
                    throw new MobSpawner.Failure("本局刷怪点过于分散，需常驻的区块超过 512 个");
                }
                world.addPluginChunkTicket(cx, cz, plugin);
                chunkTickets.add(key);
                ticketed = true;
            }
        }
    }

    /** 撤掉这一局的区块票据（一局结束时调用）。 */
    private void releaseChunks() {
        if (!ticketed || world == null) {
            return;
        }
        for (long key : chunkTickets) {
            world.removePluginChunkTicket((int) (key >> 32), (int) key, plugin);
        }
        chunkTickets.clear();
        ticketed = false;
    }

    public void begin() {
        // 先把这一局用到的区块钉住：玩家走得慢也不会让远处的怪被卸载（"刷没"的根源）
        keepChunksLoaded();
        Markers markers = def.markers;
        int stageCount = markers.stageCount();
        for (int index = 0; index < stageCount; index++) {
            Stage stage = new Stage(index);
            stage.bossStage = markers.isBossStage(index);
            stage.gate = index < markers.gates.size()
                    ? toLocation(markers.gates.get(index))
                    : (markers.exitDoor != null ? toLocation(markers.exitDoor) : null);
            buildWaves(stage, markers);
            stages.add(stage);
        }
        bar = Bukkit.createBossBar("§5副本", org.bukkit.boss.BarColor.PURPLE,
                org.bukkit.boss.BarStyle.SEGMENTED_20);
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                bar.addPlayer(player);
            }
        }
        deadline = startedAt + def.timeLimitMinutes * 60_000L;
        currentStage = 0;
        // 第一关就"没得打"（没标刷怪点）的话，直接按清场推进
        Stage first = stages.get(0);
        if (first.waveCount() == 0) {
            plugin.getLogger().warning("「" + def.name + "」第 1 关没有刷怪点，直接算清场");
            first.cleared = true;
            openGate(first);
            advanceToNext(first);
        } else {
            first.started = true;   // 玩家就在落点，第一关立刻开打
            spawnWave(first, 0);
        }
        announce("§5[副本]§r 进入「" + def.coloredDisplay() + "§r」，共 " + stages.size()
                + " 关，限时 " + def.timeLimitMinutes + " 分钟。");
        refreshBar();
    }

    /** 把关卡与阵容、刷怪点配对起来。 */
    private void buildWaves(Stage stage, Markers markers) {
        List<String> specs = new ArrayList<>();
        DungeonDef.Stage configured = def.stage(stage.index);
        if (stage.bossStage) {
            specs.addAll(def.boss.waves);
            specs.add(def.boss.guards == null ? "" : def.boss.guards);   // 最后一波随首领登场
        } else if (configured != null) {
            specs.addAll(configured.waves);
        }
        for (int wave = 1; wave <= specs.size(); wave++) {
            List<Location> spots = new ArrayList<>();
            for (Markers.Spawner spawner : markers.spawnersAt(stage.index, wave)) {
                spots.add(toLocation(spawner.pos));
            }
            if (spots.isEmpty() && !stage.bossStage) {
                throw new MobSpawner.Failure("第 " + (stage.index + 1) + " 关第 " + wave + " 波没有刷怪点");
            }
            List<EntityType> types = new ArrayList<>();
            List<String> templates = new ArrayList<>();
            parseWave(specs.get(wave - 1), types, templates);
            if (types.isEmpty() && !(stage.bossStage && wave == specs.size())) {
                throw new MobSpawner.Failure("第 " + (stage.index + 1) + " 关第 " + wave + " 波阵容为空或无效");
            }
            stage.waves.add(types);
            stage.templates.add(templates);
            stage.spots.add(spots);
        }
        if (stage.waves.isEmpty() && !stage.bossStage) {
            // 没配阵容：按刷怪点数量刷默认僵尸，至少让这一关能打
            List<Location> spots = new ArrayList<>();
            for (Markers.Spawner spawner : markers.spawnersAt(stage.index, 0)) {
                spots.add(toLocation(spawner.pos));
            }
            if (!spots.isEmpty()) {
                List<EntityType> types = new ArrayList<>();
                List<String> templates = new ArrayList<>();
                for (int i = 0; i < spots.size(); i++) {
                    types.add(EntityType.ZOMBIE);
                    templates.add(null);
                }
                stage.waves.add(types);
                stage.templates.add(templates);
                stage.spots.add(spots);
                plugin.getLogger().warning("「" + def.name + "」第 " + (stage.index + 1)
                        + " 关没配 stages[].waves，暂时刷默认僵尸（" + spots.size() + " 只）");
            }
        }
        if (stage.waves.isEmpty() && stage.bossStage) {
            // 首领区：没配小怪波也至少要有一个"首领波"
            stage.waves.add(new ArrayList<>());
            stage.templates.add(new ArrayList<>());
            stage.spots.add(new ArrayList<>());
        }
        if (stage.waves.isEmpty()) {
            throw new MobSpawner.Failure("第 " + (stage.index + 1) + " 关没有阵容或刷怪点");
        }
    }

    /** 把 {@code "ZOMBIE:3,LAVA_BRUTE:2"} 展开成实体类型表 + 模板名表（解析本身在 WaveSpec 里，可离线测）。 */
    private void parseWave(String spec, List<EntityType> types, List<String> templates) {
        List<WaveSpec.Item> parsed = WaveSpec.parse(spec);
        long tokens = spec == null ? 0 : java.util.Arrays.stream(spec.split(","))
                .filter(token -> !token.isBlank()).count();
        if (parsed.size() != tokens) { throw new MobSpawner.Failure("阵容格式错误：" + spec); }
        for (WaveSpec.Item item : parsed) {
            String name = item.name();
            MobTemplate template = plugin.mobTemplate(name.toLowerCase(Locale.ROOT));
            EntityType type = template != null ? template.type() : entityType(name);
            if (!MobSpawner.supported(type) || item.count() > MobSpawner.MAX_WAVE_SIZE
                    || types.size() + item.count() > MobSpawner.MAX_WAVE_SIZE) {
                throw new MobSpawner.Failure("无效的怪物或数量：" + name + ":" + item.count());
            }
            for (int i = 0; i < item.count(); i++) {
                types.add(type);
                templates.add(template == null ? null : template.id());
            }
        }
    }

    private EntityType entityType(String name) {
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private Location toLocation(int[] pos) {
        return new Location(world, pos[0] + 0.5, pos[1], pos[2] + 0.5);
    }

    // ---------------------------------------------------------------- 刷怪

    /** 刷某一关的第 waveIndex 波（0 基）。 */
    private void spawnWave(Stage stage, int waveIndex) {
        if (closed || failure != null || stage == null || stage.cleared
                || waveIndex < 0 || waveIndex >= stage.waveCount()) {
            return;
        }
        stage.waveIndex = waveIndex;
        stage.wavePending = false;
        stage.pendingWave = -1;
        stage.emptySince = 0;
        stage.mobs.clear();
        stage.lastSeen.clear();
        stage.missingSeconds.clear();
        stage.entities.clear();
        stage.mobRecovery.clear();
        stage.spawning = true;

        try {
            List<EntityType> types = stage.waves.get(waveIndex);
            List<String> templates = stage.templates.get(waveIndex);
            List<Location> spots = stage.spots.get(waveIndex);
            boolean bossWave = stage.bossStage && waveIndex == stage.waveCount() - 1;

            if (types.isEmpty() && !bossWave) {
                advanceWave(stage);
                return;
            }
            for (int i = 0; i < types.size(); i++) {
                Location spot = spots.isEmpty()
                        ? fallbackSpot(stage)
                        : spots.get(i % spots.size());
                keepAt(spot);
                String templateId = templates.get(i);
                MobTemplate template = templateId == null ? null : plugin.mobTemplate(templateId);
                java.util.function.Consumer<LivingEntity> configure = living -> {
                    if (template != null) {
                        template.apply(living);
                        plugin.tagMob(living, template.id());
                    }
                };
                LivingEntity entity = MobSpawner.spawn(world, spot, types.get(i), configure);
                stage.mobs.add(entity.getUniqueId());
                stage.entities.put(entity.getUniqueId(), entity);
                stage.mobRecovery.put(entity.getUniqueId(), new MobRecovery(entity, configure, point -> true));
                stage.lastSeen.put(entity.getUniqueId(), entity.getLocation().clone());
            }
            if (bossWave) {
                try { spawnBoss(stage); }
                catch (MobSpawner.Failure e) {
                    stage.bossKilled = false;
                    stage.lastBossFailure = e;
                    plugin.getLogger().warning("首领首次生成失败，进入恢复等待：" + e.getMessage());
                    FailureDiagnostics.record(plugin, "首领首次生成失败 副本=" + def.name + " 关=" + (stage.index + 1), e.getMessage(), e);
                }
            }
            announceWave(stage, waveIndex, false);
            refreshBar();
        } catch (RuntimeException e) {
            fail("第 " + (stage.index + 1) + " 关第 " + (waveIndex + 1) + " 波生成失败：" + e.getMessage(), e);
        } finally {
            stage.spawning = false;
        }
    }

    /** 首领区没标刷怪点时，护卫就围在首领位/第一个点旁边。 */
    private Location fallbackSpot(Stage stage) {
        Location base = def.markers.boss != null ? toLocation(def.markers.boss)
                : (stage.spots.isEmpty() || stage.spots.get(0).isEmpty()
                ? world.getSpawnLocation() : stage.spots.get(0).get(0));
        double angle = random.nextDouble() * Math.PI * 2;
        double radius = 3.0 + random.nextDouble() * 3.0;
        return base.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    /** 刷首领（位置来自标记；属性来自 dungeons.yml 的 boss:）。 */
    private void spawnBoss(Stage stage) {
        Location center = def.markers.boss != null ? toLocation(def.markers.boss) : fallbackSpot(stage);
        keepAt(center);
        // 首领标记点底下可能是洞 / 悬空（自建副本里标记是手放的）→ 先找一块站得住的地。
        // 否则它一出来就掉出世界：玩家看到"boss 自己消失了"，插件却收到死亡事件直接结算。
        MobTemplate template = plugin.mobTemplate(def.boss.type.toLowerCase(Locale.ROOT));
        EntityType type = template != null ? template.type() : entityType(def.boss.type);
        LivingEntity boss = MobSpawner.spawn(world, center, type, entity -> configureBoss(entity, template));
        stage.bossEntity = boss;
        stage.bossId = boss.getUniqueId();
        stage.bossKilled = false;
        stage.lastBossFailure = null;
        stage.mobs.add(boss.getUniqueId());
        stage.entities.put(boss.getUniqueId(), boss);
        stage.lastSeen.put(boss.getUniqueId(), boss.getLocation().clone());
        stage.missingSeconds.remove(boss.getUniqueId());
        announce("§5[副本]§r §c" + stripColor(def.boss.name) + " §r登场了！");
        refreshBar();
    }

    private void configureBoss(LivingEntity boss, MobTemplate template) {
        if (template != null) {
            template.apply(boss);
            plugin.tagMob(boss, template.id());
        }
        if (def.boss.name != null && !def.boss.name.isBlank()) {
            boss.setCustomName(def.boss.name);
            boss.setCustomNameVisible(true);
        }
        MobHealth.apply(boss, def.boss.health);
        try {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, false, false));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 1, false, false));
        } catch (Throwable ignored) {
            // 药水名不兼容就跳过
        }
    }

    private static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }

    // ---------------------------------------------------------------- 推进

    /** 一波清完后的推进。 */
    private void advanceWave(Stage stage) {
        if (closed || failure != null || stage.cleared || stage.spawning || stage.wavePending) {
            return;
        }
        int next = stage.waveIndex + 1;
        if (next < stage.waveCount()) {
            stage.wavePending = true;
            stage.pendingWave = next;
            stage.emptySince = System.currentTimeMillis();
            announceWave(stage, next, true);
            stage.waveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                stage.waveTask = null;
                if (closed || failure != null || stage.cleared || !stage.wavePending || stage.pendingWave != next) {
                    return;   // 这一局已经收了或状态变了
                }
                spawnWave(stage, next);
            }, WAVE_DELAY_TICKS);
            return;
        }
        // 最后一波也清完了：首领区必须"确认击杀首领"
        if (stage.bossStage && !stage.bossKilled) {
            ensureBossAlive(stage);
            return;
        }
        completeStage(stage);
    }

    private void completeStage(Stage stage) {
        if (closed || failure != null || stage.cleared || !stage.mobs.isEmpty()
                || (stage.bossStage && !stage.bossKilled)) {
            return;
        }
        stage.cleared = true;
        stage.emptySince = 0;
        openGate(stage);
        grantChest(stage);
        if (stage.bossStage) {
            completed = true;
            announce("§5[副本]§r §a首领已被击败" + (def.markers.exitDoor != null
                    ? "，出口门已经打开。" : "（没标出口门，用 /dungeon leave 离开）"));
            showSummary();
        } else {
            announce("§5[副本]§r §a第 " + (stage.index + 1) + " 关已清空"
                    + (stage.gate != null ? "，关卡门打开了。" : "。"));
            advanceToNext(stage);
        }
        refreshBar();
    }

    /** 清完一关就开门，并立刻开始下一关的第 1 波。 */
    private void advanceToNext(Stage stage) {
        int next = stage.index + 1;
        if (next >= stages.size()) {
            return;
        }
        currentStage = next;
        Stage nextStage = stages.get(next);
        if (nextStage.cleared) {
            advanceToNext(nextStage);
            return;
        }
        if (nextStage.waveCount() == 0) {
            completeStage(nextStage);
            return;
        }
        // 1.5.0-M7：**不再"清完就立刻刷下一关"**。
        // 以前这行一执行，下一关的怪就出现在几百格外：玩家还在上一关慢慢打/捡东西时，
        // 那些怪既不在模拟距离内（不 tick，看着不动），区块一卸载又会消失
        // （插件看成"怪不见了"，首领那条路甚至直接结算）。
        // 现在只"标记为待开始"，等玩家走进那一关的范围才刷（见 startStagesNearPlayers）。
        nextStage.started = false;
        announce("§5[副本]§r §7第 " + (next + 1) + " 关已就绪：走进那一关就会开打。");
    }

    /**
     * "走进某一关才开始刷这一关的怪"（1.5.0-M7）。
     *
     * <p>改动原因见 {@link Dungeon#Room#started}：以前清完一关就立刻把下一关的怪刷出来，
     * 玩家慢一点就出现"几百格外的怪不 tick / 被卸载 / 首领自己消失然后直接结算"。
     */
    private void startStagesNearPlayers() {
        for (Stage stage : stages) {
            if (stage.index != currentStage || stage.cleared || stage.started || stage.waveCount() == 0) {
                continue;
            }
            Location anchor = stageAnchor(stage);
            if (anchor == null) {
                continue;
            }
            for (Player player : onlinePlayers()) {
                if (!player.getWorld().equals(world) || player.isDead()
                        || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    continue;
                }
                if (player.getLocation().distanceSquared(anchor)
                        <= STAGE_START_RADIUS * STAGE_START_RADIUS) {
                    stage.started = true;
                    plugin.getLogger().info("「" + def.name + "」第 " + (stage.index + 1)
                            + " 关：玩家已到位（" + player.getName() + "），开始刷怪");
                    spawnWave(stage, 0);
                    refreshBar();
                    break;
                }
            }
        }
    }

    /** 这一关的"入口锚点"：第一个刷怪点，没有就用关卡门。 */
    private Location stageAnchor(Stage stage) {
        for (List<Location> wave : stage.spots) {
            for (Location spot : wave) {
                if (spot != null) {
                    return spot;
                }
            }
        }
        if (stage.bossStage && def.markers.boss != null) { return toLocation(def.markers.boss); }
        return stage.index > 0 ? stages.get(stage.index - 1).gate : startLocation();
    }

    /**
     * 首领房保险（沿用 v1.4.13）：没确认击杀就不许通关；把还在的原实体找回名单，或原地补刷一只。
     *
     * <p>返回 true = 首领"还在"，这一关继续等它被打死。
     */
    private boolean ensureBossAlive(Stage stage) {
        if (closed || failure != null) { return true; }
        try {
            LivingEntity living = MobSpawner.find(world, stage.bossId, stage.bossEntity,
                    stage.bossId == null ? stageAnchor(stage) : stage.lastSeen.get(stage.bossId));
            if (living != null) {
                stage.bossEntity = living;
                stage.bossRecovery.found();
                stage.lastSeen.put(stage.bossId, living.getLocation().clone());
                if (!stage.mobs.contains(stage.bossId)) {
                    stage.mobs.add(stage.bossId);
                    stage.entities.put(stage.bossId, living);
                    stage.missingSeconds.remove(stage.bossId);
                    plugin.getLogger().warning("首领房：首领其实还活着（被误剔出名单），已重新纳入追踪");
                }
                return true;
            }
            Location last = stage.bossEntity == null
                    ? (def.markers.boss == null ? stageAnchor(stage) : toLocation(def.markers.boss))
                    : stage.bossEntity.getLocation();
            SpawnRecovery.Decision decision = stage.bossRecovery.missing(System.currentTimeMillis(),
                    MobSpawner.entitiesReady(last));
            if (decision == SpawnRecovery.Decision.ABORT) {
                fail("首领恢复失败，已尝试 " + stage.bossRecovery.attempts() + " 次；最后原因："
                        + (stage.lastBossFailure == null ? "实体持续失踪" : stage.lastBossFailure.getMessage()), stage.lastBossFailure);
            } else if (decision == SpawnRecovery.Decision.RETRY) {
                if (stage.bossId != null) {
                    stage.mobs.remove(stage.bossId);
                    stage.entities.remove(stage.bossId);
                    stage.lastSeen.remove(stage.bossId);
                }
                stage.bossRespawns = stage.bossRecovery.attempts();
                try {
                    spawnBoss(stage);
                    plugin.getLogger().warning("首领恢复第 " + stage.bossRespawns + " 次成功；仍需有效击杀");
                } catch (RuntimeException e) {
                    stage.lastBossFailure = e;
                    plugin.getLogger().warning("首领恢复第 " + stage.bossRespawns + " 次失败：" + e.getMessage());
                    FailureDiagnostics.record(plugin, "首领补刷失败 副本=" + def.name, e.getMessage(), e);
                }
            }
        } catch (RuntimeException e) {
            fail("加载首领实体失败：" + e.getMessage());
        }
        return true;
    }

    private void openGate(Stage stage) {
        if (stage.gate == null) {
            return;
        }
        Block block = stage.gate.getBlock();
        if (block.getBlockData() instanceof Door door && !door.isOpen()) {
            door.setOpen(true);
            block.setBlockData(door, false);
        }
    }

    /** 这一关的门还锁着吗（"锁着"= 玩家不许推开）。 */
    public boolean gateLocked(Location doorLower) {
        for (int i = currentStage; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            if (stage.cleared || stage.gate == null) {
                continue;
            }
            if (sameBlock(stage.gate, doorLower)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    /**
     * 这个位置附近有没有"还锁着的关卡门"（用来拦门旁边的压力板/按钮 ——
     * 不然玩家可以踩板子把锁着的门顶开，虽然下一秒又会被关上，但那一瞬间足够挤过去）。
     */
    public boolean gateLockedNear(Location location, double radius) {
        double squared = radius * radius;
        for (int i = currentStage; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            if (stage.cleared || stage.gate == null || !stage.gate.getWorld().equals(location.getWorld())) {
                continue;
            }
            if (stage.gate.distanceSquared(location) <= squared) {
                return true;
            }
        }
        return false;
    }

    /** 没清场的门每 tick 强制关上（右键那一层由事件拦，这里是兜底）。 */
    public void enforceGates() {
        for (int i = currentStage; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            if (stage.cleared || stage.gate == null) {
                continue;
            }
            Block block = stage.gate.getBlock();
            if (block.getBlockData() instanceof Door door && door.isOpen()) {
                door.setOpen(false);
                block.setBlockData(door, false);
            }
        }
    }

    public boolean isExitDoor(Location block) {
        return def.markers.exitDoor != null && stages.stream().anyMatch(s -> s.bossStage && s.cleared)
                && sameBlock(toLocation(def.markers.exitDoor), block);
    }

    // ---------------------------------------------------------------- 箱子

    private void grantChest(Stage stage) {
        for (Markers.Chest chest : def.markers.chests) {
            if (stage.bossStage != chest.isReward()) {
                continue;
            }
            if (!chest.isReward() && chest.stage != stage.index) {
                continue;
            }
            String key = chest.isReward() ? "reward-chest" : "supply-chest";
            if (!plugin.lootTables().configured(def.name, key)) {
                continue;   // 没配过这一栏就不放箱子（别塞一块兜底面包）
            }
            List<LootEntry> pool = chest.isReward()
                    ? plugin.lootTables().reward(def.name) : plugin.lootTables().supply(def.name);
            if (pool.isEmpty()) {
                continue;
            }
            Map<Integer, ItemStack> loot = LootRoller.roll(random, pool,
                    chest.isReward() ? 7 : 3, chest.isReward(), def.name, plugin);
            Location location = toLocation(chest.pos);
            location.getBlock().setType(Material.CHEST, false);
            Bukkit.getScheduler().runTask(plugin, () -> LootRoller.writeChest(location, loot, plugin));
            if (chest.isReward()) {
                for (Player player : onlinePlayers()) {
                    player.sendMessage("§5[副本]§r §6最终奖励箱出现了。");
                }
            }
        }
    }

    // ---------------------------------------------------------------- 每秒

    /** 每秒跑一次：关门、清名单、卡死兜底、检查点、首领保险、血条。 */
    public void tick() {
        if (closed || failure != null || completed || stages.isEmpty()) {
            return;
        }
        enforceGates();
        startStagesNearPlayers();   // 走进某一关才开始刷（别提前把几百格外的怪刷出来）
        if (failure != null) { return; }
        pruneVanishedMobs();
        if (failure != null) { return; }
        checkBossStages();   // 首领关单独巡检：找回 / 补刷，别让它静默消失就结算
        if (failure != null) { return; }
        guardStuckStages();
        tickCheckpoints();
        refreshBar();
    }

    /** 先找回已卸载实体，确认缺失后有限恢复；名单不得因失踪而减少。 */
    private void pruneVanishedMobs() {
        int budget = MobRecovery.MAX_ATTEMPTS_PER_TICK;
        for (Stage stage : stages) {
            if (stage.cleared || stage.mobs.isEmpty()) {
                continue;
            }
            try {
                budget = MobRecovery.tick(plugin, world, "副本「" + def.name + "」第 " + (stage.index + 1)
                                + " 关 第 " + (stage.waveIndex + 1) + " 波", stage.mobs, stage.bossId,
                        stage.entities, stage.lastSeen, stage.mobRecovery, budget);
            } catch (RuntimeException e) {
                fail("第 " + (stage.index + 1) + " 关的战斗怪物无法恢复：" + e.getMessage(), e);
                return;
            }
        }
    }

    /** 卡死兜底（沿用 v1.4.13）：名单空着不动、或"下一波"迟迟没刷出来，超时强制推进。 */
    private void guardStuckStages() {
        long now = System.currentTimeMillis();
        for (Stage stage : stages) {
            if (stage.cleared) {
                continue;
            }
            if (!stage.started) {
                continue;   // 还没开始的关卡：没怪也没波次，不参与卡住判定
            }
            if (stage.wavePending) {
                if (stage.emptySince == 0) {
                    stage.emptySince = now;
                    continue;
                }
                if (now - stage.emptySince > STUCK_WAVE_MS && stage.pendingWave >= 0) {
                    int pending = stage.pendingWave;
                    plugin.getLogger().warning("「" + def.name + "」第 " + (stage.index + 1)
                            + " 关的第 " + (pending + 1) + " 波迟迟没刷出来，强制重刷一次");
                    stage.wavePending = false;
                    stage.pendingWave = -1;
                    stage.emptySince = now;
                    if (stage.waveTask != null) { stage.waveTask.cancel(); stage.waveTask = null; }
                    spawnWave(stage, pending);
                }
                continue;
            }
            if (hasNonBossMob(stage)) {
                stage.emptySince = 0;
                continue;
            }
            // 只剩首领、而它还没被"确认击杀"：绝不在这里强制推进（否则就是"boss 还在却结算"）。
            // 它的死活交给 checkBossStages。
            if (stage.bossStage && !stage.bossKilled) {
                stage.emptySince = 0;
                continue;
            }
            if (stage.emptySince == 0) {
                stage.emptySince = now;
            } else if (now - stage.emptySince > STUCK_ROOM_MS) {
                plugin.getLogger().warning("「" + def.name + "」第 " + (stage.index + 1)
                        + " 关的怪名单空了 " + ((now - stage.emptySince) / 1000) + " 秒仍没推进，强制推进");
                stage.emptySince = now;
                advanceWave(stage);
            }
        }
    }

    /** 这一关还有没有"非首领"的怪要打（首领单独处理，不算在这里）。 */
    private static boolean hasNonBossMob(Stage stage) {
        for (UUID id : stage.mobs) {
            if (stage.bossId == null || !stage.bossId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 首领关的独立巡检：加载并找回 / 有间隔地补刷 / 耗尽后异常结束并退款。
     *
     * <p>首领被别的插件清掉、或跟着区块卸载没回来时，既不会有死亡事件，
     * 也不会被 {@link #guardStuckStages} 推进（那一关被特意排除了）——
     * 没有这一趟，玩家会被永久卡在首领关。
     */
    private void checkBossStages() {
        for (Stage stage : stages) {
            if (!stage.bossStage || stage.cleared || !stage.started || stage.bossKilled || stage.wavePending
                    || stage.waveIndex != stage.waveCount() - 1) {
                continue;
            }
            ensureBossAlive(stage);
        }
    }

    /** 检查点：踩到就记下（方块签名对不上的检查点不算数）。 */
    private void tickCheckpoints() {
        if (def.markers.checkpoints.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PlayerInfo> entry : players.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !player.getWorld().equals(world)) {
                continue;
            }
            for (int i = 0; i < def.markers.checkpoints.size(); i++) {
                Markers.Checkpoint checkpoint = def.markers.checkpoints.get(i);
                if (!Markers.signatureMatches(world, checkpoint)) {
                    continue;   // 脚下方块被改过 → 这个检查点不算数
                }
                Location location = toLocation(checkpoint.pos);
                if (player.getLocation().distanceSquared(location) > CHECKPOINT_RADIUS * CHECKPOINT_RADIUS) {
                    continue;
                }
                PlayerInfo info = entry.getValue();
                if (info.checkpoint == null || info.checkpoint.distanceSquared(location) > 0.1) {
                    info.checkpoint = location;
                    player.sendMessage("§5[副本]§r §a检查点已记录 §7(第 " + (i + 1) + " 个)");
                }
            }
        }
    }

    /** 玩家死后回到最近的检查点（检查点失效就回落点）。 */
    public Location respawnFor(UUID uuid) {
        PlayerInfo info = players.get(uuid);
        if (info != null && info.checkpoint != null) {
            return info.checkpoint;
        }
        return startLocation();
    }

    public Location startLocation() {
        if (def.markers.start != null) {
            return toLocation(def.markers.start);
        }
        return world.getSpawnLocation();
    }

    public void onPlayerDeath(Player player) {
        PlayerInfo info = players.get(player.getUniqueId());
        if (info != null) {
            info.deaths++;
        }
    }

    // ---------------------------------------------------------------- 怪物死亡

    /** 怪物死亡：推进它所属的那一关。返回 true = 这只怪属于本局。 */
    public boolean onMobDeath(LivingEntity entity) {
        if (closed || failure != null || completed) { return false; }
        for (Stage stage : stages) {
            if (stage.mobs.remove(entity.getUniqueId())) {
                stage.entities.remove(entity.getUniqueId());
                stage.mobRecovery.remove(entity.getUniqueId());
                kills++;
                stage.missingSeconds.remove(entity.getUniqueId());
                stage.lastSeen.remove(entity.getUniqueId());
                if (stage.bossId != null && stage.bossId.equals(entity.getUniqueId())) {
                    // 首领"确认击杀"还要看**死因归属**：被玩家打死（含打进岩浆 / 打落虚空）才算赢；
                    // 自己掉出世界、被别的插件清掉，不算通关，交给补刷。判定统一在 PlayerHits 里。
                    boolean byPlayer = PlayerHits.countsAsPlayerKill(entity, players::containsKey);
                    stage.bossKilled = byPlayer;
                    if (byPlayer) {
                        plugin.getLogger().info("首领确认击杀（第 " + (stage.index + 1) + " 关）："
                                + PlayerHits.describe(entity));
                    } else {
                        plugin.getLogger().warning("首领**不算被玩家打死**（第 " + (stage.index + 1)
                                + " 关）：" + PlayerHits.describe(entity)
                                + " → 这一关不放行，交给补刷；反复出现请查首领房落脚点");
                    }
                    PlayerHits.forget(entity);
                }
                if (stage.mobs.isEmpty() && !stage.wavePending && !stage.cleared && !stage.spawning) {
                    advanceWave(stage);
                }
                refreshBar();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 提示 / 血条

    private void announce(String text) {
        for (Player player : onlinePlayers()) {
            player.sendMessage(text);
        }
    }

    private void announceWave(Stage stage, int waveIndex, boolean incoming) {
        String wave = "§f第 " + (waveIndex + 1) + "/" + stage.waveCount() + " 波";
        for (Player player : onlinePlayers()) {
            if (incoming) {
                player.sendTitle("§e下一波 3 秒后", "§7第 " + (stage.index + 1) + " 关 · " + wave, 5, 40, 10);
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
            } else {
                player.sendTitle("§c" + wave, "§7第 " + (stage.index + 1) + " 关", 5, 30, 10);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.8f);
            }
        }
    }

    public void refreshBar() {
        if (bar == null) {
            return;
        }
        Stage target = null;
        for (Stage stage : stages) {
            if (!stage.cleared) {
                target = stage;
                break;
            }
        }
        if (target == null) {
            bar.setColor(org.bukkit.boss.BarColor.GREEN);
            bar.setTitle("§a副本已通关");
            bar.setProgress(1.0);
            return;
        }
        if (target.bossStage && target.bossId != null) {
            try { MobHealth.updateBar(bar, stripColor(def.boss.name),
                    MobHealth.forBar(target.bossId, target.bossEntity), def.boss.health); }
            catch (MobSpawner.Failure e) { fail(e.getMessage(), e); }
            return;
        }
        String wave = target.waveCount() > 1
                ? " §7· §f第 " + (target.waveIndex + 1) + "/" + target.waveCount() + " 波" : "";
        if (target.wavePending) {
            bar.setColor(org.bukkit.boss.BarColor.YELLOW);
            bar.setTitle("§e第 " + (target.index + 1) + " 关" + wave + " §7· §f下一波准备中");
            bar.setProgress(1.0);
            return;
        }
        bar.setColor(org.bukkit.boss.BarColor.PURPLE);
        bar.setTitle("§e第 " + (target.index + 1) + " 关" + wave + " §7· §f剩余 "
                + target.mobs.size() + " 只");
        int initial = Math.max(1, target.waves.isEmpty() ? 1 : target.waves.get(
                Math.min(target.waveIndex, target.waves.size() - 1)).size());
        bar.setProgress(Math.max(0, Math.min(1, target.mobs.size() / (double) initial)));
    }

    // ---------------------------------------------------------------- 结算

    private void showSummary() {
        long elapsed = Math.max(0, System.currentTimeMillis() - startedAt);
        for (Player player : onlinePlayers()) {
            PlayerInfo info = players.get(player.getUniqueId());
            int deaths = info == null ? 0 : info.deaths;
            player.sendMessage("§5[副本]§r §a通关！用时 §f" + Instances.formatDuration(elapsed)
                    + "§a，击杀 §f" + kills + "§a，死亡 §f" + deaths + "§a。");
            if (def.moneyReward > 0) {
                boolean paid = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "eco give " + player.getName() + " " + def.moneyReward);
                if (paid) {
                    player.sendMessage("§5[副本]§r §a通关奖励 §f" + def.moneyReward + " §a金币已到账。");
                } else {
                    plugin.getLogger().warning("发钱失败（服务器没有 eco 指令？）："
                            + player.getName() + " × " + def.moneyReward);
                }
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.openInventory(summary(player, elapsed, deaths));
        }
    }

    private Inventory summary(Player player, long elapsed, int deaths) {
        SummaryHolder holder = new SummaryHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, "§5副本结算 · " + def.name);
        holder.inventory = inventory;
        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "§8");
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(4, named(new ItemStack(Material.NETHER_STAR), "§e通关",
                "§7副本 " + def.coloredDisplay(),
                "§7用时 " + Instances.formatDuration(elapsed) + " / 限时 " + def.timeLimitMinutes + " 分"));
        inventory.setItem(11, named(new ItemStack(Material.CLOCK), "§b用时",
                "§f" + Instances.formatDuration(elapsed)));
        inventory.setItem(13, named(new ItemStack(Material.IRON_SWORD), "§c击杀",
                "§f" + kills + " §7只（全队）"));
        inventory.setItem(15, named(new ItemStack(Material.SKELETON_SKULL), "§c死亡",
                "§f" + deaths + " §7次（" + player.getName() + "）"));
        if (def.moneyReward > 0) {
            inventory.setItem(22, named(new ItemStack(Material.GOLD_INGOT), "§6金币奖励",
                    "§f+" + def.moneyReward));
        }
        return inventory;
    }

    private ItemStack named(ItemStack stack, String name, String... lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(List.of(lore));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ---------------------------------------------------------------- 收尾

    public void cleanup() {
        if (closed) { return; }
        closed = true;
        if (failure != null) { staminaPaid.forEach((uuid, amount) -> plugin.stamina().refund(uuid, amount)); }
        staminaPaid.clear();
        releaseChunks();   // 先撤区块票据，再清怪、卸世界
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        for (Stage stage : stages) {
            if (stage.waveTask != null) { stage.waveTask.cancel(); stage.waveTask = null; }
            stage.wavePending = false;
            for (LivingEntity entity : stage.entities.values()) {
                PlayerHits.forget(entity);
                if (!entity.isDead()) { entity.remove(); }
            }
            stage.entities.clear();
            stage.mobRecovery.clear();
            for (UUID uuid : stage.mobs) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity != null && !(entity instanceof Player)) {
                    entity.remove();
                }
            }
            stage.mobs.clear();
        }
    }

    private List<Player> onlinePlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                out.add(player);
            }
        }
        return out;
    }
}
