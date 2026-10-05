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
    /** 在"已加载的区块里"连续查不到这么多秒，才允许把这支怪从名单剔除（1.4.13）。 */
    private static final int MISSING_SECONDS_BEFORE_DROP = 5;
    /** 名单空着超过这么久还没推进 → 判定卡死，强制推进（1.4.13）。 */
    private static final long STUCK_ROOM_MS = 10_000L;
    /** 等下一波的时间远超 3 秒（说明刷怪任务没跑成）→ 强制重刷（1.4.13）。 */
    private static final long STUCK_WAVE_MS = 15_000L;
    /** 首领补刷上限，防止无限补刷（1.4.13）。 */
    private static final int MAX_BOSS_RESPAWNS = 3;

    public static final class Slot {
        public final int index;
        public final Location origin;
        public final Map<UUID, Location> entranceOf = new HashMap<>();
        public final Map<UUID, Location> checkpointOf = new HashMap<>();
        public final Map<UUID, GameMode> modeOf = new HashMap<>();
        public String tier = "iron";
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
        public boolean manualHold;
        public Dungeon dungeon;
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

    /** 分配空槽位并现场生成副本；没有空位返回 null。 */
    public Slot allocate(String tierId, List<Player> party) {
        if (!ready()) {
            return null;
        }
        Tier tier = plugin.tier(tierId);
        if (tier == null) {
            return null;
        }
        for (Slot slot : slots.values()) {
            if (slot.busy) {
                continue;
            }
            slot.busy = true;
            slot.manualHold = false;
            slot.tier = tierId;
            slot.entranceOf.clear();
            slot.checkpointOf.clear();
            slot.modeOf.clear();
            slot.deaths.clear();
            slot.kills = 0;
            slot.lastReward = new ArrayList<>();
            clearRegion(slot.lastPasteMin, slot.lastPasteMax);
            clear(slot);
            slot.lastPasteMin = null;
            slot.lastPasteMax = null;

            Dungeon dungeon = new DungeonBuilder().build(
                    world, slot.origin.getBlockX(), slot.origin.getBlockY(), slot.origin.getBlockZ(), tier);
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
                slot.entranceOf.put(player.getUniqueId(), player.getLocation());
                slot.checkpointOf.put(player.getUniqueId(), dungeon.spawn);
                bar.addPlayer(player);
            }

            // 大厅没有怪物，直接视为已清场：立刻给出通往第一间的按钮
            Dungeon.Room hall = dungeon.rooms.get(0);
            hall.cleared = true;
            grantRewards(slot, hall);
            refreshBar(slot);
            applyChunkTickets(slot);
            return slot;
        }
        return null;
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
                entity.remove();
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
                entity.remove();
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
            dropChunkTickets(slot);
        }
    }

    /** 房间清场：补给箱 + 按钮；首领房则给最终奖励箱和离开用的木门。 */
    public void grantRewards(Slot slot, Dungeon.Room room) {
        Tier tier = plugin.tier(slot.tier);
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
        if (room.cleared) {
            return;
        }
        int next = room.waveIndex + 1;
        if (next < room.waves.size()) {
            room.wavePending = true;
            room.pendingWave = next;
            room.emptySince = System.currentTimeMillis();
            Dungeon dungeon = slot.dungeon;
            announceWave(slot, room, next, true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!slot.busy || slot.dungeon != dungeon || room.cleared) {
                    return;   // 槽位已经回收或重开了，别再刷
                }
                DungeonBuilder.spawnWave(world, room, next, plugin.tier(slot.tier));
                announceWave(slot, room, next, false);
                refreshBar(slot);
            }, WAVE_DELAY_TICKS);
            return;
        }
        // 最后一波也清完了。1.4.13：首领房必须"确认击杀首领"才算通关 ——
        // 以前是"名单空了就算通关"，而活着的首领只要一时查不到（区块卸载）就会被当成死了，
        // 于是出现"首领还在却已经结算"。
        if (room.bossRoom && !room.bossKilled && ensureBossAlive(slot, room)) {
            return;
        }
        completeRoom(slot, room);
    }

    /** 这一间真的完成了：发奖励、清场广播，首领房额外弹结算界面。 */
    private void completeRoom(Slot slot, Dungeon.Room room) {
        if (room.cleared) {
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
     * 返回 false = 补刷次数用尽或压根刷不出来，调用方按通关处理（宁可放行，也别把玩家永久卡在这一间）。
     */
    private boolean ensureBossAlive(Slot slot, Dungeon.Room room) {
        if (room.bossId != null) {
            Entity existing = Bukkit.getEntity(room.bossId);
            if (existing instanceof LivingEntity living && !living.isDead() && living.isValid()) {
                if (!room.mobs.contains(room.bossId)) {
                    room.mobs.add(room.bossId);
                    room.lastSeen.put(room.bossId, living.getLocation());
                    room.missingSeconds.remove(room.bossId);
                    plugin.getLogger().warning("首领房：首领其实还活着（被误剔出名单），已重新纳入追踪");
                }
                return true;
            }
        }
        Tier tier = plugin.tier(slot.tier);
        if (room.center == null || tier == null || room.bossRespawns >= MAX_BOSS_RESPAWNS) {
            plugin.getLogger().warning("首领房：首领找不回、也补刷不了（已补 " + room.bossRespawns
                    + " 次），这一间按通关处理");
            return false;
        }
        room.bossRespawns++;
        DungeonBuilder.spawnBoss(world, room, tier);
        plugin.getLogger().warning("首领房：首领不见了，已在房间中央补刷第 " + room.bossRespawns + " 只");
        refreshBar(slot);
        return true;
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
                player.sendTitle("§c" + wave, "§7第 " + room.displayIndex + " 间", 5, 30, 10);
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
            Tier tier = plugin.tier(slot.tier);
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
        Tier tier = plugin.tier(slot.tier);
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

    /**
     * 兜底：怪物没留下死亡事件就消失了（被别的插件清掉、掉出世界等），
     * 把名单里已经不存在的实体剔掉；整间都空了就按清场处理，
     * 免得血条永远挂着"剩余 N 只"。
     *
     * <p><b>1.4.13 的两处加固</b>（这两条正是"活着的首领被判成已通关"的根源）：
     * <ol>
     *   <li>判断"查不到"时看的是**这只怪自己最后出现的位置**所在区块加载了没有，
     *       不再只看房间中心 —— 怪跑远了、房间中心还加载着，以前就会被误判成"已消失"；</li>
     *   <li>要连续 {@value #MISSING_SECONDS_BEFORE_DROP} 秒都查不到才剔除（以前是一秒一次直接删），
     *       瞬时抖动不会再把活怪误杀；剔除时打日志，含坐标，方便事后追溯。</li>
     * </ol>
     */
    private void pruneVanishedMobs(Slot slot) {
        if (slot.dungeon == null) {
            return;
        }
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.cleared || room.mobs.isEmpty()) {
                continue;
            }
            boolean changed = false;
            for (Iterator<UUID> it = room.mobs.iterator(); it.hasNext(); ) {
                UUID uuid = it.next();
                Entity entity = Bukkit.getEntity(uuid);
                if (entity != null && !entity.isDead() && entity.isValid()) {
                    // 还活着：刷新"最后已知位置"，并把失踪计数清零
                    room.lastSeen.put(uuid, entity.getLocation());
                    room.missingSeconds.remove(uuid);
                    continue;
                }
                Location seen = room.lastSeen.get(uuid);
                if (seen != null && seen.getWorld() != null
                        && !seen.getWorld().isChunkLoaded(seen.getBlockX() >> 4, seen.getBlockZ() >> 4)) {
                    // 它最后一次出现的地方区块没加载 —— 查不到很正常，别动名单
                    room.missingSeconds.remove(uuid);
                    continue;
                }
                int missing = room.missingSeconds.merge(uuid, 1, Integer::sum);
                if (missing < MISSING_SECONDS_BEFORE_DROP) {
                    continue;   // 再给它几秒：可能只是瞬时查不到
                }
                plugin.getLogger().warning("第 " + room.displayIndex + " 间有怪连续 "
                        + missing + " 秒查不到且区块是加载的，按已消失处理（最后位置 "
                        + (seen == null ? "未知"
                                : (seen.getBlockX() + "," + seen.getBlockY() + "," + seen.getBlockZ()))
                        + "）");
                it.remove();
                room.missingSeconds.remove(uuid);
                room.lastSeen.remove(uuid);
                changed = true;
            }
            if (changed && room.mobs.isEmpty() && !room.wavePending && !room.cleared) {
                advanceWave(slot, room);
            }
        }
    }

    /**
     * 卡死兜底（1.4.13）：名单空着却没推进、或"下一波"迟迟没刷出来，
     * 说明推进链路某一环断了（刷怪任务异常、提前 return 等）。超时后强制推进，
     * 避免整间永远清不掉、玩家再也拿不到结算。
     */
    private void guardStuckRooms(Slot slot) {
        if (slot.dungeon == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.index == 0 || room.cleared) {
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
                    DungeonBuilder.spawnWave(world, room, pending, plugin.tier(slot.tier));
                }
                continue;
            }
            if (!room.mobs.isEmpty()) {
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

    /** 怪物死亡后调用：推进房间进度。 */
    public boolean onMobDeath(Entity entity) {
        if (!ready() || !isInstanceWorld(entity.getWorld())) {
            return false;
        }
        for (Slot slot : slots.values()) {
            if (!slot.busy || slot.dungeon == null) {
                continue;
            }
            for (Dungeon.Room room : slot.dungeon.rooms) {
                if (room.mobs.remove(entity.getUniqueId())) {
                    room.kills++;
                    slot.kills++;
                    room.missingSeconds.remove(entity.getUniqueId());
                    room.lastSeen.remove(entity.getUniqueId());
                    // 首领"确认击杀"（收到死亡事件）才允许通关 —— 1.4.13
                    if (room.bossId != null && room.bossId.equals(entity.getUniqueId())) {
                        room.bossKilled = true;
                    }
                    if (room.mobs.isEmpty() && !room.wavePending && !room.cleared) {
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
            Entity entity = Bukkit.getEntity(target.bossId);
            double max = target.initialBossHealth;
            double health = entity instanceof LivingEntity living ? living.getHealth() : 0;
            if (entity instanceof LivingEntity living) {
                var attribute = living.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (attribute != null) {
                    max = attribute.getValue();
                }
            }
            slot.bar.setColor(BarColor.RED);
            slot.bar.setTitle("§c首领 §f" + (int) Math.max(0, health) + " / " + (int) max);
            slot.bar.setProgress(max <= 0 ? 0 : Math.max(0, Math.min(1, health / max)));
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
        for (UUID uuid : new ArrayList<>(slot.entranceOf.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
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
            if (!slot.busy) {
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
            pruneVanishedMobs(slot);
            guardStuckRooms(slot);
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
