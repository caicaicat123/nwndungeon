package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.GameRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** 副本世界的槽位管理：分配、生成、房间推进、传送、回收。 */
public final class Instances {

    /** 一波清完到下一波刷出的间隔（tick）。 */
    private static final long WAVE_DELAY_TICKS = 60L;

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
                Map<Integer, ItemStack> loot = rollLoot(tier.rewardItems(), 7, true);
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
            fillChest(room.chestSpot, tier.supplyItems(), 3, false);
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
    private void fillChest(Location location, List<LootEntry> pool, int maxTypes, boolean rich) {
        if (pool.isEmpty()) {
            return;
        }
        placeChest(location, rollLoot(pool, maxTypes, rich));
    }

    /** 先放箱子，下一 tick 再写内容（新放下的方块实体要等一 tick 才稳定）。 */
    private void placeChest(Location location, Map<Integer, ItemStack> loot) {
        location.getBlock().setType(Material.CHEST, false);
        Bukkit.getScheduler().runTask(plugin, () -> writeChest(location, loot));
    }

    /**
     * 抽奖励：随机格子 + 按权重抽物品 + 每件物品自己的数量区间（单箱 27 格）。
     * 先按 chance 筛出本次候选（chance=1 的一直在池里），再按 weight 加权抽取。
     */
    private Map<Integer, ItemStack> rollLoot(List<LootEntry> pool, int maxTypes, boolean rich) {
        int types = rich ? maxTypes : 1 + random.nextInt(Math.min(3, maxTypes));
        Map<Integer, ItemStack> loot = new LinkedHashMap<>();
        if (pool.isEmpty()) {
            return loot;
        }
        List<LootEntry> candidates = new ArrayList<>();
        for (LootEntry entry : pool) {
            if (entry.chance() >= 1.0 || random.nextDouble() < entry.chance()) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            candidates = pool;
        }
        int totalWeight = 0;
        for (LootEntry entry : candidates) {
            totalWeight += entry.weight();
        }
        while (loot.size() < types && loot.size() < 27) {
            int slot = random.nextInt(27);
            if (loot.containsKey(slot)) {
                continue;
            }
            LootEntry picked = pick(candidates, totalWeight);
            int min = picked.min() > 0 ? picked.min() : 1;
            int max = picked.max() > 0 ? picked.max() : (rich ? 4 : 2);
            if (max < min) {
                max = min;
            }
            int amount = min + random.nextInt(max - min + 1);
            loot.put(slot, new ItemStack(picked.material(), Math.max(1, amount)));
        }
        return loot;
    }

