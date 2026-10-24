package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Powerable;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.GameRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 副本世界的槽位管理：分配、生成、房间推进、传送、回收。 */
public final class Instances {

    /** 一波清完到下一波刷出的间隔（tick）。 */
    private static final long WAVE_DELAY_TICKS = 60L;
    /** 名单空着超过这么久还没推进 → 判定卡死，强制推进（1.4.13）。 */
    private static final long STUCK_ROOM_MS = 10_000L;
    /** 走进房间的判定边距（房间 25 格、间距 40 格，8 格边距不会误触隔壁）。 */
    private static final int ROOM_START_MARGIN = 8;
    /** 等下一波的时间远超 3 秒（说明刷怪任务没跑成）→ 强制重刷（1.4.13）。 */
    private static final long STUCK_WAVE_MS = 15_000L;

    public static final class Slot {
        public final int index;
        public final Location origin;
        public final Map<UUID, Location> entranceOf = new HashMap<>();
        public final Map<UUID, Location> checkpointOf = new HashMap<>();
        public final Map<UUID, GameMode> modeOf = new HashMap<>();
        public String tier = "iron";
        public Tier settings;
        public Location spawn;
        public long deadline;
        public long startedAt;
        public int limitMinutes = 20;
        public int kills;
        public final Map<UUID, Integer> deaths = new HashMap<>();
        public List<ItemStack> lastReward = new ArrayList<>();
        public final Map<UUID, Long> offlineSince = new HashMap<>();
        public Location lastPasteMin;
        public Location lastPasteMax;
        public boolean busy;
        public boolean preparing;
        public String preparationPhase = "";
        private Object buildToken;
        private org.bukkit.scheduler.BukkitTask buildTask;
        private final java.util.Set<Long> preparationChunks = new java.util.HashSet<>();
        private Location dirtyMin;
        private Location dirtyMax;
        public boolean manualHold;
        public Dungeon dungeon;
        public String failure;
        public final Map<UUID, Integer> staminaPaid = new HashMap<>();
        public BossBar bar;
        /** 进行中给这个槽位挂的区块票据范围（区块坐标，闭区间）；没挂时 ticketed=false。 */
        public boolean ticketed;
        public int ticketMinChunkX;
        public int ticketMinChunkZ;
        public int ticketMaxChunkX;
        public int ticketMaxChunkZ;

        Slot(int index, Location origin) {
            this.index = index;
            this.origin = origin;
        }
    }

    /** 离线太久被摘出副本的玩家：留一张"回家票"，上线时送回进本前的位置与游戏模式。 */
    public record ReturnTicket(Location location, GameMode mode) {
    }

    private final Map<UUID, ReturnTicket> pendingReturns = new HashMap<>();
    private final NWNDungeon plugin;
    private final Random random = new Random();
    private final Map<Integer, Slot> slots = new LinkedHashMap<>();
    private World world;
    private NamespacedKey prevModeKey;

