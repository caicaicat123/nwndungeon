package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 随机副本入口 + 独立副本世界。
 *
 * 入口是一扇带标记的铁门（标记写在区块的持久化数据里），用红石激活后，
 * 同区块内的玩家一起传送到副本世界里的一个空槽位。门被破坏后永久失效。
 */
public final class NWNDungeon extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Random random = new Random();
    private final Map<String, Tier> tiers = new LinkedHashMap<>();
    private final Set<String> genWorlds = new HashSet<>();
    private final Set<Material> groundBlocks = new HashSet<>();
    private final Map<String, Integer> weights = new LinkedHashMap<>();

    private Entrances entrances;
    private Instances instances;

    private boolean generationEnabled;
    private int genChance;
    private int minY;
    private int maxY;
    private int triggerRadius;
    private boolean denyWhenFull;
    private boolean keepInventory;
    private boolean allowBreak;
    private boolean allowPlace;
    private boolean disableExplosions;
    private boolean returnToEntrance;
    private String instanceWorld;
    private int slotCount;
    private int slotSpacing;
    private int recycleMinutes;
    private int castSeconds;
    private double cancelDistance;
    private final Map<Location, Cast> casts = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        entrances = new Entrances(this);
        instances = new Instances(this);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("dungeon") != null) {
            getCommand("dungeon").setExecutor(this);
            getCommand("dungeon").setTabCompleter(this);
        }

        // 建世界比较重，延后一tick执行，避免拖住启动
        getServer().getScheduler().runTask(this, () -> instances.setup());
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (instances.ready()) {
                instances.tick();
            }
        }, 40L, 20L);

        getLogger().info("副本插件已加载。入口生成=" + (generationEnabled ? "开" : "关")
                + "，副本世界=" + instanceWorld);
    }

    @Override
    public void onDisable() {
        for (Cast cast : casts.values()) {
            cast.bar.removeAll();
        }
        casts.clear();
    }

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();

        genWorlds.clear();
        genWorlds.addAll(cfg.getStringList("worlds"));
        generationEnabled = cfg.getBoolean("generation.enabled", true);
        genChance = Math.max(1, cfg.getInt("generation.chance", 400));
        minY = cfg.getInt("generation.min-y", 60);
        maxY = cfg.getInt("generation.max-y", 120);
        groundBlocks.clear();
        for (String raw : cfg.getStringList("generation.ground-blocks")) {
            Material material = Material.matchMaterial(raw);
            if (material != null) {
                groundBlocks.add(material);
            }
        }
        weights.clear();
        ConfigurationSection weightSection = cfg.getConfigurationSection("generation.difficulty-weights");
        if (weightSection != null) {
            for (String key : weightSection.getKeys(false)) {
                weights.put(key.toLowerCase(Locale.ROOT), weightSection.getInt(key));
            }
        }

        triggerRadius = cfg.getInt("trigger.radius", -1);
        denyWhenFull = cfg.getBoolean("trigger.deny-when-full", true);
        castSeconds = Math.max(0, cfg.getInt("trigger.cast-seconds", 3));
        cancelDistance = Math.max(1, cfg.getInt("trigger.cancel-distance", 6));

        instanceWorld = cfg.getString("instance.world", "nwndungeon");
        slotCount = Math.max(1, cfg.getInt("instance.slots", 8));
        slotSpacing = Math.max(300, cfg.getInt("instance.slot-spacing", 600));
        recycleMinutes = Math.max(1, cfg.getInt("instance.empty-recycle-minutes", 5));

        tiers.clear();
        ConfigurationSection tierSection = cfg.getConfigurationSection("tiers");
        if (tierSection != null) {
            for (String key : tierSection.getKeys(false)) {
                tiers.put(key.toLowerCase(Locale.ROOT), Tier.from(key.toLowerCase(Locale.ROOT), tierSection.getConfigurationSection(key)));
            }
        }
        for (Tier loaded : tiers.values()) {
            int waves = loaded.rooms().stream().mapToInt(List::size).sum();
            getLogger().info("难度 " + loaded.id() + "：" + loaded.rooms().size()
                    + " 个战斗房 / 共 " + waves + " 波，首领房 " + loaded.bossRoomWaves().size() + " 波");
        }

        keepInventory = cfg.getBoolean("rules.keep-inventory-on-death", true);
        allowBreak = cfg.getBoolean("rules.allow-block-break", false);
        allowPlace = cfg.getBoolean("rules.allow-block-place", false);
        disableExplosions = cfg.getBoolean("rules.disable-explosions", true);
        returnToEntrance = cfg.getBoolean("rules.return-to-entrance", true);
    }

    // ------------------------------------------------------------ 入口生成

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!generationEnabled || !event.isNewChunk()) {
            return;
        }
        World world = event.getWorld();
        if (instances.ready() && instances.isInstanceWorld(world)) {
            return;
        }
        if (!genWorlds.contains(world.getName())) {
            return;
        }
        if (random.nextInt(genChance) != 0) {
            return;
        }
        Chunk chunk = event.getChunk();
        int x = (chunk.getX() << 4) + 8;
        int z = (chunk.getZ() << 4) + 8;
        int surface = world.getHighestBlockYAt(x, z);
        int groundY = surface;
        while (groundY > world.getMinHeight() && world.getBlockAt(x, groundY, z).getType() == Material.AIR) {
            groundY--;
        }
        Material ground = world.getBlockAt(x, groundY, z).getType();
        int y = groundY + 1;
        if (y < minY || y > maxY || !groundBlocks.contains(ground)) {
            return;
        }
        buildEntrance(world, x, y, z, pickTier());
    }

    private String pickTier() {
        if (weights.isEmpty()) {
            return "iron";
        }
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(Math.max(1, total));
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return weights.keySet().iterator().next();
    }

    /** 生成一处入口：按难度生成"遗迹"建筑 + 带标记的铁门 + 难度方块（门上/门下各一块）+ 台座按钮 + 告示牌。 */
    public void buildEntrance(World world, int x, int y, int z, String tierId) {
        Tier tier = tier(tierId);
        if (tier == null) {
            return;
        }
        Ruins.build(world, x, y, z, tier, random);
        entrances.register(world.getBlockAt(x, y, z).getLocation(), tierId);
        getLogger().info("生成副本入口 " + tierId + " @ " + world.getName()
                + " " + x + "," + y + "," + z);
    }

    // ------------------------------------------------------------ 红石触发

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getNewCurrent() <= 0 || event.getOldCurrent() > 0) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.IRON_DOOR) {
            return;
        }
        Location door = block.getLocation();
        if (block.getBlockData() instanceof Door data && data.getHalf() == Bisected.Half.TOP) {
            door = door.clone().subtract(0, 1, 0);
        }
        trigger(door);
    }

    private void trigger(Location door) {
        Entrances.Entry entry = entrances.get(door);
        if (entry == null || !entry.alive()) {
            return;
        }
        if (door.getBlock().getType() != Material.IRON_DOOR) {
            // 门已经不在了（可能被绕过事件破坏），就地作废
            entrances.markBroken(door);
            return;
        }
        if (!instances.ready()) {
            return;
        }
        if (casts.containsKey(door)) {
            return;   // 这扇门已经在读条了
        }
        List<Player> party = partyAt(door);
        if (party.isEmpty()) {
            return;
        }
        if (!instances.hasFreeSlot()) {
            if (denyWhenFull) {
                party.forEach(p -> p.sendMessage("§5[副本]§r §c副本已满，稍后再来。"));
            }
            return;
        }
        if (castSeconds <= 0) {
            enter(entry, party);   // 配成 0 秒 = 老行为：直接传送
            return;
        }
        startCast(door, entry, party);
    }

    // ------------------------------------------------------------ 读条进本

    /** 读条中的一次进本（进度条 + 音效 + 粒子；跑远 / 死亡 / 掉线会取消）。 */
    private static final class Cast {
        final Location door;
        final String tierId;
        final List<UUID> party = new ArrayList<>();
        final BossBar bar;
        int ticks;
        int taskId = -1;

        Cast(Location door, String tierId) {
            this.door = door;
            this.tierId = tierId;
            this.bar = Bukkit.createBossBar("§5进入副本", BarColor.PURPLE, BarStyle.SOLID);
        }
    }

    private void startCast(Location door, Entrances.Entry entry, List<Player> party) {
        Cast cast = new Cast(door, entry.tier());
        for (Player player : party) {
            cast.party.add(player.getUniqueId());
            cast.bar.addPlayer(player);
            player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 1.2f);
        }
        casts.put(door, cast);
        cast.taskId = getServer().getScheduler()
                .runTaskTimer(this, () -> tickCast(cast), 1L, 1L).getTaskId();
    }

    private void tickCast(Cast cast) {
        Tier tier = tier(cast.tierId);
        String name = tier == null ? cast.tierId : tier.display();
        for (UUID uuid : new ArrayList<>(cast.party)) {
            Player player = Bukkit.getPlayer(uuid);
            boolean gone = player == null || !player.isOnline() || player.isDead()
                    || !player.getWorld().equals(cast.door.getWorld())
                    || player.getLocation().distanceSquared(cast.door) > cancelDistance * cancelDistance;
            if (gone) {
                if (player != null) {
                    cast.bar.removePlayer(player);
                    player.sendMessage("§5[副本]§r §c你离开了入口，本次进入取消。");
                }
                cast.party.remove(uuid);
            }
        }
        if (cast.party.isEmpty()) {
            stopCast(cast);
            return;
        }
        cast.ticks++;
        double progress = Math.min(1.0, cast.ticks / (double) (castSeconds * 20));
        cast.bar.setTitle("§5进入 " + name + " §7· §f" + (int) Math.round(progress * 100) + "%");
        cast.bar.setProgress(progress);
        if (cast.ticks % 4 == 0) {
            Location center = cast.door.clone().add(0.5, 1.0, 0.5);
            cast.door.getWorld().spawnParticle(Particle.PORTAL, center, 12, 0.4, 0.8, 0.4, 0.02);
        }
        if (cast.ticks >= castSeconds * 20) {
            List<Player> ready = new ArrayList<>();
            for (UUID uuid : cast.party) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    ready.add(player);
                }
            }
            Entrances.Entry entry = entrances.get(cast.door);
            stopCast(cast);
            if (entry != null && entry.alive() && !ready.isEmpty()) {
                enter(entry, ready);
            }
        }
    }

    private void stopCast(Cast cast) {
        if (cast.taskId != -1) {
            getServer().getScheduler().cancelTask(cast.taskId);
            cast.taskId = -1;
        }
        cast.bar.removeAll();
        casts.remove(cast.door);
    }

    /** 读条完成（或 0 秒配置）后真正进本：分配槽位 + 传送。 */
    private void enter(Entrances.Entry entry, List<Player> party) {
        Tier tier = tier(entry.tier());
        Instances.Slot slot = instances.allocate(entry.tier(), party);
        if (slot == null) {
            if (denyWhenFull) {
                party.forEach(p -> p.sendMessage("§5[副本]§r §c副本已满，稍后再来。"));
            }
            return;
        }
        for (Player player : party) {
            slot.modeOf.put(player.getUniqueId(), player.getGameMode());
            player.setGameMode(GameMode.ADVENTURE);
            player.teleport(slot.spawn);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            player.sendMessage("§5[副本]§r 进入 " + (tier == null ? entry.tier() : tier.display())
                    + " §r副本，限时 " + (tier == null ? 20 : tier.timeLimitMinutes()) + " 分钟。");
            player.sendMessage("§7死亡会回到最近的检查点，物品不会掉落。输入 §f/dungeon leave §7离开。");
        }
    }

    private List<Player> partyAt(Location door) {
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(door.getWorld())) {
                continue;
            }
            boolean same = triggerRadius <= 0
                    ? player.getChunk().equals(door.getChunk())
                    : player.getLocation().distanceSquared(door) <= (double) triggerRadius * triggerRadius;
            if (same && player.hasPermission("nwndungeon.use")) {
                result.add(player);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 保护规则

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (instances.ready() && instances.isInstanceWorld(event.getBlock().getWorld())) {
            if (!allowBreak && player.getGameMode() != GameMode.CREATIVE) {
                event.setCancelled(true);
                player.sendMessage("§5[副本]§r §c副本里不能破坏地形。");
            }
            return;
        }
        Block block = event.getBlock();
        if (block.getType() == Material.IRON_DOOR) {
            Location door = block.getLocation();
            if (block.getBlockData() instanceof Door data && data.getHalf() == Bisected.Half.TOP) {
                door = door.clone().subtract(0, 1, 0);
            }
            if (entrances.markBroken(door)) {
                player.sendMessage("§5[副本]§r §c入口已被破坏，此处永久失效。");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!instances.ready() || !instances.isInstanceWorld(event.getBlock().getWorld())) {
            return;
        }
        if (!allowPlace && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§5[副本]§r §c副本里不能放置方块。");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (disableExplosions && instances.ready() && instances.isInstanceWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** 房间里的怪物被击杀：推进房间进度、刷新血条、必要时发放补给与按钮。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (instances.ready()) {
            instances.onMobDeath(event.getEntity());
        }
    }

    /** 通关后房间中央的木门：右键即离开副本。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !instances.ready()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.OAK_DOOR) {
            return;
        }
        if (!instances.isInstanceWorld(block.getWorld())) {
            return;
        }
        Location lower = block.getLocation();
        if (block.getBlockData() instanceof Door data && data.getHalf() == Bisected.Half.TOP) {
            lower = lower.clone().subtract(0, 1, 0);
        }
        if (instances.isExitDoor(lower)) {
            event.setCancelled(true);
            instances.leave(event.getPlayer(), "你从副本里走了出来。");
        }
    }

    /** 结算界面只读：拦掉玩家在里面的任何点击。 */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Instances.SummaryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (disableExplosions && instances.ready() && instances.isInstanceWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ 死亡与退出

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!instances.ready() || !instances.isInstanceWorld(player.getWorld())) {
            return;
        }
        instances.onPlayerDeath(player);
        if (keepInventory) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!instances.ready() || !instances.isInstanceWorld(player.getWorld())) {
            return;
        }
        Location checkpoint = instances.checkpointOf(player.getUniqueId());
        if (checkpoint != null) {
            event.setRespawnLocation(checkpoint);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (instances.ready()) {
            instances.tick();
        }
    }

    // ------------------------------------------------------------ 指令

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§5[副本]§r /dungeon <spawn|test|leave|list|tp|release|reload>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只能由玩家使用。");
                    return true;
                }
                if (!player.hasPermission("nwndungeon.use")) {
                    player.sendMessage("§c你没有权限。");
                    return true;
                }
                if (instances.byPlayer(player.getUniqueId()) == null) {
                    player.sendMessage("§7你不在副本里。");
                    return true;
                }
                instances.leave(player, "你离开了副本。");
            }
            case "spawn" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!instances.ready()) {
                    sender.sendMessage("§c副本世界还没准备好。");
                    return true;
                }
                String tierId = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "iron";
                if (tier(tierId) == null) {
                    sender.sendMessage("§c未知难度，可选：" + String.join(", ", tiers.keySet()));
                    return true;
                }
                Location target;
                if (args.length >= 5) {
                    World world = args.length >= 6 ? Bukkit.getWorld(args[5])
                            : (sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0));
                    if (world == null) {
                        sender.sendMessage("§c找不到这个世界。");
                        return true;
                    }
                    try {
                        target = new Location(world, Integer.parseInt(args[2]),
                                Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§c坐标必须是整数。用法：/dungeon spawn <难度> <x> <y> <z> [世界]");
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    // 放在玩家面前 3 格，避免直接盖在脚下
                    Location front = player.getLocation().clone();
                    var direction = front.getDirection().setY(0);
                    if (direction.lengthSquared() > 0) {
                        direction.normalize().multiply(3);
                    }
                    target = front.add(direction);
                } else {
                    sender.sendMessage("§c控制台用法：/dungeon spawn <难度> <x> <y> <z> [世界]");
                    return true;
                }
                int x = target.getBlockX();
                int y = target.getBlockY() + 1;
                int z = target.getBlockZ();
                World world = target.getWorld();
                while (world.getBlockAt(x, y - 1, z).getType() == Material.AIR && y > world.getMinHeight()) {
                    y--;
                }
                buildEntrance(world, x, y, z, tierId);
                sender.sendMessage("§a已在你的位置生成一个 " + tierId + " 难度入口。");
            }
            case "test" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!instances.ready()) {
                    sender.sendMessage("§c副本世界还没准备好。");
                    return true;
                }
                String tierId = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "iron";
                if (tier(tierId) == null) {
                    sender.sendMessage("§c未知难度，可选：" + String.join(", ", tiers.keySet()));
                    return true;
                }
                Instances.Slot slot = instances.allocate(tierId, List.of());
                if (slot == null) {
                    sender.sendMessage("§c没有空闲槽位。");
                    return true;
                }
                slot.manualHold = true;
                Location spawn = slot.spawn;
                sender.sendMessage("§a槽位 #" + slot.index + " 已生成 " + tierId + " 难度副本。");
                sender.sendMessage("§7原点 " + slot.origin.getBlockX() + "," + slot.origin.getBlockY()
                        + "," + slot.origin.getBlockZ() + "  出生点 "
                        + spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ());
            }
            case "list" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!instances.ready()) {
                    sender.sendMessage("§c副本世界还没准备好。");
                    return true;
                }
                sender.sendMessage("§5[副本]§r 槽位状态：");
                for (Instances.Slot slot : instances.all()) {
                    sender.sendMessage("§7 #" + slot.index + (slot.busy
                            ? " §a占用 §7难度=" + slot.tier + " 人数=" + slot.entranceOf.size()
                              + " 剩余=" + Math.max(0, (slot.deadline - System.currentTimeMillis()) / 60000) + "分"
                            : " §8空闲"));
                }
            }
            case "tp" -> {
                if (!requireAdmin(sender) || !(sender instanceof Player player)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c用法：/dungeon tp <槽位号>");
                    return true;
                }
                int index;
                try {
                    index = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c槽位号必须是数字。");
                    return true;
                }
                Instances.Slot target = instances.all().stream()
                        .filter(s -> s.index == index).findFirst().orElse(null);
                if (target == null) {
                    sender.sendMessage("§c没有这个槽位。");
                    return true;
                }
                Location spawn = target.busy && target.spawn != null ? target.spawn
                        : target.origin.clone().add(12, 2, 12);
                player.teleport(spawn);
                sender.sendMessage("§a已传送到槽位 #" + index + "。");
            }
            case "release" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!instances.ready()) {
                    sender.sendMessage("§c副本世界还没准备好。");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c用法：/dungeon release <槽位号|all>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    int count = instances.forceReleaseAll();
                    sender.sendMessage(count == 0 ? "§7没有占用中的槽位。"
                            : "§a已回收 " + count + " 个槽位。");
                    return true;
                }
                int slotIndex;
                try {
                    slotIndex = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c槽位号必须是数字，或者 all。");
                    return true;
                }
                sender.sendMessage(instances.forceRelease(slotIndex)
                        ? "§a已回收槽位 #" + slotIndex + "。"
                        : "§7槽位 #" + slotIndex + " 本来就是空闲的。");
            }
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                loadSettings();
                sender.sendMessage("§a配置已重载。");
            }
            default -> sender.sendMessage("§c未知子命令。用法：/dungeon <spawn|test|leave|list|tp|release|reload>");
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("nwndungeon.admin")) {
            sender.sendMessage("§c需要 nwndungeon.admin 权限。");
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Arrays.asList("spawn", "test", "leave", "list", "tp", "release", "reload").stream()
                    .filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return new ArrayList<>(tiers.keySet());
        }
        return List.of();
    }

    // ------------------------------------------------------------ 对外接口

    public Tier tier(String id) {
        return id == null ? null : tiers.get(id.toLowerCase(Locale.ROOT));
    }

    public String instanceWorldName() {
        return instanceWorld;
    }

    public int slotCount() {
        return slotCount;
    }

    public int slotSpacing() {
        return slotSpacing;
    }

    public boolean returnToEntrance() {
        return returnToEntrance;
    }

}
