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
 *   <li><b>清完一关就开它的门，并立刻开始下一关的第 1 波</b>（和内置副本"所有房间的第一波一开始就在"
 *       是同一个观感，比"等玩家跨过门再刷"简单得多，也不会出现"门开了但下一关还没醒"的空档）。</li>
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
    private static final int MISSING_SECONDS_BEFORE_DROP = 5;
    /** 名单空着超过这么久还没推进 → 判定卡死，强制推进（v1.4.13）。 */
    private static final long STUCK_ROOM_MS = 10_000L;
    /** 等下一波远超 3 秒（刷怪任务没跑成）→ 强制重刷（v1.4.13）。 */
    private static final long STUCK_WAVE_MS = 15_000L;
    /** 首领补刷上限（v1.4.13）。 */
    private static final int MAX_BOSS_RESPAWNS = 3;
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
        public boolean cleared;
        public int waveIndex;
        public boolean wavePending;
        public int pendingWave = -1;
        public long emptySince;
        public final List<UUID> mobs = new ArrayList<>();
        public final Map<UUID, Location> lastSeen = new HashMap<>();
        public final Map<UUID, Integer> missingSeconds = new HashMap<>();
        public UUID bossId;
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
    public void begin() {
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
                continue;   // 这一波没有刷怪点 → 整波跳过（校验里会提示）
            }
            List<EntityType> types = new ArrayList<>();
            List<String> templates = new ArrayList<>();
            parseWave(specs.get(wave - 1), types, templates);
            if (types.isEmpty()) {
                continue;
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
            stage.cleared = true;
            plugin.getLogger().warning("「" + def.name + "」第 " + (stage.index + 1)
                    + " 关既没阵容也没刷怪点，直接算清场");
        }
    }

    /** 把 {@code "ZOMBIE:3,LAVA_BRUTE:2"} 展开成实体类型表 + 模板名表（解析本身在 WaveSpec 里，可离线测）。 */
    private void parseWave(String spec, List<EntityType> types, List<String> templates) {
        for (WaveSpec.Item item : WaveSpec.parse(spec)) {
            String name = item.name();
            MobTemplate template = plugin.mobTemplate(name.toLowerCase(Locale.ROOT));
            EntityType type = template != null ? template.type() : entityType(name);
            if (type == null) {
                plugin.getLogger().warning("dungeons.yml 里「" + def.name + "」的阵容有认不出的怪："
                        + name + "（既不是 mobs.yml 模板也不是原版实体名，已跳过）");
                continue;
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
        if (stage == null || stage.cleared || waveIndex < 0 || waveIndex >= stage.waveCount()) {
            return;
        }
        stage.waveIndex = waveIndex;
        stage.wavePending = false;
        stage.pendingWave = -1;
        stage.emptySince = 0;
        stage.mobs.clear();
        stage.lastSeen.clear();
        stage.missingSeconds.clear();

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
            Entity entity = world.spawnEntity(spot, types.get(i));
            if (entity == null) {
                continue;
            }
            entity.setPersistent(true);
            String templateId = templates.get(i);
            if (templateId != null && entity instanceof LivingEntity living) {
                MobTemplate template = plugin.mobTemplate(templateId);
                if (template != null) {
                    template.apply(living);
                    plugin.tagMob(living, template.id());
                }
            }
            stage.mobs.add(entity.getUniqueId());
            stage.lastSeen.put(entity.getUniqueId(), spot.clone());
        }
        if (bossWave) {
            spawnBoss(stage);
        }
        announceWave(stage, waveIndex, false);
        refreshBar();
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
        MobTemplate template = plugin.mobTemplate(def.boss.type.toLowerCase(Locale.ROOT));
        EntityType type = template != null ? template.type() : entityType(def.boss.type);
        if (type == null) {
            type = EntityType.ZOMBIE;
        }
        Entity entity = world.spawnEntity(center, type);
        if (!(entity instanceof LivingEntity boss)) {
            plugin.getLogger().warning("首领刷不出来（" + def.boss.type + "）");
            stage.bossKilled = true;   // 刷不出来就别卡住玩家
            return;
        }
        boss.setPersistent(true);
        if (template != null) {
            template.apply(boss);
            plugin.tagMob(boss, template.id());
        }
        if (def.boss.name != null && !def.boss.name.isBlank()) {
            boss.setCustomName(def.boss.name);
            boss.setCustomNameVisible(true);
        }
        AttributeInstance maxHealth = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(def.boss.health);
            boss.setHealth(Math.min(def.boss.health, maxHealth.getValue()));
        }
        try {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, false, false));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 1, false, false));
        } catch (Throwable ignored) {
            // 药水名不兼容就跳过
        }
        stage.bossId = boss.getUniqueId();
        stage.bossKilled = false;
        stage.mobs.add(boss.getUniqueId());
        stage.lastSeen.put(boss.getUniqueId(), center.clone());
        stage.missingSeconds.remove(boss.getUniqueId());
        announce("§5[副本]§r §c" + stripColor(def.boss.name) + " §r登场了！");
        refreshBar();
    }

    private static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }

    // ---------------------------------------------------------------- 推进

    /** 一波清完后的推进。 */
    private void advanceWave(Stage stage) {
        if (stage.cleared) {
            return;
        }
        int next = stage.waveIndex + 1;
        if (next < stage.waveCount()) {
            stage.wavePending = true;
            stage.pendingWave = next;
            stage.emptySince = System.currentTimeMillis();
            announceWave(stage, next, true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (stage.cleared || !stage.wavePending || stage.pendingWave != next) {
                    return;   // 这一局已经收了或状态变了
                }
                spawnWave(stage, next);
            }, WAVE_DELAY_TICKS);
            return;
        }
        // 最后一波也清完了：首领区必须"确认击杀首领"
        if (stage.bossStage && !stage.bossKilled && ensureBossAlive(stage)) {
            return;
        }
        completeStage(stage);
    }

    private void completeStage(Stage stage) {
        if (stage.cleared) {
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
            // 下一关也是"没得打"的，继续往下推
            completeStage(nextStage);
            return;
        }
        if (nextStage.waveCount() == 0) {
            completeStage(nextStage);
            return;
        }
        spawnWave(nextStage, 0);
    }

    /**
     * 首领房保险（沿用 v1.4.13）：没确认击杀就不许通关；把还在的原实体找回名单，或原地补刷一只。
     *
     * <p>返回 true = 首领"还在"，这一关继续等它被打死。
     */
    private boolean ensureBossAlive(Stage stage) {
        if (stage.bossId != null) {
            Entity existing = Bukkit.getEntity(stage.bossId);
            if (existing instanceof LivingEntity living && !living.isDead() && living.isValid()) {
                if (!stage.mobs.contains(stage.bossId)) {
                    stage.mobs.add(stage.bossId);
                    stage.lastSeen.put(stage.bossId, living.getLocation());
                    stage.missingSeconds.remove(stage.bossId);
                    plugin.getLogger().warning("首领房：首领其实还活着（被误剔出名单），已重新纳入追踪");
                }
                return true;
            }
        }
        if (stage.bossRespawns >= MAX_BOSS_RESPAWNS) {
            plugin.getLogger().warning("首领房：首领找不回、也补刷不了（已补 " + stage.bossRespawns
                    + " 次），这一关按通关处理");
            return false;
        }
        stage.bossRespawns++;
        spawnBoss(stage);
        plugin.getLogger().warning("首领房：首领不见了，已补刷第 " + stage.bossRespawns + " 只");
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
        if (stages.isEmpty()) {
            return;
        }
        enforceGates();
        pruneVanishedMobs();
        guardStuckStages();
        tickCheckpoints();
        refreshBar();
    }

    /**
     * 把"已经不在的怪"剔出名单。
     *
     * <p>沿用 v1.4.13 的两条加固：① 只有"**它最后出现的位置**所在区块已加载"时才可能判它消失
     * （不能只看房间中心）；② 要连续 {@value #MISSING_SECONDS_BEFORE_DROP} 秒都查不到才剔。
     * 这两条正是"活着的首领被判成已通关"的根源。
     */
    private void pruneVanishedMobs() {
        for (Stage stage : stages) {
            if (stage.cleared || stage.mobs.isEmpty()) {
                continue;
            }
            boolean changed = false;
            for (Iterator<UUID> it = stage.mobs.iterator(); it.hasNext(); ) {
                UUID uuid = it.next();
                Entity entity = Bukkit.getEntity(uuid);
                if (entity != null && !entity.isDead() && entity.isValid()) {
                    stage.lastSeen.put(uuid, entity.getLocation());
                    stage.missingSeconds.remove(uuid);
                    continue;
                }
                Location seen = stage.lastSeen.get(uuid);
                if (seen != null && seen.getWorld() != null
                        && !seen.getWorld().isChunkLoaded(seen.getBlockX() >> 4, seen.getBlockZ() >> 4)) {
                    stage.missingSeconds.remove(uuid);
                    continue;
                }
                int missing = stage.missingSeconds.merge(uuid, 1, Integer::sum);
                if (missing < MISSING_SECONDS_BEFORE_DROP) {
                    continue;
                }
                plugin.getLogger().warning("「" + def.name + "」第 " + (stage.index + 1)
                        + " 关有怪连续 " + missing + " 秒查不到且区块是加载的，按已消失处理（最后位置 "
                        + (seen == null ? "未知"
                        : seen.getBlockX() + "," + seen.getBlockY() + "," + seen.getBlockZ()) + "）");
                it.remove();
                stage.missingSeconds.remove(uuid);
                stage.lastSeen.remove(uuid);
                changed = true;
            }
            if (changed && stage.mobs.isEmpty() && !stage.wavePending && !stage.cleared) {
                advanceWave(stage);
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
                    spawnWave(stage, pending);
                }
                continue;
            }
            if (!stage.mobs.isEmpty()) {
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
    public boolean onMobDeath(Entity entity) {
        for (Stage stage : stages) {
            if (stage.mobs.remove(entity.getUniqueId())) {
                kills++;
                stage.missingSeconds.remove(entity.getUniqueId());
                stage.lastSeen.remove(entity.getUniqueId());
                if (stage.bossId != null && stage.bossId.equals(entity.getUniqueId())) {
                    stage.bossKilled = true;   // "确认击杀"——只有收到死亡事件才算
                }
                if (stage.mobs.isEmpty() && !stage.wavePending && !stage.cleared) {
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
            Entity entity = Bukkit.getEntity(target.bossId);
            double max = def.boss.health;
            double health = 0;
            if (entity instanceof LivingEntity living) {
                health = living.getHealth();
                AttributeInstance attribute = living.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (attribute != null) {
                    max = attribute.getValue();
                }
            }
            bar.setColor(org.bukkit.boss.BarColor.RED);
            bar.setTitle("§c" + stripColor(def.boss.name) + " §f" + (int) Math.max(0, health)
                    + " / " + (int) max);
            bar.setProgress(max <= 0 ? 0 : Math.max(0, Math.min(1, health / max)));
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
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        for (Stage stage : stages) {
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
