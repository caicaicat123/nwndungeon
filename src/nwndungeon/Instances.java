package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
        public int kills;
        public final Map<UUID, Integer> deaths = new HashMap<>();
        public List<ItemStack> lastReward = new ArrayList<>();
        public boolean busy;
        public boolean manualHold;
        public Dungeon dungeon;
        public BossBar bar;

        Slot(int index, Location origin) {
            this.index = index;
            this.origin = origin;
        }
    }

    private final NWNDungeon plugin;
    private final Random random = new Random();
    private final Map<Integer, Slot> slots = new LinkedHashMap<>();
    private World world;

    public Instances(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    public World world() {
        return world;
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
            clear(slot);

            Dungeon dungeon = new DungeonBuilder().build(
                    world, slot.origin.getBlockX(), slot.origin.getBlockY(), slot.origin.getBlockZ(), tier);
            slot.dungeon = dungeon;
            slot.spawn = dungeon.spawn;
            slot.startedAt = System.currentTimeMillis();
            slot.deadline = slot.startedAt + tier.timeLimitMinutes() * 60_000L;

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

    /** 房间清场：补给箱 + 按钮；首领房则给最终奖励箱和离开用的木门。 */
    public void grantRewards(Slot slot, Dungeon.Room room) {
        Tier tier = plugin.tier(slot.tier);
        if (room.bossRoom) {
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
        if (room.buttonSpot != null) {
            placeButton(room.buttonSpot);
        }
    }

    /** 清场后出现的石按钮：按一下给铁门通电（约 1 秒），够一个人走过去。 */
    private void placeButton(Location location) {
        Block block = location.getBlock();
        block.setType(Material.STONE_BUTTON, false);
        if (block.getBlockData() instanceof Switch button) {
            button.setFace(Switch.Face.FLOOR);
            button.setFacing(BlockFace.NORTH);
            button.setPowered(false);
            block.setBlockData(button, false);
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
            if (room.buttonSpot != null) {
                Material type = room.buttonSpot.getBlock().getType();
                if (type == Material.LEVER || Tag.BUTTONS.isTagged(type)) {
                    room.buttonSpot.getBlock().setType(Material.AIR, false);
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
    private void fillChest(Location location, List<Material> pool, int maxTypes, boolean rich) {
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

    /** 抽奖励：随机格子 + 随机数量（单箱 27 格）。 */
    private Map<Integer, ItemStack> rollLoot(List<Material> pool, int maxTypes, boolean rich) {
        int types = rich ? maxTypes : 1 + random.nextInt(Math.min(3, maxTypes));
        Map<Integer, ItemStack> loot = new LinkedHashMap<>();
        while (loot.size() < types && loot.size() < 27) {
            int slot = random.nextInt(27);
            if (loot.containsKey(slot)) {
                continue;
            }
            Material material = pool.get(random.nextInt(pool.size()));
            int amount = rich ? 1 + random.nextInt(4) : 1 + random.nextInt(2);
            loot.put(slot, new ItemStack(material, amount));
        }
        return loot;
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
                        : "§5[副本]§r §a第 " + (room.index + 1) + " 间已清空，门旁出现按钮，角落出现补给箱。");
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
                DungeonBuilder.spawnWave(world, room, next, plugin.tier(slot.tier));
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
                player.sendTitle("§e下一波 3 秒后", "§7第 " + (room.index + 1) + " 间 · " + wave, 5, 40, 10);
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
            } else {
                player.sendTitle("§c" + wave, "§7第 " + (room.index + 1) + " 间", 5, 30, 10);
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
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.openInventory(summaryInventory(slot, player, elapsed, deaths, rank));
        }
    }

    /** 评级：用时（相对限时）+ 死亡次数。S = 半场以内且零死亡；A = 3/4 限时以内且最多死一次；其余 B。 */
    private String rank(Slot slot, long elapsed, int deaths) {
        Tier tier = plugin.tier(slot.tier);
        long limit = (tier == null ? 20 : tier.timeLimitMinutes()) * 60_000L;
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
        int limit = tier == null ? 20 : tier.timeLimitMinutes();
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
            slot.bar.setTitle("§e第 " + (target.index + 1) + " 间" + wave + " §7· §f下一波准备中");
            slot.bar.setProgress(1.0);
            return;
        }
        slot.bar.setColor(BarColor.PURPLE);
        slot.bar.setTitle("§e第 " + (target.index + 1) + " 间" + wave + " §7· §f剩余 " + target.mobs.size() + " 只");
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
                player.sendMessage("§5[副本]§r " + reason);
            }
        }
        if (slot.bar != null) {
            slot.bar.removeAll();
            slot.bar = null;
        }
        slot.entranceOf.clear();
        slot.checkpointOf.clear();
        slot.modeOf.clear();
        slot.dungeon = null;
        slot.busy = false;
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
                    if (slot.bar != null && player != null) {
                        slot.bar.removePlayer(player);
                    }
                    slot.entranceOf.remove(uuid);
                    slot.checkpointOf.remove(uuid);
                    slot.modeOf.remove(uuid);
                    continue;
                }
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