    public Instances(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    public World world() {
        return world;
    }

    // ---------------------------------------------------------------- 进本前的游戏模式

    private NamespacedKey prevModeKey() {
        if (prevModeKey == null) {
            prevModeKey = new NamespacedKey(plugin, "prev_gamemode");
        }
        return prevModeKey;
    }

    /** 记住进本前的游戏模式（写进玩家数据里，服务器重启也不会丢）。已经记过就不覆盖。 */
    public void rememberMode(Player player, GameMode mode) {
        if (mode == null || mode == GameMode.ADVENTURE) {
            return;   // 别把"冒险模式"当成进本前的模式记下来
        }
        if (player.getPersistentDataContainer().has(prevModeKey(), PersistentDataType.STRING)) {
            return;
        }
        player.getPersistentDataContainer().set(prevModeKey(), PersistentDataType.STRING, mode.name());
    }

    /** 还原进本前的游戏模式并清掉记录；没有记录返回 false。 */
    public boolean restoreMode(Player player) {
        String raw = player.getPersistentDataContainer().get(prevModeKey(), PersistentDataType.STRING);
        if (raw == null) {
            return false;
        }
        player.getPersistentDataContainer().remove(prevModeKey());
        try {
            player.setGameMode(GameMode.valueOf(raw));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean ready() {
        return world != null;
    }

    public boolean isInstanceWorld(World check) {
        return world != null && world.equals(check);
    }

    public void setup() {
        String name = plugin.instanceWorldName();
        World existing = Bukkit.getWorld(name);
        if (existing == null) {
            existing = new WorldCreator(name)
                    .type(WorldType.FLAT)
                    .generatorSettings("{\"layers\":[],\"biome\":\"minecraft:plains\",\"structure_overrides\":[]}")
                    .generateStructures(false)
                    .createWorld();
        }
        if (existing == null) {
            plugin.getLogger().severe("副本世界创建失败：" + name);
            return;
        }
        world = existing;
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setAutoSave(true);

        int spacing = plugin.slotSpacing();
        int count = plugin.slotCount();
        slots.clear();
        for (int i = 0; i < count; i++) {
            slots.put(i, new Slot(i, new Location(world, i * spacing, 0, 0)));
        }
        plugin.getLogger().info("副本世界就绪：" + name + "，槽位 " + count + " 个");
    }

    public Collection<Slot> all() {
        return slots.values();
    }

    public Slot byPlayer(UUID uuid) {
        for (Slot slot : slots.values()) {
            if (slot.busy && slot.entranceOf.containsKey(uuid)) {
                return slot;
            }
        }
        return null;
    }

    public Slot byIndex(int index) {
        return slots.get(index);
    }

    public Slot byBoss(java.util.UUID uuid) {
        for (Slot slot : slots.values()) {
            if (slot.busy && slot.dungeon != null && slot.dungeon.rooms.stream().anyMatch(room -> uuid.equals(room.bossId))) {
                return slot;
            }
        }
        return null;
    }

    public int reservedCount() {
        return (int) slots.values().stream().filter(slot -> slot.busy).count();
    }

    public boolean preparing() { return slots.values().stream().anyMatch(slot -> slot.preparing); }

    /** 先占位和预加载区块，再分 tick 建造；回调前不得扣体力或传送玩家。 */
    public boolean allocate(String tierId, List<Player> party, java.util.function.Consumer<Slot> ready,
                            java.util.function.Consumer<String> failed) {
        if (!ready() || plugin.preparingRun() || !plugin.canReserveRun()) { return false; }
        Tier tier = plugin.tier(tierId);
        if (tier == null) { return false; }
        for (Slot slot : slots.values()) {
            if (slot.busy) {
                continue;
            }
            slot.busy = true;
            slot.preparing = true;
            slot.preparationPhase = "预加载区块";
            Object token = new Object();
            slot.buildToken = token;
            slot.failure = null;
            slot.staminaPaid.clear();
            slot.manualHold = false;
            slot.tier = tierId;
            slot.settings = tier;
            slot.entranceOf.clear();
            slot.checkpointOf.clear();
            slot.modeOf.clear();
            slot.deaths.clear();
            slot.kills = 0;
            slot.lastReward = new ArrayList<>();
            for (Player player : party) { slot.entranceOf.put(player.getUniqueId(), player.getLocation()); }
            DungeonBuilder.Plan plan;
            try {
                plan = new DungeonBuilder().plan(
                        world, slot.origin.getBlockX(), slot.origin.getBlockY(), slot.origin.getBlockZ(), tier);
            } catch (RuntimeException e) {
                abortPreparation(slot, token, failed, e.getMessage());
                return true;
            }
            List<java.util.Iterator<Runnable>> parts = new ArrayList<>();
            if (slot.lastPasteMin != null && slot.lastPasteMax != null) {
                parts.add(clearOperations(slot.lastPasteMin, slot.lastPasteMax));
            }
            Location min = slot.dirtyMin != null ? slot.dirtyMin : slot.origin.clone().add(-16, -3, -16);
            Location max = slot.dirtyMax != null ? slot.dirtyMax
                    : slot.origin.clone().add(10 * DungeonBuilder.PITCH + 60, 16, DungeonBuilder.ROOM + 20);
            parts.add(clearOperations(min, max));
            parts.add(plan.operations().iterator());
            // 准备中途取消时，新建部分也必须留在下次清理范围内。
            slot.dirtyMin = new Location(world, Math.min(min.getBlockX(), slot.origin.getBlockX()),
                    Math.min(min.getBlockY(), slot.origin.getBlockY()), Math.min(min.getBlockZ(), slot.origin.getBlockZ()));
            slot.dirtyMax = new Location(world, Math.max(max.getBlockX(), slot.origin.getBlockX()
                    + (plan.dungeon().rooms.size() - 1) * DungeonBuilder.PITCH + DungeonBuilder.ROOM - 1),
                    Math.max(max.getBlockY(), slot.origin.getBlockY() + DungeonBuilder.HEIGHT),
                    Math.max(max.getBlockZ(), slot.origin.getBlockZ() + DungeonBuilder.ROOM - 1));
            java.util.Iterator<Runnable> work = new java.util.Iterator<>() {
                int part;
                public boolean hasNext() {
                    while (part < parts.size() && !parts.get(part).hasNext()) { part++; }
                    return part < parts.size();
                }
                public Runnable next() {
                    if (!hasNext()) { throw new java.util.NoSuchElementException(); }
                    return parts.get(part).next();
                }
            };
            TickBudget budget = new TickBudget(work);
            long started = System.nanoTime();
            List<Long> chunks = preparationChunks(slot, plan.dungeon(), min, max);
            try { preload(slot, token, chunks.iterator(), () -> {
                slot.preparationPhase = "清理与建造";
                slot.buildTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (!slot.busy || slot.buildToken != token) { return; }
                    try {
                        if (!budget.step(plugin.buildOperationsPerTick(), plugin.buildNanosPerTick())) { return; }
                        slot.buildTask.cancel();
                        slot.buildTask = null;
                        finishAllocation(slot, tier, party, plan.dungeon());
                        plugin.getLogger().info("内置副本建造完成 #" + slot.index + "：" + budget.operations()
                                + " 次操作，最长批次 " + (budget.maximumNanos() / 1_000_000.0)
                                + " ms，总计 " + ((System.nanoTime() - started) / 1_000_000.0)
                                + " ms，首领血量=" + tier.bossHealth());
                        ready.accept(slot);
                    } catch (RuntimeException e) { abortPreparation(slot, token, failed, e.getMessage()); }
                }, 1L, 1L);
            }, failed); }
            catch (RuntimeException e) { abortPreparation(slot, token, failed, e.getMessage()); }
            return true;
        }
        return false;
    }

    private void finishAllocation(Slot slot, Tier tier, List<Player> party, Dungeon dungeon) {
            slot.dungeon = dungeon;
            slot.spawn = dungeon.spawn;
            slot.startedAt = System.currentTimeMillis();
            slot.limitMinutes = tier.timeLimitMinutes();
            slot.deadline = slot.startedAt + slot.limitMinutes * 60_000L;

            for (Dungeon.Room room : dungeon.rooms) {
                room.initialMobs = room.mobs.size();
                if (room.bossRoom) {
                    room.initialBossHealth = tier.bossHealth();
                }
            }

            if (slot.bar != null) {
                slot.bar.removeAll();
            }
            BossBar bar = Bukkit.createBossBar("§5副本", BarColor.PURPLE, BarStyle.SEGMENTED_20);
            slot.bar = bar;

            for (Player player : party) {
                if (!player.isOnline()) { continue; }
                slot.checkpointOf.put(player.getUniqueId(), dungeon.spawn);
                bar.addPlayer(player);
            }

            // 大厅没有怪物，直接视为已清场：立刻给出通往第一间的按钮
            Dungeon.Room hall = dungeon.rooms.get(0);
            hall.cleared = true;
            grantRewards(slot, hall);
            refreshBar(slot);
            applyChunkTickets(slot);
            dropPreparationChunks(slot);
            slot.lastPasteMin = null;
            slot.lastPasteMax = null;
            slot.dirtyMin = slot.origin.clone();
            slot.dirtyMax = slot.origin.clone().add((dungeon.rooms.size() - 1) * DungeonBuilder.PITCH
                    + DungeonBuilder.ROOM - 1, DungeonBuilder.HEIGHT, DungeonBuilder.ROOM - 1);
            slot.preparing = false;
            slot.preparationPhase = "";
    }

    private java.util.Iterator<Runnable> clearOperations(Location min, Location max) {
        // next()/run() 成对使用，复用同一个游标，避免每次开本分配近六十万个闭包。
        class Cursor implements java.util.Iterator<Runnable>, Runnable {
            int x = min.getBlockX(), z = min.getBlockZ(), y = min.getBlockY();
            int bx, by, bz;
            public boolean hasNext() { return x <= max.getBlockX(); }
            public Runnable next() {
                if (!hasNext()) { throw new java.util.NoSuchElementException(); }
                bx = x; by = y; bz = z;
                if (++y > max.getBlockY()) {
                    y = min.getBlockY();
                    if (++z > max.getBlockZ()) { z = min.getBlockZ(); x++; }
                }
                return this;
            }
            public void run() {
                // 不为清理一片从未建造过的虚空生成新区块。
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) { return; }
                Block block = world.getBlockAt(bx, by, bz);
                if (!block.getType().isAir()) { block.setType(Material.AIR, false); }
            }
        }
        return new Cursor();
    }