    private LootEntry pick(List<LootEntry> candidates, int totalWeight) {
        int roll = random.nextInt(Math.max(1, totalWeight));
        for (LootEntry entry : candidates) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** 下一 tick 执行：先按实时容器写，写不进再退回快照写法，两条路都读回来确认。 */
    private void writeChest(Location location, Map<Integer, ItemStack> loot) {
        BlockState state = location.getBlock().getState(false);
        if (!(state instanceof Chest chest)) {
            plugin.getLogger().warning("箱子没放成，跳过一个：" + location.getBlockX()
                    + "," + location.getBlockY() + "," + location.getBlockZ());
            return;
        }
        Inventory inventory = chest.getInventory();
        loot.forEach(inventory::setItem);
        if (chestFilled(inventory)) {
            return;
        }
        // 实时写入没生效：退回"快照 + update"的老写法，再等一 tick 试一次
        Bukkit.getScheduler().runTask(plugin, () -> {
            BlockState snapshot = location.getBlock().getState();
            if (snapshot instanceof Chest snap) {
                Inventory snapInventory = snap.getInventory();
                loot.forEach(snapInventory::setItem);
                snap.update(true, false);
                if (chestFilled(snapInventory)) {
                    return;
                }
            }
            plugin.getLogger().warning("箱子写入后回读仍为空：" + location.getBlockX()
                    + "," + location.getBlockY() + "," + location.getBlockZ());
        });
    }

    private boolean chestFilled(Inventory inventory) {
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() != Material.AIR) {
                return true;
            }
        }
        return false;
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
            Dungeon dungeon = slot.dungeon;
            announceWave(slot, room, next, true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!slot.busy || slot.dungeon != dungeon || room.cleared) {
                    return;   // 槽位已经回收或重开了，别再刷
                }
                spawnWave(slot, room, next);
                announceWave(slot, room, next, false);
                refreshBar(slot);
            }, WAVE_DELAY_TICKS);
            return;
        }
        room.cleared = true;
        grantRewards(slot, room);
        announceCleared(slot, room);
        if (room.bossRoom) {
            showSummary(slot);
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

    // ---------------------------------------------------------------- 模板副本

    private java.io.File templatesFolder() {
        return new java.io.File(plugin.getDataFolder(), "templates");
    }

    /** 刷一波怪：程序生成的副本用固定 9 个点位，模板副本用编辑时标记的刷怪点。 */
    private void spawnWave(Slot slot, Dungeon.Room room, int waveIndex) {
        if (room.plan.isEmpty()) {
            DungeonBuilder.spawnWave(world, room, waveIndex, plugin.tier(slot.tier));
            return;
        }
        if (waveIndex < 0 || waveIndex >= room.plan.size()) {
            return;
        }
        room.waveIndex = waveIndex;
        room.wavePending = false;
        room.mobs.clear();
        for (Dungeon.TemplateSpawn spawn : room.plan.get(waveIndex)) {
            MobTemplate mobTemplate = spawn.mob() == null ? null : plugin.mobTemplate(spawn.mob());
            Entity entity = world.spawnEntity(spawn.point(),
                    mobTemplate != null ? mobTemplate.type() : EntityType.ZOMBIE);
            if (entity == null) {
                continue;
            }
            entity.setPersistent(true);
            if (mobTemplate != null && entity instanceof LivingEntity living) {
                mobTemplate.apply(living);
                plugin.tagMob(living, mobTemplate.id());
            }
            room.mobs.add(entity.getUniqueId());
        }
        room.initialMobs = room.mobs.size();
    }

    /** 用模板开一个副本实例（模板副本，和程序生成的随机副本并存）。 */
    public Slot allocateTemplate(Template template, List<Player> party) {
        if (!ready() || template == null) {
            return null;
        }
        for (Slot slot : slots.values()) {
            if (slot.busy) {
                continue;
            }
            slot.busy = true;
            slot.manualHold = false;
            slot.tier = "template:" + template.name;
            slot.entranceOf.clear();
            slot.checkpointOf.clear();
            slot.modeOf.clear();
            slot.deaths.clear();
            slot.kills = 0;
            slot.lastReward = new ArrayList<>();
            if (slot.lastPasteMin != null && slot.lastPasteMax != null) {
                clearRegion(slot.lastPasteMin, slot.lastPasteMax);
            } else {
                clear(slot);
            }
            slot.lastPasteMin = null;
            slot.lastPasteMax = null;
            try {
                org.bukkit.util.BlockVector size = template.paste(templatesFolder(), slot.origin);
                slot.lastPasteMin = slot.origin.clone();
                slot.lastPasteMax = slot.origin.clone()
                        .add(size.getBlockX() - 1, size.getBlockY() - 1, size.getBlockZ() - 1);
            } catch (Exception e) {
                plugin.getLogger().warning("贴模板 " + template.name + " 失败：" + e.getMessage());
            }
            Dungeon dungeon = templateDungeon(template, slot.origin);
            slot.dungeon = dungeon;
            slot.spawn = dungeon.spawn;
            slot.startedAt = System.currentTimeMillis();
            slot.limitMinutes = Math.max(1, template.timeLimitMinutes);
            slot.deadline = slot.startedAt + slot.limitMinutes * 60_000L;
            if (slot.bar != null) {
                slot.bar.removeAll();
            }
            BossBar bar = Bukkit.createBossBar("§5副本", BarColor.PURPLE, BarStyle.SEGMENTED_20);
            slot.bar = bar;
            for (Player player : party) {
                slot.entranceOf.put(player.getUniqueId(), player.getLocation());
                slot.checkpointOf.put(player.getUniqueId(), dungeon.spawn);
                slot.modeOf.put(player.getUniqueId(), player.getGameMode());
                bar.addPlayer(player);
            }
            // 每间只刷第一波；没有配怪的房间直接算清场（压力板会立刻补上）
            for (Dungeon.Room room : dungeon.rooms) {
                if (room.index == 0) {
                    continue;
                }
                if (room.plan.isEmpty()) {
                    room.cleared = true;
                    grantRewards(slot, room);
                    continue;
                }
                spawnWave(slot, room, 0);
                room.initialMobs = room.mobs.size();
            }
            refreshBar(slot);
            return slot;
        }
        return null;
    }

    /** 模板数据 → 运行时结构（坐标从模板原点换算到槽位原点）。 */
    private Dungeon templateDungeon(Template template, Location origin) {
        Dungeon dungeon = new Dungeon();
        dungeon.spawn = template.at(world, origin, template.spawn);

        Dungeon.Room hall = new Dungeon.Room(0);
        hall.cleared = true;
        hall.origin = origin.clone();
        hall.center = dungeon.spawn;
        dungeon.rooms.add(hall);

        int index = 1;
        for (Template.Room def : template.rooms) {
            Dungeon.Room room = new Dungeon.Room(index);
            room.origin = origin.clone();
            room.bossRoom = def.boss;
            if (def.door != null) {
                room.doorLower = template.at(world, origin, def.door);
            }
            if (def.plate != null) {
                room.plateSpot = template.at(world, origin, def.plate);
            }
            if (def.chest != null) {
                room.chestSpot = template.at(world, origin, def.chest);
            }
            for (List<Template.Spawn> wave : def.waves) {
                List<Dungeon.TemplateSpawn> points = new ArrayList<>();
                for (Template.Spawn spawn : wave) {
                    points.add(new Dungeon.TemplateSpawn(template.at(world, origin, spawn.point()), spawn.mob()));
                }
                room.plan.add(points);
            }
            if (!room.plan.isEmpty() && !room.plan.get(0).isEmpty()) {
                room.center = room.plan.get(0).get(0).point();
            } else if (room.chestSpot != null) {
                room.center = room.chestSpot;
            } else {
                room.center = origin.clone();
            }
            if (def.boss && template.exitPlate != null) {
                room.exitPlate = template.at(world, origin, template.exitPlate);
            }
            dungeon.rooms.add(room);
            index++;
        }
        for (Template.Point point : template.checkpoints) {
            dungeon.checkpoints.add(template.at(world, origin, point));
        }
        int number = 0;
        for (Dungeon.Room room : dungeon.rooms) {
            if (!room.plan.isEmpty()) {
                room.displayIndex = ++number;
            }
        }
        return dungeon;
    }

    /** 模板副本：站到通关后出现的压力板上就离开副本。 */
    private void checkExitPlate(Slot slot) {
        if (slot.dungeon == null) {
            return;
        }
        Dungeon.Room last = null;
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.exitPlate != null) {
                last = room;
            }
        }
        if (last == null || !last.cleared) {
            return;
        }
        Location plate = last.exitPlate;
        if (!world.isChunkLoaded(plate.getBlockX() >> 4, plate.getBlockZ() >> 4)) {
            return;
        }
        if (!Tag.PRESSURE_PLATES.isTagged(plate.getBlock().getType())) {
            return;
        }
        for (UUID uuid : new ArrayList<>(slot.entranceOf.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.getWorld().equals(plate.getWorld())) {
                continue;
            }
            if (player.getLocation().distanceSquared(plate) <= 1.5 * 1.5) {
                leave(player, "你踩上压力板，从副本里走了出来。");
            }
        }
    }

    /**
     * 兜底：怪物没留下死亡事件就消失了（被别的插件清掉、掉出世界等），
     * 把名单里已经不存在的实体剔掉；整间都空了就按清场处理，
     * 免得血条永远挂着"剩余 N 只"。
     */
    private void pruneVanishedMobs(Slot slot) {
        if (slot.dungeon == null) {
            return;
        }
        for (Dungeon.Room room : slot.dungeon.rooms) {
            if (room.cleared || room.mobs.isEmpty() || room.center == null) {
                continue;
            }
            if (!room.center.getWorld().isChunkLoaded(
                    room.center.getBlockX() >> 4, room.center.getBlockZ() >> 4)) {
                continue;   // 区块没加载时查不到实体，先别动名单
            }
            boolean changed = false;
            for (Iterator<UUID> it = room.mobs.iterator(); it.hasNext(); ) {
                Entity entity = Bukkit.getEntity(it.next());
                if (entity == null || entity.isDead() || !entity.isValid()) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed && room.mobs.isEmpty() && !room.wavePending && !room.cleared) {
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
            enforceGates(slot);
            checkExitPlate(slot);
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