    private List<Long> preparationChunks(Slot slot, Dungeon dungeon, Location dirtyMin, Location dirtyMax) {
        java.util.Set<Long> chunks = new java.util.LinkedHashSet<>();
        int minX = slot.origin.getBlockX() - 16;
        int maxX = slot.origin.getBlockX() + 10 * DungeonBuilder.PITCH + 60;
        int minZ = slot.origin.getBlockZ() - 16;
        int maxZ = slot.origin.getBlockZ() + DungeonBuilder.ROOM + 20;
        for (int x = dirtyMin.getBlockX() >> 4; x <= dirtyMax.getBlockX() >> 4; x++) {
            for (int z = dirtyMin.getBlockZ() >> 4; z <= dirtyMax.getBlockZ() >> 4; z++) {
                if (world.isChunkGenerated(x, z)) { chunks.add(chunkKey(x, z)); }
            }
        }
        // 与战斗阶段票据范围一致，建造期间也保持加载。
        int end = slot.origin.getBlockX() + (dungeon.rooms.size() - 1) * DungeonBuilder.PITCH + DungeonBuilder.ROOM;
        for (int x = minX >> 4; x <= (end + 16) >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) { chunks.add(chunkKey(x, z)); }
        }
        return new ArrayList<>(chunks);
    }

    private static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private void preload(Slot slot, Object token, java.util.Iterator<Long> chunks, Runnable done,
                         java.util.function.Consumer<String> failed) {
        if (!slot.busy || slot.buildToken != token) { return; }
        if (!chunks.hasNext()) { done.run(); return; }
        long key = chunks.next();
        int x = (int) (key >> 32), z = (int) key;
        world.getChunkAtAsync(x, z, true).orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).whenComplete((chunk, error) -> {
            if (!plugin.isEnabled()) { return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!slot.busy || slot.buildToken != token) { return; }
                if (error != null) { abortPreparation(slot, token, failed, "区块加载失败：" + error.getMessage()); return; }
                try {
                    world.addPluginChunkTicket(x, z, plugin);
                    slot.preparationChunks.add(key);
                    preload(slot, token, chunks, done, failed);
                } catch (RuntimeException e) { abortPreparation(slot, token, failed, e.getMessage()); }
            });
        });
    }

    private void abortPreparation(Slot slot, Object token, java.util.function.Consumer<String> failed, String reason) {
        if (slot.buildToken != token) { return; }
        plugin.getLogger().warning("内置副本准备失败：" + reason);
        release(slot, "§c副本准备失败，本次未扣体力。");
        failed.accept(reason);
    }

    private void dropPreparationChunks(Slot slot) {
        for (long key : slot.preparationChunks) {
            // 已正式接管的票据不能在这里撤掉（插件票据不是引用计数）。
            int x = (int) (key >> 32), z = (int) key;
            if (!slot.ticketed || x < slot.ticketMinChunkX || x > slot.ticketMaxChunkX
                    || z < slot.ticketMinChunkZ || z > slot.ticketMaxChunkZ) {
                world.removePluginChunkTicket(x, z, plugin);
            }
        }
        slot.preparationChunks.clear();
    }

    /** 清空槽位区域内的方块与实体。 */
    public void clear(Slot slot) {
        int ox = slot.origin.getBlockX();
        int oy = slot.origin.getBlockY();
        int oz = slot.origin.getBlockZ();
        int maxLength = 10 * DungeonBuilder.PITCH + 60;
        int maxZ = oz + DungeonBuilder.ROOM + 20;
        for (int x = ox - 16; x <= ox + maxLength; x++) {
            for (int z = oz - 16; z <= maxZ; z++) {
                for (int y = oy - 3; y <= oy + 16; y++) {
                    if (world.getBlockAt(x, y, z).getType() != Material.AIR) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
        Location center = new Location(world, ox + maxLength / 2.0, oy + 6, oz + DungeonBuilder.ROOM / 2.0);
        for (Entity entity : world.getNearbyEntities(center,
                maxLength / 2.0 + 20, 20, DungeonBuilder.ROOM / 2.0 + 20)) {
            if (!(entity instanceof Player)) {
                MythicMobsSupport.remove(entity);
            }
        }
    }

    /** 只清一小块（用于把上一份模板副本留下的方块抹干净）。两个参数为 null 时什么都不做。 */
    private void clearRegion(Location min, Location max) {
        if (min == null || max == null || !ready()) {
            return;
        }
        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.AIR) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        Location center = new Location(world,
                (min.getBlockX() + max.getBlockX()) / 2.0,
                (min.getBlockY() + max.getBlockY()) / 2.0,
                (min.getBlockZ() + max.getBlockZ()) / 2.0);
        for (Entity entity : world.getNearbyEntities(center,
                Math.abs(max.getBlockX() - min.getBlockX()) / 2.0 + 2,
                Math.abs(max.getBlockY() - min.getBlockY()) / 2.0 + 2,
                Math.abs(max.getBlockZ() - min.getBlockZ()) / 2.0 + 2)) {
            if (!(entity instanceof Player)) {
                MythicMobsSupport.remove(entity);
            }
        }
    }

    // ---------------------------------------------------------------- 区块票据（1.4.13）

    /**
     * 给进行中的副本挂区块票据，把副本范围的区块钉住、不让它们卸载。
     *
     * <p>为什么必须这么做：判断"这支怪还在不在"用的是 {@code Bukkit.getEntity(uuid)}，
     * 而它对**区块没加载的活怪**返回 null —— 于是活着的首领会在一瞬间被当成"已消失"：
     * 房间可能被提前判通关（结算时有时无、血条瞬间 0/60），被误剔除名单的怪还活着，
     * 之后会顺着已开的门在副本里乱走（"上一关的怪跑到下一关"）。
     * 钉住区块后，"查不到"就只剩"真的没了"一种含义。
     */
    private void applyChunkTickets(Slot slot) {
        dropChunkTickets(slot);
        if (world == null || slot.dungeon == null) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.origin == null) {
                continue;
            }
            minX = Math.min(minX, room.origin.getBlockX() - 16);
            maxX = Math.max(maxX, room.origin.getBlockX() + DungeonBuilder.ROOM + 16);
            minZ = Math.min(minZ, room.origin.getBlockZ() - 16);
            maxZ = Math.max(maxZ, room.origin.getBlockZ() + DungeonBuilder.ROOM + 16);
        }
        for (Location checkpoint : slot.dungeon.checkpoints) {
            if (checkpoint == null) {
                continue;
            }
            minX = Math.min(minX, checkpoint.getBlockX() - 16);
            maxX = Math.max(maxX, checkpoint.getBlockX() + 16);
            minZ = Math.min(minZ, checkpoint.getBlockZ() - 16);
            maxZ = Math.max(maxZ, checkpoint.getBlockZ() + 16);
        }
        if (slot.lastPasteMin != null && slot.lastPasteMax != null) {
            minX = Math.min(minX, slot.lastPasteMin.getBlockX() - 16);
            maxX = Math.max(maxX, slot.lastPasteMax.getBlockX() + 16);
            minZ = Math.min(minZ, slot.lastPasteMin.getBlockZ() - 16);
            maxZ = Math.max(maxZ, slot.lastPasteMax.getBlockZ() + 16);
        }
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        slot.ticketMinChunkX = minX >> 4;
        slot.ticketMinChunkZ = minZ >> 4;
        slot.ticketMaxChunkX = maxX >> 4;
        slot.ticketMaxChunkZ = maxZ >> 4;
        for (int cx = slot.ticketMinChunkX; cx <= slot.ticketMaxChunkX; cx++) {
            for (int cz = slot.ticketMinChunkZ; cz <= slot.ticketMaxChunkZ; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
            }
        }
        slot.ticketed = true;
    }

    /** 撤掉某个槽位的区块票据（回收槽位时调用）。 */
    private void dropChunkTickets(Slot slot) {
        if (!slot.ticketed || world == null) {
            slot.ticketed = false;
            return;
        }
        for (int cx = slot.ticketMinChunkX; cx <= slot.ticketMaxChunkX; cx++) {
            for (int cz = slot.ticketMinChunkZ; cz <= slot.ticketMaxChunkZ; cz++) {
                world.removePluginChunkTicket(cx, cz, plugin);
            }
        }
        slot.ticketed = false;
    }

    /** 插件卸载 / 关服前把所有槽位的票据撤掉，别把区块钉到下一个生命周期。 */
    public void releaseAllTickets() {
        for (Slot slot : slots.values()) {
            slot.buildToken = null;
            if (slot.buildTask != null) { slot.buildTask.cancel(); slot.buildTask = null; }
            dropPreparationChunks(slot);
            dropChunkTickets(slot);
        }
    }

    /** 房间清场：补给箱 + 按钮；首领房则给最终奖励箱和离开用的木门。 */
    public void grantRewards(Slot slot, Dungeon.Room room) {
        Tier tier = tierFor(slot);
        if (room.bossRoom) {
            if (room.exitPlate != null) {
                // 模板副本：通关后出现离开压力板（踩上去就出去）
                placePlate(room.exitPlate);
                return;
            }
            if (room.chestSpot != null && tier != null) {
                // 抽好的奖励留一份，通关结算界面里展示
                Map<Integer, ItemStack> loot = rollLoot(tier.rewardItems(), 7, true, slot.tier);
                slot.lastReward = new ArrayList<>(loot.values());
                placeChest(room.chestSpot, loot);
            }
            if (room.exitDoor != null) {
                DungeonBuilder.placeOakDoor(world, room.exitDoor.getBlockX(),
                        room.exitDoor.getBlockY(), room.exitDoor.getBlockZ());
            }
            return;
        }
        if (room.chestSpot != null && tier != null) {
            fillChest(room.chestSpot, tier.supplyItems(), 3, false, slot.tier);
        }
        if (room.plateSpot != null) {
            placePlate(room.plateSpot);
        }
    }

    /** 清场后出现在门前的石压力板：踩上去就给铁门通电，人走过去自动开。 */
    private void placePlate(Location location) {
        Block block = location.getBlock();
        block.setType(Material.STONE_PRESSURE_PLATE, false);
        if (block.getBlockData() instanceof Powerable plate) {
            plate.setPowered(false);
            block.setBlockData(plate, false);
        }
    }

    /**
     * 兜底：房间没打完，通往下一间的铁门保持关闭、按钮不许出现。
     * 门本身是铁门，冒险模式下玩家徒手打不开，所以这一条守住了"清完才能走"。
     */
    private void enforceGates(Slot slot) {
        if (slot.dungeon == null) {
            return;
        }
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.cleared) {
                continue;
            }
            if (room.doorLower != null) {
                Block door = room.doorLower.getBlock();
                if (door.getBlockData() instanceof Door data && data.isOpen()) {
                    data.setOpen(false);
                    door.setBlockData(data, false);
                }
            }
            if (room.plateSpot != null) {
                Material type = room.plateSpot.getBlock().getType();
                if (type == Material.LEVER || Tag.BUTTONS.isTagged(type) || Tag.PRESSURE_PLATES.isTagged(type)) {
                    room.plateSpot.getBlock().setType(Material.AIR, false);
                }
            }
        }
    }

    /**
     * 放补给箱 / 最终奖励箱，并把战利品塞进去。
     *
     * 踩过的坑：方块刚 setType 完的那一瞬间世界里的容器还没稳定，这时 getState()
     * 拿到的快照背包是个空壳，填完再 update() 写回去等于把空背包覆盖回去 ——
     * 箱子就是空的。所以先放箱子，下一 tick 再用实时状态直接改世界里的容器。
     */
    private void fillChest(Location location, List<LootEntry> pool, int maxTypes, boolean rich, String tierId) {
        if (pool.isEmpty()) {
            return;
        }
        // D6：这一段配置里要是压根没写补给箱那一栏，就别放箱子 ——
        // 否则 LootTables 的空池兜底会变成"每关固定塞一块面包"（那个兜底是给内置副本用的）。
        if (!plugin.lootTables().configured(tierId, "supply-chest")) {
            return;
        }
        placeChest(location, rollLoot(pool, maxTypes, rich, tierId));
    }

    /** 先放箱子，下一 tick 再写内容（新放下的方块实体要等一 tick 才稳定）。 */
    private void placeChest(Location location, Map<Integer, ItemStack> loot) {
        location.getBlock().setType(Material.CHEST, false);
        Bukkit.getScheduler().runTask(plugin, () -> LootRoller.writeChest(location, loot, plugin));
    }

    /**
     * 抽奖励：**实现搬到了 {@link LootRoller}**，这里只保留一个转发入口
     * （1.5.0 之前这段逻辑在本类里，自建副本又抄了一份；M6 把两份合成一份，
     * 内置副本这条路的抽奖结果不变 —— 逐行对比过，只是换了执行位置）。
     */
    private Map<Integer, ItemStack> rollLoot(List<LootEntry> pool, int maxTypes, boolean rich, String tierId) {
        return LootRoller.roll(random, pool, maxTypes, rich, tierId, plugin);
    }

    /** 清场广播：给副本里所有人发消息（击杀清场与兜底清场共用）。 */
    private void announceCleared(Slot slot, Dungeon.Room room) {
        for (UUID uuid : slot.entranceOf.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(room.bossRoom
                        ? "§5[副本]§r §a首领已被击败，中央木门可以离开了。"
                        : "§5[副本]§r §a第 " + room.displayIndex + " 间已清空，门前出现压力板，角落出现补给箱。");
            }
        }
    }

    /**
     * 一波清完后的推进：还有下一波就等 3 秒再刷（带预告 + 音效），
     * 最后一波清完才算这间完成。
     */
    private void advanceWave(Slot slot, Dungeon.Room room) {
        if (!slot.busy || slot.failure != null || room.cleared || room.spawning || room.wavePending) {
            return;
        }
        int next = room.waveIndex + 1;
        if (next < room.waves.size()) {
            room.wavePending = true;
            room.pendingWave = next;
            room.emptySince = System.currentTimeMillis();
            Dungeon dungeon = slot.dungeon;
            announceWave(slot, room, next, true);
            room.waveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                room.waveTask = null;
                if (!slot.busy || slot.failure != null || slot.dungeon != dungeon || room.cleared
                        || !room.wavePending || room.pendingWave != next) {
                    return;   // 槽位已经回收或重开了，别再刷
                }
                spawnWave(slot, room, next);
            }, WAVE_DELAY_TICKS);
            return;
        }
        // 最后一波也清完了。1.4.13：首领房必须"确认击杀首领"才算通关 ——
        // 以前是"名单空了就算通关"，而活着的首领只要一时查不到（区块卸载）就会被当成死了，
        // 于是出现"首领还在却已经结算"。
        if (room.bossRoom && !room.bossKilled) {
            ensureBossAlive(slot, room);
            return;
        }
        completeRoom(slot, room);
    }

    /** 这一间真的完成了：发奖励、清场广播，首领房额外弹结算界面。 */
    private void completeRoom(Slot slot, Dungeon.Room room) {
        if (!slot.busy || slot.failure != null || room.cleared || !room.mobs.isEmpty()
                || (room.bossRoom && !room.bossKilled)) {
            return;
        }
        room.cleared = true;
        room.emptySince = 0;
        grantRewards(slot, room);
        announceCleared(slot, room);
        if (room.bossRoom) {
            showSummary(slot);
        }
    }

    /**
     * 首领房保险：没确认击杀首领就不许通关。
     *
     * <p>返回 true = 首领"已经回来了"（把还在的原实体找回名单，或原地补刷一只），房间继续等它被打死；
     * 恢复耗尽会标记系统异常，由管理器退款并回收；绝不调用通关流程。
     */
    private boolean ensureBossAlive(Slot slot, Dungeon.Room room) {
        if (slot.failure != null) { return true; }
        try {
            LivingEntity living = MobSpawner.find(world, room.bossId, room.bossEntity,
                    room.bossId == null ? room.center : room.lastSeen.get(room.bossId));
            if (living != null) {
                room.bossEntity = living;
                room.mythicBoss |= MythicMobsSupport.managed(living);
                room.bossRecovery.found();
                room.lastSeen.put(room.bossId, living.getLocation().clone());
                if (!room.mobs.contains(room.bossId)) {
                    room.mobs.add(room.bossId);
                    room.entities.put(room.bossId, living);
                    room.missingSeconds.remove(room.bossId);
                    plugin.getLogger().warning("首领房：首领其实还活着（被误剔出名单），已重新纳入追踪");
                }
                return true;
            }
            Location last = room.bossEntity == null ? room.center : room.bossEntity.getLocation();
            SpawnRecovery.Decision decision = room.bossRecovery.missing(System.currentTimeMillis(),
                    MobSpawner.entitiesReady(last));
            if (decision == SpawnRecovery.Decision.ABORT) {
                fail(slot, "首领恢复失败，已尝试 " + room.bossRecovery.attempts() + " 次；最后原因："
                        + (room.lastBossFailure == null ? "实体持续失踪" : room.lastBossFailure.getMessage()), room.lastBossFailure);
            } else if (decision == SpawnRecovery.Decision.RETRY) {
                if (room.bossId != null && room.mythicBoss) {
                    throw new MobSpawner.Failure("神话首领持续失踪，无法安全重建技能阶段；本局异常结束，不判通关");
                }
                if (room.bossId != null) {
                    room.mobs.remove(room.bossId);
                    room.entities.remove(room.bossId);
                    room.lastSeen.remove(room.bossId);
                }
                room.bossRespawns = room.bossRecovery.attempts();
                try {
                    DungeonBuilder.spawnBoss(world, room, tierFor(slot));
                    initializeBossHealth(slot, room);
                    plugin.getLogger().warning("首领恢复第 " + room.bossRespawns + " 次成功；仍需有效击杀");
                } catch (RuntimeException e) {
                    room.lastBossFailure = e;
                    plugin.getLogger().warning("首领恢复第 " + room.bossRespawns + " 次失败：" + e.getMessage());
                    FailureDiagnostics.record(plugin, "首领补刷失败 槽位#" + slot.index + " 难度=" + slot.tier,
                            e.getMessage(), e);
                }
            }
        } catch (RuntimeException e) {
            fail(slot, "加载首领实体失败：" + e.getMessage());
        }
        return true;
    }

    private void spawnWave(Slot slot, Dungeon.Room room, int wave) {
        if (!slot.busy || slot.failure != null) { return; }
        try {
            DungeonBuilder.spawnWave(world, room, wave, tierFor(slot));
            if (room.bossRoom && wave == room.waves.size() - 1 && room.lastBossFailure == null) {
                initializeBossHealth(slot, room);
            }
            if (room.lastBossFailure != null) {
                plugin.getLogger().warning("首领首次生成失败，进入恢复等待：" + room.lastBossFailure.getMessage());
                FailureDiagnostics.record(plugin, "首领首次生成失败 槽位#" + slot.index + " 难度=" + slot.tier
                        + " 房间=" + room.displayIndex + " 波=" + (wave + 1), room.lastBossFailure.getMessage(), room.lastBossFailure);
            }
            announceWave(slot, room, wave, false);
            refreshBar(slot);
        } catch (RuntimeException e) {
            fail(slot, "第 " + room.displayIndex + " 间生成失败：" + e.getMessage(), e);
        }
    }

    private void initializeBossHealth(Slot slot, Dungeon.Room room) {
        LivingEntity boss = room.bossEntity;
        if (boss == null) { return; }
        UUID uuid = boss.getUniqueId();
        plugin.initializeBossHealth(boss, tierFor(slot).bossHealth(),
                () -> slot.busy && slot.failure == null && !room.cleared && uuid.equals(room.bossId)
                        && room.bossEntity == boss,
                () -> refreshBar(slot), e -> fail(slot, "首领出生血量初始化失败：" + e.getMessage(), e));
    }

    private void fail(Slot slot, String reason) {
        fail(slot, reason, null);
    }

    private void fail(Slot slot, String reason, Throwable cause) {
        if (slot.failure == null) {
            slot.failure = reason;
            plugin.getLogger().severe("副本槽位 " + slot.index + " 异常终止：" + reason);
            FailureDiagnostics.record(plugin, "内置副本终止 槽位#" + slot.index + " 难度=" + slot.tier
                    + " 世界=" + (world == null ? "未创建" : world.getName()), reason, cause);
        }
    }

    /** 波次提示：3 秒预告 / 新一波登场（标题 + 音效）。 */
    private void announceWave(Slot slot, Dungeon.Room room, int waveIndex, boolean incoming) {
        String wave = "§f第 " + (waveIndex + 1) + "/" + room.waves.size() + " 波";
        for (UUID uuid : slot.entranceOf.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            if (incoming) {
                player.sendTitle("§e下一波 3 秒后", "§7第 " + room.displayIndex + " 间 · " + wave, 5, 40, 10);
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
            } else {
                List<String> names = tierFor(slot).roomNames();
                String title = room.displayIndex > 0 && room.displayIndex <= names.size() ? names.get(room.displayIndex - 1) : "§c" + wave;
                player.sendTitle(waveIndex == 0 ? title : "§c" + wave, "§7第 " + room.displayIndex + " 间", 5, 30, 10);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.8f);
            }
        }
    }

    /** 通关结算：给副本里每个玩家弹结算界面（用时 / 击杀 / 死亡 / 评级 / 奖励）。 */
    private void showSummary(Slot slot) {
        long elapsed = Math.max(0, System.currentTimeMillis() - slot.startedAt);
        for (UUID uuid : new ArrayList<>(slot.entranceOf.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            int deaths = slot.deaths.getOrDefault(uuid, 0);
            String rank = rank(slot, elapsed, deaths);
            player.sendMessage("§5[副本]§r §a通关！用时 §f" + formatDuration(elapsed)
                    + "§a，击杀 §f" + slot.kills + "§a，评级 §e" + rank + "§a。");
            Tier tier = tierFor(slot);
            if (tier != null && tier.moneyReward() > 0) {
                boolean paid = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "eco give " + player.getName() + " " + tier.moneyReward());
                if (paid) {
                    player.sendMessage("§5[副本]§r §a通关奖励 §f" + tier.moneyReward() + " §a金币已到账。");
                } else {
                    plugin.getLogger().warning("发钱失败（服务器没有 eco 指令？）："
                            + player.getName() + " × " + tier.moneyReward());
                }
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.openInventory(summaryInventory(slot, player, elapsed, deaths, rank));
        }
    }

    /** 评级：用时（相对限时）+ 死亡次数。S = 半场以内且零死亡；A = 3/4 限时以内且最多死一次；其余 B。 */
    private String rank(Slot slot, long elapsed, int deaths) {
        long limit = Math.max(1, slot.limitMinutes) * 60_000L;
        double ratio = limit <= 0 ? 1 : elapsed / (double) limit;
        if (ratio <= 0.5 && deaths == 0) {
            return "S";
        }
        if (ratio <= 0.75 && deaths <= 1) {
            return "A";
        }
        return "B";
    }

    public static String formatDuration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private Inventory summaryInventory(Slot slot, Player player, long elapsed, int deaths, String rank) {
        Tier tier = tierFor(slot);
        int limit = Math.max(1, slot.limitMinutes);
        SummaryHolder holder = new SummaryHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, "§5副本结算 · 评级 " + rank);
        holder.inventory = inventory;
        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "§8");
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(4, named(new ItemStack(rankIcon(rank)), "§e评级 " + rank,
                "§7难度 " + (tier == null ? slot.tier : tier.display()),
                "§7用时 " + formatDuration(elapsed) + " / 限时 " + limit + " 分"));
        inventory.setItem(10, named(new ItemStack(Material.CLOCK), "§b用时",
                "§f" + formatDuration(elapsed) + " §7（限时 " + limit + " 分）"));
        inventory.setItem(12, named(new ItemStack(Material.IRON_SWORD), "§c击杀",
                "§f" + slot.kills + " §7只（全队）"));
        inventory.setItem(14, named(new ItemStack(Material.SKELETON_SKULL), "§c死亡",
                "§f" + deaths + " §7次（" + player.getName() + "）"));
        List<String> lore = new ArrayList<>();
        if (slot.lastReward.isEmpty()) {
            lore.add("§7无");
        } else {
            for (ItemStack stack : slot.lastReward) {
                lore.add("§f" + stack.getType().name() + " §7x" + stack.getAmount());
            }
        }
        inventory.setItem(16, named(new ItemStack(Material.CHEST), "§6最终奖励",
                lore.toArray(new String[0])));
        if (tier != null && tier.moneyReward() > 0) {
            inventory.setItem(22, named(new ItemStack(Material.GOLD_INGOT), "§6金币奖励",
                    "§f+" + tier.moneyReward()));
        }
        return inventory;
    }

    private Material rankIcon(String rank) {
        return switch (rank) {
            case "S" -> Material.NETHER_STAR;
            case "A" -> Material.DIAMOND;
            default -> Material.IRON_INGOT;
        };
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

    /** 结算界面的占位 holder：用来识别并拦掉玩家点击。 */
    public static final class SummaryHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** 记录某个玩家的死亡次数（结算用）。 */
    public void onPlayerDeath(Player player) {
        Slot slot = byPlayer(player.getUniqueId());
        if (slot != null) {
            slot.deaths.merge(player.getUniqueId(), 1, Integer::sum);
        }
    }

    /** 还有没有空闲槽位（进本读条前先查，免得白等 3 秒）。 */
    public boolean hasFreeSlot() {
        if (!ready()) {
            return false;
        }
        for (Slot slot : slots.values()) {
            if (!slot.busy) {
                return true;
            }
        }
        return false;
    }

    private Tier tierFor(Slot slot) { return slot.settings != null ? slot.settings : plugin.tier(slot.tier); }

    // ---------------------------------------------------------------- 老模板路线（已删除）
    //
    // 1.5.0 起自建副本走的是"复制整个世界"（见 DungeonRun / InstanceWorlds），老的那条
    // "把 .nbt 结构贴进共享槽位"（allocateTemplate / templateDungeon / checkExitPlate / spawnWave(slot)）
    // 已经没有任何调用方，整段删掉 —— 顺带消灭了计划书 §11.1 里的 D1~D5：
    //   D1/D2 首领属性只认 Tier（模板副本 slot.tier = "template:x"，导致每次清首领房都刷警告、且没有首领）
    //   D3    首领房见到 exitPlate 就 return，最终奖励箱从来没发过
    //   D4    出口门只认 room.exitDoor，模板副本的 exitPlate 右键没反应
    //   D5    enforceGates 会把管理员标定的固定坐标上的按钮/压力板每秒抠掉
    // 骨架类 Template / TemplateEditor 暂时留着：M5 做入口建筑要抽里面的结构读写（Paper Structure API）。

    /** 坐标的可读写法（日志用）。 */
    private static String xyz(Location location) {
        if (location == null) {
            return "未知";
        }
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private void pruneVanishedMobs(Slot slot) {
        if (slot.dungeon == null) {
            return;
        }
        int budget = MobRecovery.MAX_ATTEMPTS_PER_TICK;
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.cleared || room.mobs.isEmpty()) {
                continue;
            }
            try {
                budget = MobRecovery.tick(plugin, world, "副本槽位 " + slot.index + " 第 " + room.displayIndex
                                + " 间 第 " + (room.waveIndex + 1) + " 波", room.mobs, room.bossId,
                        room.entities, room.lastSeen, room.mobRecovery, budget);
            } catch (RuntimeException e) {
                fail(slot, "第 " + room.displayIndex + " 间的战斗怪物无法恢复：" + e.getMessage(), e);
                return;
            }
        }
    }

    /**
     * 卡死兜底（1.4.13）：名单空着却没推进、或"下一波"迟迟没刷出来，
     * 说明推进链路某一环断了（刷怪任务异常、提前 return 等）。超时后强制推进，
     * 避免整间永远清不掉、玩家再也拿不到结算。
     */
    /**
     * "走进房间才开始刷怪"（1.5.0-M7）。
     *
     * <p>为什么改：以前建房时就把每一间房的第一波全刷出来，最远的房间离玩家几百格。
     * 那些怪既不在模拟距离内（**不 tick**，看起来站着不动），区块一卸载又会从内存里消失
     * （{@code getEntity} 返回 null）→ 插件看成"怪不见了"，首领那条路甚至直接结算。
     * 玩家报的"高难度副本、打得越慢 boss 越容易自己消失"就是这么来的。
     *
     * <p>现在只有玩家**真的走进那一间**（含 {@value #ROOM_START_MARGIN} 格边距）才刷这一间的怪。
     * 顺带的好处：一局里同时活着的怪少了一大截（10 间房的副本以前是把 10 间全挂着的）。
     */
    private void startRoomsNearPlayers(Slot slot) {
        if (slot.dungeon == null || slot.failure != null) {
            return;
        }
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.index == 0 || room.cleared) {
                continue;
            }
            // 只允许首个未完成房间启动，不能隔着门提前唤醒后面的房间。
            if (room.started || room.waves.isEmpty()) { return; }
            if (firstPlayerInside(slot, room) == null) {
                return;
            }
            room.started = true;
            spawnWave(slot, room, 0);
            return;
        }
    }

    /** 这一间房（含 margin 格边距）里的第一个玩家；没人返回 null。 */
    private Player firstPlayerInside(Slot slot, Dungeon.Room room) {
        if (room.origin == null) {
            return null;
        }
        for (UUID uuid : slot.entranceOf.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()
                    || player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                    || !player.getWorld().equals(room.origin.getWorld())) {
                continue;
            }
            Location at = player.getLocation();
            if (at.getBlockX() >= room.origin.getBlockX() - ROOM_START_MARGIN
                    && at.getBlockX() <= room.origin.getBlockX() + DungeonBuilder.ROOM + ROOM_START_MARGIN
                    && at.getBlockZ() >= room.origin.getBlockZ() - ROOM_START_MARGIN
                    && at.getBlockZ() <= room.origin.getBlockZ() + DungeonBuilder.ROOM + ROOM_START_MARGIN
                    && Math.abs(at.getY() - room.origin.getY() - 1) <= DungeonBuilder.HEIGHT) {
                return player;
            }
        }
        return null;
    }

    private void guardStuckRooms(Slot slot) {
        if (slot.dungeon == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.index == 0 || room.cleared) {
                continue;
            }
            // 还没开始的房间（玩家没走进去、怪还没刷）不参与"卡住强制推进"，
            // 否则空名单 10 秒就会被当成"卡住"推过去，等于跳过这一间。
            if (!room.started) {
                continue;
            }
            if (room.wavePending) {
                if (room.emptySince == 0) {
                    room.emptySince = now;
                    continue;
                }
                if (now - room.emptySince > STUCK_WAVE_MS && room.pendingWave >= 0) {
                    int pending = room.pendingWave;
                    plugin.getLogger().warning("第 " + room.displayIndex + " 间的第 " + (pending + 1)
                            + " 波迟迟没刷出来，强制重刷一次");
                    room.wavePending = false;
                    room.pendingWave = -1;
                    room.emptySince = now;
                    if (room.waveTask != null) { room.waveTask.cancel(); room.waveTask = null; }
                    spawnWave(slot, room, pending);
                }
                continue;
            }
            if (hasNonBossMob(room)) {
                room.emptySince = 0;
                continue;
            }
            // 只剩首领、而它还没被"确认击杀"：**绝不在这里强制推进**。
            // 它的死活交给 checkBossRooms（找回 / 补刷 / 补刷用尽才放行）。
            // 以前这里会把"名单里只剩首领"当成"房间没人了"，10 秒后强制推进 → 直接结算。
            if (room.bossRoom && !room.bossKilled) {
                room.emptySince = 0;
                continue;
            }
            if (room.emptySince == 0) {
                room.emptySince = now;
            } else if (now - room.emptySince > STUCK_ROOM_MS) {
                plugin.getLogger().warning("第 " + room.displayIndex + " 间的怪名单空了 "
                        + ((now - room.emptySince) / 1000) + " 秒仍没推进，强制推进");
                room.emptySince = now;
                advanceWave(slot, room);
            }
        }
    }

    /** 这一间还有没有"非首领"的怪要打（首领单独处理，不算在这里）。 */
    private static boolean hasNonBossMob(Dungeon.Room room) {
        for (UUID id : room.mobs) {
            if (room.bossId == null || !room.bossId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 首领房的独立巡检（每秒一次）。
     *
     * <p>为什么必须单独有这一趟：首领一旦被别的插件清掉、或跟着区块卸载后没回来，
     * 它既不会触发死亡事件，房间也不会被 {@link #guardStuckRooms} 推进
     * （那一间被我们特意排除了）—— 没有这一趟，玩家就会被永久卡在首领房。
     *
     * <p>处置顺序：加载实际区块并找回 → 等待 5 秒 → 有间隔地补刷最多 3 次 → 异常结束并退款。
     */
    private void checkBossRooms(Slot slot) {
        if (slot.dungeon == null || slot.failure != null) {
            return;
        }
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (!room.bossRoom || room.cleared || !room.started || room.bossKilled || room.wavePending
                    || room.waveIndex != room.waves.size() - 1) {
                continue;
            }
            ensureBossAlive(slot, room);
        }
    }

    /** 怪物死亡后调用：推进房间进度。 */
    public boolean onMobDeath(LivingEntity entity) {
        if (!ready() || !isInstanceWorld(entity.getWorld())) {
            return false;
        }
        for (Slot slot : slots.values()) {
            if (!slot.busy || slot.failure != null || slot.dungeon == null) {
                continue;
            }
            for (Dungeon.Room room : slot.dungeon.rooms) {
                if (room.mobs.remove(entity.getUniqueId())) {
                    room.entities.remove(entity.getUniqueId());
                    room.mobRecovery.remove(entity.getUniqueId());
                    room.kills++;
                    slot.kills++;
                    room.missingSeconds.remove(entity.getUniqueId());
                    room.lastSeen.remove(entity.getUniqueId());
                    // 首领"确认击杀"：1.4.13 要求必须有死亡事件；1.5.0 再进一步**判定死因归属** ——
                    // 被玩家打死的、包括被打进岩浆/打落虚空的，都算玩家赢（见 PlayerHits）；
                    // 从头到尾没人碰过它、自己消失的，不算通关，走补刷。
                    if (room.bossId != null && room.bossId.equals(entity.getUniqueId())) {
                        boolean byPlayer = PlayerHits.countsAsPlayerKill(entity, slot.entranceOf::containsKey);
                        if (byPlayer) {
                            room.bossKilled = true;
                            plugin.getLogger().info("首领确认击杀（第 " + room.displayIndex + " 间）："
                                    + PlayerHits.describe(entity) + "，位置 " + xyz(entity.getLocation()));
                        } else {
                            room.bossKilled = false;
                            plugin.getLogger().warning("首领**不算被玩家打死**（第 " + room.displayIndex + " 间）："
                                    + PlayerHits.describe(entity) + "，位置 " + xyz(entity.getLocation())
                                    + " → 这一间不放行，交给补刷；反复出现请检查首领房落脚点");
                        }
                        PlayerHits.forget(entity);
                    }
                    if (room.mobs.isEmpty() && !room.wavePending && !room.cleared && !room.spawning) {
                        advanceWave(slot, room);
                    }
                    refreshBar(slot);
                    return true;
                }
            }
        }
        return false;
    }

    /** 更新 Boss 血条：显示当前要打的房间剩余怪物，或首领血量。 */
    public void refreshBar(Slot slot) {
        if (slot.bar == null || slot.dungeon == null) {
            return;
        }
        Dungeon.Room target = null;
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.index == 0 || room.cleared) {
                continue;
            }
            target = room;
            break;
        }
        if (target == null) {
            slot.bar.setColor(BarColor.GREEN);
            slot.bar.setTitle("§a副本已通关 · 从中央木门离开");
            slot.bar.setProgress(1.0);
            return;
        }
        if (target.bossRoom && target.bossId != null) {
            double configured = slot.settings != null ? slot.settings.bossHealth() : target.initialBossHealth;
            try { MobHealth.updateBar(slot.bar, "首领", MobHealth.forBar(target.bossId, target.bossEntity), configured); }
            catch (MobSpawner.Failure e) { fail(slot, e.getMessage(), e); }
            return;
        }
        String wave = target.waves.size() > 1
                ? " §7· §f第 " + (target.waveIndex + 1) + "/" + target.waves.size() + " 波"
                : "";
        if (target.wavePending) {
            slot.bar.setColor(BarColor.YELLOW);
            slot.bar.setTitle("§e第 " + target.displayIndex + " 间" + wave + " §7· §f下一波准备中");
            slot.bar.setProgress(1.0);
            return;
        }
        slot.bar.setColor(BarColor.PURPLE);
        slot.bar.setTitle("§e第 " + target.displayIndex + " 间" + wave + " §7· §f剩余 " + target.mobs.size() + " 只");
        slot.bar.setProgress(target.initialMobs <= 0 ? 1 : (double) target.mobs.size() / target.initialMobs);
    }

    public void release(Slot slot, String reason) {
        if (!slot.busy) {
            return;
        }
        boolean wasPreparing = slot.preparing;
        slot.buildToken = null;
        if (slot.buildTask != null) { slot.buildTask.cancel(); slot.buildTask = null; }
        dropPreparationChunks(slot);
        slot.preparing = false;
        slot.preparationPhase = "";
        if (slot.dungeon != null) {
            for (Dungeon.Room room : slot.dungeon.rooms) {
                if (room.waveTask != null) { room.waveTask.cancel(); room.waveTask = null; }
                room.wavePending = false;
                for (LivingEntity entity : room.entities.values()) {
                    PlayerHits.forget(entity);
                    if (!entity.isDead()) { MythicMobsSupport.remove(entity); }
                }
                room.entities.clear();
                room.mobRecovery.clear();
                room.mobs.clear();
            }
        }
        if (slot.failure != null) {
            slot.staminaPaid.forEach((uuid, amount) -> plugin.stamina().refund(uuid, amount));
        }
        slot.staminaPaid.clear();
        if (slot.failure != null) { reason += " §7原因：" + failureText(slot.failure); }
        for (UUID uuid : new ArrayList<>(slot.entranceOf.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (wasPreparing) {
                if (player != null && player.isOnline()) { player.sendMessage("§5[副本]§r " + reason); }
                continue;
            }
            if (player != null && player.isOnline()) {
                if (slot.bar != null) {
                    slot.bar.removePlayer(player);
                }
                Location back = plugin.returnToEntrance() ? slot.entranceOf.get(uuid) : null;
                player.teleport(back != null ? back : world.getSpawnLocation());
                GameMode previous = slot.modeOf.get(uuid);
                if (previous != null) {
                    player.setGameMode(previous);
                }
                restoreMode(player);
                player.sendMessage("§5[副本]§r " + reason);
            } else {
                // 人不在线：留张回家票，等他上线时送回进本前的位置
                Location back = slot.entranceOf.get(uuid);
                if (back != null) {
                    pendingReturns.put(uuid, new ReturnTicket(back, slot.modeOf.get(uuid)));
                }
            }
        }
        if (slot.bar != null) {
            slot.bar.removeAll();
            slot.bar = null;
        }
        slot.entranceOf.clear();
        slot.checkpointOf.clear();
        slot.modeOf.clear();
        slot.offlineSince.clear();
        dropChunkTickets(slot);
        slot.dungeon = null;
        slot.busy = false;
    }

    /**
     * 玩家重新上线时调用：如果他还在这场副本里，就地接续（送回最近的检查点、恢复冒险模式、重新挂上 Boss 血条）；
     * 如果这一局已经结束/回收了，就按"回家票"送回进本前的位置。
     */
    public void resume(Player player) {
        if (!ready()) {
            return;
        }
        Slot slot = byPlayer(player.getUniqueId());
        if (slot != null) {
            if (slot.preparing) { player.sendMessage("§5[副本]§r §7副本仍在准备，请等待完成。"); return; }
            slot.offlineSince.remove(player.getUniqueId());
            if (slot.bar != null) {
                slot.bar.addPlayer(player);
            }
            Location target = slot.checkpointOf.get(player.getUniqueId());
            if (target == null) {
                target = slot.spawn;
            }
            if (target != null) {
                player.teleport(target);
            }
            player.setGameMode(GameMode.ADVENTURE);
            player.sendMessage("§5[副本]§r §a欢迎回来，已把你接回副本（死亡会回到最近的检查点）。");
            player.sendMessage("§7输入 §f/dungeon leave §7可以离开副本。");
            return;
        }
        ReturnTicket ticket = pendingReturns.remove(player.getUniqueId());
        if (ticket != null) {
            if (ticket.location() != null && ticket.location().getWorld() != null) {
                player.teleport(ticket.location());
            }
            if (ticket.mode() != null) {
                player.setGameMode(ticket.mode());
            }
            restoreMode(player);
            player.sendMessage("§5[副本]§r §7你离线太久，这一局已经结束，已把你送回原来的位置。");
            return;
        }
        if (isInstanceWorld(player.getWorld())) {
            player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            if (!restoreMode(player)) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            player.sendMessage("§5[副本]§r §7你已经不在副本里了，已把你送回主城。");
            return;
        }
        // 兜底：身上还留着"进本前的游戏模式"记录，但人已经在副本外 → 还原
        if (restoreMode(player)) {
            player.sendMessage("§5[副本]§r §7已把你从副本的冒险模式切回原来的游戏模式。");
        }
    }

    static String failureText(String reason) {
        String clean = reason == null ? "未知错误" : reason.replaceAll("§.", "").replaceAll("[\\r\\n\\t]+", " ");
        return clean.length() > 240 ? clean.substring(0, 240) + "…" : clean;
    }

    public void leave(Player player, String reason) {
        Slot slot = byPlayer(player.getUniqueId());
        if (slot == null) {
            return;
        }
        Location back = slot.entranceOf.get(player.getUniqueId());
        slot.entranceOf.remove(player.getUniqueId());
        slot.checkpointOf.remove(player.getUniqueId());
        GameMode previous = slot.modeOf.remove(player.getUniqueId());
        if (slot.bar != null) {
            slot.bar.removePlayer(player);
        }
        if (back != null) {
            player.teleport(back);
        }
        if (previous != null) {
            player.setGameMode(previous);
        }
        restoreMode(player);
        player.sendMessage("§5[副本]§r " + reason);
        if (slot.entranceOf.isEmpty()) {
            release(slot, "副本已清空，槽位回收。");
        }
    }

    public void tick() {
        if (!ready()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Slot slot : slots.values()) {
            if (!slot.busy || slot.preparing) {
                continue;
            }
            if (slot.failure != null) {
                release(slot, "§c副本生成异常，本局结束，已扣体力已退还；本局未通关。");
                continue;
            }
            if (now > slot.deadline) {
                release(slot, "时间到，副本结束。");
                continue;
            }
            for (UUID uuid : new ArrayList<>(slot.entranceOf.keySet())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    // 掉线先留着位置（配置 instance.empty-recycle-minutes，默认 5 分钟），到期才摘出去
                    long since = slot.offlineSince.computeIfAbsent(uuid, key -> now);
                    if (now - since <= plugin.recycleMinutes() * 60_000L) {
                        continue;
                    }
                    Location back = slot.entranceOf.get(uuid);
                    if (back != null) {
                        pendingReturns.put(uuid, new ReturnTicket(back, slot.modeOf.get(uuid)));
                    }
                    if (slot.bar != null && player != null) {
                        slot.bar.removePlayer(player);
                    }
                    slot.entranceOf.remove(uuid);
                    slot.checkpointOf.remove(uuid);
                    slot.modeOf.remove(uuid);
                    slot.offlineSince.remove(uuid);
                    continue;
                }
                slot.offlineSince.remove(uuid);
                if (!isInstanceWorld(player.getWorld()) || slot.dungeon == null) {
                    continue;
                }
                for (int i = 0; i < slot.dungeon.checkpoints.size(); i++) {
                    Location checkpoint = slot.dungeon.checkpoints.get(i);
                    if (player.getWorld().equals(checkpoint.getWorld())
                            && player.getLocation().distanceSquared(checkpoint) <= 6.25) {
                        Location known = slot.checkpointOf.get(uuid);
                        if (known == null || known.distanceSquared(checkpoint) > 0.1) {
                            slot.checkpointOf.put(uuid, checkpoint);
                            player.sendMessage("§5[副本]§r §a检查点已记录 §7(第 " + (i + 1) + " 个)");
                        }
                    }
                }
            }
            try {
                startRoomsNearPlayers(slot);
                if (slot.failure == null) { pruneVanishedMobs(slot); }
                if (slot.failure == null) { checkBossRooms(slot); }
                if (slot.failure == null) { guardStuckRooms(slot); }
            } catch (RuntimeException e) {
                fail(slot, "战斗更新失败：" + e.getMessage());
            }
            if (slot.failure != null) {
                release(slot, "§c副本生成异常，本局结束，已扣体力已退还；本局未通关。");
                continue;
            }
            enforceGates(slot);
            refreshBar(slot);
            if (slot.entranceOf.isEmpty()) {
                if (slot.manualHold && completed(slot)) {
                    // 测试本打完就撒手，不用占着槽位等重启（没打完的话仍留到限时结束）
                    release(slot, "测试副本已通关，槽位自动释放。");
                } else if (!slot.manualHold) {
                    release(slot, "副本已清空，槽位回收。");
                }
            }
        }
    }

    /** 副本是否已通关：所有房间（含首领房）都清完了。 */
    private boolean completed(Slot slot) {
        if (slot.dungeon == null || slot.dungeon.rooms.isEmpty()) {
            return false;
        }
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (!room.cleared) {
                return false;
            }
        }
        return true;
    }

    /** 管理员手动回收一个槽位（里面的玩家会被送回入口）；本来就是空的返回 false。 */
    public boolean forceRelease(int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.busy) {
            return false;
        }
        release(slot, "管理员回收了这个副本槽位。");
        return true;
    }

    /** 管理员回收所有占用中的槽位，返回回收数量。 */
    public int forceReleaseAll() {
        int count = 0;
        for (Slot slot : slots.values()) {
            if (slot.busy) {
                release(slot, "管理员回收了这个副本槽位。");
                count++;
            }
        }
        return count;
    }

    public Location checkpointOf(UUID uuid) {
        Slot slot = byPlayer(uuid);
        return slot == null ? null : slot.checkpointOf.get(uuid);
    }

    /** 判断某个方块是不是"通关后出现的离开木门"。 */
    public boolean isExitDoor(Location location) {
        for (Slot slot : slots.values()) {
            if (!slot.busy || slot.dungeon == null) {
                continue;
            }
            for (Dungeon.Room room : slot.dungeon.rooms) {
                if (room.exitDoor != null && room.cleared
                        && room.exitDoor.getBlockX() == location.getBlockX()
                        && room.exitDoor.getBlockY() == location.getBlockY()
                        && room.exitDoor.getBlockZ() == location.getBlockZ()
                        && room.exitDoor.getWorld().equals(location.getWorld())) {
                    return true;
                }
            }
        }
        return false;
    }
}
