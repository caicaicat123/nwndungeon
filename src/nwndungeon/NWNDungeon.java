package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
    /** 1.5.0 的副本编辑器先关着（用户 2026-09-19：1.5.0 先放放，1.4.x 照改）。 */
    private static final boolean EDITOR_ENABLED = false;
    private final Map<String, Tier> tiers = new LinkedHashMap<>();
    private final Set<String> genWorlds = new HashSet<>();
    private final Set<Material> groundBlocks = new HashSet<>();
    private final Map<String, Integer> weights = new LinkedHashMap<>();

    private Entrances entrances;
    private EntranceRegistry entranceRegistry;
    private Instances instances;
    private Stamina stamina;
    private LootTables lootTables;
    private PanelConfig panelConfig;
    private final Map<Location, UUID> buttonPresser = new HashMap<>();
    private final java.util.Set<Location> openPanels = new java.util.HashSet<>();

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
    private final Map<String, MobTemplate> mobTemplates = new LinkedHashMap<>();
    private TemplateEditor editor;
    private NamespacedKey mobKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lootTables = LootTables.load(this);
        panelConfig = PanelConfig.load(this);
        loadSettings();
        entrances = new Entrances(this);
        entranceRegistry = new EntranceRegistry(this);
        instances = new Instances(this);
        stamina = new Stamina(this);
        if (EDITOR_ENABLED) {
            editor = new TemplateEditor(this);
        }
        mobKey = new NamespacedKey(this, "mob_template");

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

        loadMobs();
        registerPlaceholders();
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

    // ------------------------------------------------------------ 怪物模板

    private void loadMobs() {
        mobTemplates.clear();
        mobTemplates.putAll(MobTemplate.loadAll(this));
        getLogger().info("怪物模板 " + mobTemplates.size() + " 个："
                + (mobTemplates.isEmpty() ? "（无）" : String.join(", ", mobTemplates.keySet())));
    }

    public MobTemplate mobTemplate(String id) {
        return id == null ? null : mobTemplates.get(id.toLowerCase(Locale.ROOT));
    }

    public TemplateEditor editor() {
        return editor;
    }

    /** 给刷出来的怪打上"用的哪个怪物模板"标签，死亡时按模板发额外掉落。 */
    public void tagMob(LivingEntity entity, String templateId) {
        if (mobKey != null) {
            entity.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, templateId);
        }
    }

    /** 服务器装了 PlaceholderAPI 就注册占位符（%nwndungeon_stamina% 等，给菜单/记分板用）。 */
    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new StaminaPlaceholders(this).register();
            getLogger().info("PlaceholderAPI 接口已注册：%nwndungeon_stamina% / _max / _next / _next_text / _bar / _full，"
                    + "以及 cost_<难度> / money_<难度>");
        } catch (Throwable t) {
            getLogger().warning("PlaceholderAPI 接口注册失败：" + t);
        }
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
                tiers.put(key.toLowerCase(Locale.ROOT), Tier.from(key.toLowerCase(Locale.ROOT),
                        tierSection.getConfigurationSection(key), lootTables));
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
        if (stamina != null) {
            stamina.reload();
        }
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
        Location trigger = Ruins.build(world, x, y, z, tier, random);
        entrances.register(world.getBlockAt(x, y, z).getLocation(), tierId, trigger);
        entranceRegistry.add(world.getName(), x, y, z, tierId);
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
            entranceRegistry.markBroken(door.getWorld().getName(), door.getBlockX(), door.getBlockY(), door.getBlockZ());
            return;
        }
        if (!instances.ready()) {
            return;
        }
        if (casts.containsKey(door)) {
            return;   // 这扇门已经在读条了
        }
        List<Player> members = partyAt(door);
        if (members.isEmpty()) {
            return;
        }
        // 区块里不止一个人：先弹确认面板（谁按下按钮谁确认），确认后才开始读条
        UUID presserId = buttonPresser.remove(door);
        Player presser = presserId == null ? null : Bukkit.getPlayer(presserId);
        if (panelConfig != null && panelConfig.enabled && members.size() > 1 && presser != null
                && !openPanels.contains(door) && !casts.containsKey(door)) {
            openPanels.add(door);
            Tier tier = tier(entry.tier());
            EntryPanel.open(presser, door, tier == null ? entry.tier() : tier.display(), members, panelConfig);
            return;
        }
        startEntry(entry, door, members, members.size() > 1);
    }

    /**
     * 真正开始一次进本：查槽位 → 查体力 → 读条（或直接传送）。
     * lenient = 允许队员站得离门比较远（多人在区块里确认进本时用，不然读条会被"走远"判定取消）。
     */
    private void startEntry(Entrances.Entry entry, Location door, List<Player> members, boolean lenient) {
        if (door != null && casts.containsKey(door)) {
            return;
        }
        if (members.isEmpty()) {
            return;
        }
        if (!instances.hasFreeSlot()) {
            if (denyWhenFull) {
                members.forEach(p -> p.sendMessage("§5[副本]§r §c副本已满，稍后再来。"));
            }
            return;
        }
        // 体力检查：谁不够就不给进（够的话读条结束后统一扣）
        int cost = staminaCostFor(entry.tier());
        if (cost > 0) {
            List<String> poor = new ArrayList<>();
            for (Player player : members) {
                if (stamina.current(player) < cost) {
                    poor.add(player.getName() + "(" + stamina.current(player) + ")");
                }
            }
            if (!poor.isEmpty()) {
                members.forEach(p -> p.sendMessage("§5[副本]§r §c体力不足，本次需要 " + cost + " 点："
                        + String.join("、", poor)));
                members.forEach(p -> p.sendMessage("§7你的体力：" + stamina.describe(p)));
                return;
            }
        }
        if (castSeconds <= 0) {
            enter(entry, members);   // 配成 0 秒 = 老行为：直接传送
            return;
        }
        startCast(door, entry, members, lenient);
    }

    // ------------------------------------------------------------ 读条进本

    /** 读条中的一次进本（进度条 + 音效 + 粒子；跑远 / 死亡 / 掉线会取消）。 */
    private static final class Cast {
        final Location door;
        final String tierId;
        final List<UUID> party = new ArrayList<>();
        final BossBar bar;
        final boolean lenient;   // 多人在区块里确认进本：不因为离门远而掉队
        int ticks;
        int taskId = -1;

        Cast(Location door, String tierId, boolean lenient) {
            this.door = door;
            this.tierId = tierId;
            this.lenient = lenient;
            this.bar = Bukkit.createBossBar("§5进入副本", BarColor.PURPLE, BarStyle.SOLID);
        }
    }

    private void startCast(Location door, Entrances.Entry entry, List<Player> party, boolean lenient) {
        Cast cast = new Cast(door, entry.tier(), lenient);
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
                    || (!cast.lenient
                        && player.getLocation().distanceSquared(cast.door) > cancelDistance * cancelDistance);
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
        int cost = staminaCostFor(entry.tier());
        if (cost > 0) {
            for (Player player : party) {
                if (!stamina.spend(player, cost)) {
                    player.sendMessage("§5[副本]§r §c体力不足，本次没扣（需要 " + cost + " 点）。");
                }
            }
        }
        for (Player player : party) {
            sendInto(slot, player, tier == null ? entry.tier() : tier.display());
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
                entranceRegistry.markBroken(door.getWorld().getName(), door.getBlockX(), door.getBlockY(), door.getBlockZ());
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

    /** 房间里的怪物被击杀：先按怪物模板发额外掉落，再推进房间进度。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (mobKey != null && entity.getPersistentDataContainer().has(mobKey, PersistentDataType.STRING)) {
            MobTemplate template = mobTemplate(entity.getPersistentDataContainer()
                    .get(mobKey, PersistentDataType.STRING));
            if (template != null) {
                for (MobTemplate.DropSpec drop : template.drops()) {
                    if (random.nextDouble() > drop.chance()) {
                        continue;
                    }
                    int amount = drop.min() + random.nextInt(Math.max(1, drop.max() - drop.min() + 1));
                    event.getDrops().add(new ItemStack(drop.material(), Math.max(1, amount)));
                }
            }
        }
        if (instances.ready()) {
            instances.onMobDeath(entity);
        }
    }

    /** 通关后房间中央的木门：右键即离开副本。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !instances.ready()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // 副本入口按钮：记下是谁按的；红石事件进来时才知道该给谁弹"确认队伍"面板
        if (block.getType() == Material.STONE_BUTTON && !instances.isInstanceWorld(block.getWorld())) {
            Entrances.Entry entry = entrances.byTrigger(block.getLocation());
            if (entry != null && entry.alive() && entry.door() != null) {
                buttonPresser.put(entry.door(), event.getPlayer().getUniqueId());
            }
            return;
        }
        if (block.getType() != Material.OAK_DOOR) {
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
            return;
        }
        if (event.getInventory().getHolder() instanceof EntryPanel.Holder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            int slot = event.getRawSlot();
            if (slot == EntryPanel.CANCEL_SLOT) {
                openPanels.remove(holder.door());
                player.closeInventory();
                return;
            }
            if (slot == EntryPanel.CONFIRM_SLOT) {
                openPanels.remove(holder.door());
                player.closeInventory();
                Entrances.Entry entry = entrances.get(holder.door());
                if (entry != null && entry.alive()) {
                    startEntry(entry, holder.door(), partyAt(holder.door()), true);
                }
            }
        }
    }

    /** 关掉确认面板 = 取消（没点确认就不进本）。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof EntryPanel.Holder holder) {
            openPanels.remove(holder.door());
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

    /** 玩家重新上线：还在副本里就接续，否则（离线太久/副本已结束）送回进本前的位置，避免卡在副本世界。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!instances.ready()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> instances.resume(player), 1L);
    }

    // ------------------------------------------------------------ 指令

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§5[副本]§r /dungeon <spawn|test|leave|list|locate|tp|release|stamina|edit|template|reload>");
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
                    player.sendMessage(instances.restoreMode(player)
                            ? "§7你不在副本里，已把你切回原来的游戏模式。"
                            : "§7你不在副本里。");
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
                lootTables = LootTables.load(this);
                panelConfig = PanelConfig.load(this);
                loadSettings();
                loadMobs();
                sender.sendMessage("§a配置已重载。");
            }
            case "stamina" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只能由玩家使用。");
                    return true;
                }
                player.sendMessage("§5[副本]§r 你的体力：" + stamina.describe(player));
                player.sendMessage("§7每次进本消耗：普通 " + staminaCostFor("iron")
                        + " / 困难 " + staminaCostFor("gold") + " / 噩梦 " + staminaCostFor("diamond"));
            }
            case "locate" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只能由玩家使用（要知道你站在哪）。");
                    return true;
                }
                String filter = null;
                if (args.length > 1) {
                    String raw = args[1];
                    if (raw.equalsIgnoreCase("any") || raw.equalsIgnoreCase("all") || raw.equals("全部")) {
                        filter = null;
                    } else {
                        Tier match = tiers.values().stream()
                                .filter(t -> t.id().equalsIgnoreCase(raw)
                                        || stripColor(t.display()).equalsIgnoreCase(raw))
                                .findFirst().orElse(null);
                        if (match == null) {
                            player.sendMessage("§c没有这个副本名。可用：" + tierNames() + "，或 any（不限难度）");
                            return true;
                        }
                        filter = match.id();
                    }
                }
                List<EntranceRegistry.Entry> found = entranceRegistry.nearest(player.getLocation(), filter, 3);
                if (found.isEmpty()) {
                    player.sendMessage("§5[副本]§r §7名单里暂时没有"
                            + (filter == null ? "" : "这个难度的") + "入口。"
                            + "§8（新入口会在探索新区块时自动登记）");
                    return true;
                }
                player.sendMessage("§5[副本]§r §7离你最近的自然入口：");
                int index = 1;
                for (EntranceRegistry.Entry entry : found) {
                    Location location = entry.toLocation();
                    Tier tierOfEntry = tier(entry.tier);
                    String name = tierOfEntry == null ? entry.tier : tierOfEntry.display();
                    String where;
                    if (location == null) {
                        where = entry.world;
                    } else if (!location.getWorld().equals(player.getWorld())) {
                        where = entry.world + " §8(另一个世界)";
                    } else {
                        double dx = location.getX() - player.getLocation().getX();
                        double dz = location.getZ() - player.getLocation().getZ();
                        where = ((int) Math.sqrt(dx * dx + dz * dz)) + " 格 · " + EntranceRegistry.direction(dx, dz)
                                + " · §f" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
                    }
                    player.sendMessage("§7 " + index++ + ". §f" + name + " §7→ §f" + where);
                }
            }
            case "edit" -> {
                if (!EDITOR_ENABLED) {
                    sender.sendMessage("§7副本编辑器还在开发中（1.5.0 分支），当前版本先关闭。");
                    return true;
                }
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只能由玩家使用。");
                    return true;
                }
                handleEdit(player, args);
            }
            case "template" -> {
                if (!EDITOR_ENABLED) {
                    sender.sendMessage("§7模板副本还在开发中（1.5.0 分支），当前版本先关闭。");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只能由玩家使用。");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("§c用法：/dungeon template <模板名>");
                    return true;
                }
                Template template = editor.template(args[1]);
                if (template == null) {
                    player.sendMessage("§c没有这个模板。现有：" + namesOrNone());
                    return true;
                }
                if (instances.byPlayer(player.getUniqueId()) != null) {
                    player.sendMessage("§c你已经在副本里了。");
                    return true;
                }
                Instances.Slot slot = instances.allocateTemplate(template, List.of(player));
                if (slot == null) {
                    player.sendMessage("§c没有空闲槽位。");
                    return true;
                }
                sendInto(slot, player, template.display);
            }
            default -> sender.sendMessage("§c未知子命令。用法：/dungeon <spawn|test|leave|list|locate|tp|release|edit|template|reload>");
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

    /** 某个难度进本要多少体力（0 = 不消耗）。 */
    public int staminaCostFor(String tierId) {
        Tier tier = tier(tierId);
        if (tier == null || stamina == null || !stamina.enabled()) {
            return 0;
        }
        return tier.staminaCost();
    }

    /** 去掉 § 颜色代码（用来匹配玩家输入的难度名，比如输入"普通"）。 */
    private static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }

    /** "普通、困难、噩梦" 这样的难度名列表。 */
    private String tierNames() {
        List<String> names = new ArrayList<>();
        for (Tier tier : tiers.values()) {
            names.add(stripColor(tier.display()));
        }
        return String.join("、", names);
    }

    // ------------------------------------------------------------ 编辑器命令

    private void handleEdit(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§5[编辑器]§r /dungeon edit <new|save|cancel|pos1|pos2|spawn|checkpoint|chest|exit|room|wave|mob|spawnpoint|gate|info|test|list|delete>");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            player.sendMessage("§7模板：" + namesOrNone());
            return;
        }
        if (action.equals("new")) {
            if (args.length < 3) {
                player.sendMessage("§c用法：/dungeon edit new <模板名>（同名会把已保存的结构贴回来继续改）");
                return;
            }
            editor.open(player, args[2]);
            return;
        }
        if (action.equals("delete")) {
            if (args.length < 3) {
                player.sendMessage("§c用法：/dungeon edit delete <模板名>");
                return;
            }
            player.sendMessage(editor.delete(args[2]) ? "§a已删除模板 " + args[2] : "§c没有这个模板。");
            return;
        }
        if (action.equals("test")) {
            if (args.length < 3) {
                player.sendMessage("§c用法：/dungeon edit test <模板名>");
                return;
            }
            Template template = editor.template(args[2]);
            if (template == null) {
                player.sendMessage("§c没有这个模板。现有：" + namesOrNone());
                return;
            }
            Instances.Slot slot = instances.allocateTemplate(template, List.of(player));
            if (slot == null) {
                player.sendMessage("§c没有空闲槽位。");
                return;
            }
            sendInto(slot, player, template.display);
            player.sendMessage("§7测试本在槽位 #" + slot.index + "，打完最后一间自动释放。");
            return;
        }
        if (action.equals("reload")) {
            loadMobs();
            player.sendMessage("§a怪物模板已重载：" + mobTemplates.size() + " 个。");
            return;
        }
        TemplateEditor.Session session = editor.session(player);
        if (session == null) {
            player.sendMessage("§c你不在编辑器里。先 §f/dungeon edit new <模板名>§c（同名可继续编辑）。");
            return;
        }
        switch (action) {
            case "cancel" -> editor.close(player, session);
            case "save" -> editor.save(player, session);
            case "pos1" -> {
                session.pos1 = player.getLocation().getBlock().getLocation();
                player.sendMessage("§a角 1 = " + fmt(session.pos1));
            }
            case "pos2" -> {
                session.pos2 = player.getLocation().getBlock().getLocation();
                player.sendMessage("§a角 2 = " + fmt(session.pos2));
            }
            case "spawn" -> {
                session.spawn = feet(player);
                player.sendMessage("§a进本落点 = " + fmt(session.spawn));
            }
            case "checkpoint" -> {
                session.checkpoints.add(feet(player));
                player.sendMessage("§a检查点 +1（共 " + session.checkpoints.size() + " 个）");
            }
            case "exit" -> {
                session.exitPlate = player.getLocation().getBlock().getLocation();
                player.sendMessage("§a通关离开压力板 = " + fmt(session.exitPlate) + "（首领房清完会出现在这里）");
            }
            case "chest" -> {
                Block target = targetBlock(player);
                if (target == null) {
                    player.sendMessage("§c看着要放补给/奖励箱的方块再执行。");
                    return;
                }
                session.currentRoom().chest = target.getLocation();
                player.sendMessage("§a第 " + session.room + " 间的奖励箱 = " + fmt(target.getLocation()));
            }
            case "gate" -> {
                Block target = targetBlock(player);
                if (target == null || target.getType() != Material.IRON_DOOR) {
                    player.sendMessage("§c看着一扇铁门执行（会自动取下半格）。");
                    return;
                }
                Location door = target.getLocation();
                if (target.getBlockData() instanceof Door data && data.getHalf() == Bisected.Half.TOP) {
                    door = door.clone().subtract(0, 1, 0);
                }
                session.currentRoom().door = door;
                player.sendMessage("§a第 " + session.room + " 间的门登记完成（这一间清完会开门）。");
            }
            case "spawnpoint" -> {
                List<TemplateEditor.Mark> wave = session.currentRoom().waves
                        .computeIfAbsent(session.wave, key -> new ArrayList<>());
                wave.add(new TemplateEditor.Mark(feet(player), session.mob));
                player.sendMessage("§a第 " + session.room + " 间 / 第 " + session.wave + " 波 +1（怪："
                        + (session.mob == null ? "默认僵尸" : session.mob) + "）");
            }
            case "room" -> {
                if (args.length < 3) {
                    player.sendMessage("§c用法：/dungeon edit room <房间号>");
                    return;
                }
                session.room = Math.max(1, parseInt(args[2], session.room));
                player.sendMessage("§a当前房间 = 第 " + session.room + " 间");
            }
            case "wave" -> {
                if (args.length < 3) {
                    player.sendMessage("§c用法：/dungeon edit wave <波次>");
                    return;
                }
                session.wave = Math.max(1, parseInt(args[2], session.wave));
                player.sendMessage("§a当前波次 = 第 " + session.wave + " 波");
            }
            case "mob" -> {
                if (args.length < 3) {
                    player.sendMessage("§c用法：/dungeon edit mob <怪物模板|clear>。现有：" + mobNamesOrNone());
                    return;
                }
                if (args[2].equalsIgnoreCase("clear")) {
                    session.mob = null;
                    player.sendMessage("§a当前怪物 = 默认僵尸");
                } else if (mobTemplate(args[2]) == null) {
                    player.sendMessage("§c没有这个怪物模板。现有：" + mobNamesOrNone());
                } else {
                    session.mob = args[2].toLowerCase(Locale.ROOT);
                    player.sendMessage("§a当前怪物 = " + session.mob);
                }
            }
            case "info" -> printInfo(player, session);
            default -> player.sendMessage("§c未知的编辑动作，输入 /dungeon edit 看用法。");
        }
    }

    private void printInfo(Player player, TemplateEditor.Session session) {
        player.sendMessage("§5[模板] §f" + session.name + "§7  区域：" + fmt(session.plot));
        player.sendMessage("§7落点 " + (session.spawn == null ? "未设" : fmt(session.spawn))
                + " §7| 检查点 " + session.checkpoints.size()
                + " §7| 离开板 " + (session.exitPlate == null ? "未设" : fmt(session.exitPlate)));
        player.sendMessage("§7选区 " + (session.pos1 == null ? "未设" : fmt(session.pos1))
                + " → " + (session.pos2 == null ? "未设" : fmt(session.pos2))
                + " §7| 当前：第 " + session.room + " 间 / 第 " + session.wave + " 波 / 怪 "
                + (session.mob == null ? "默认" : session.mob));
        for (Map.Entry<Integer, TemplateEditor.RoomMark> entry : session.rooms.entrySet()) {
            TemplateEditor.RoomMark mark = entry.getValue();
            int spawns = mark.waves.values().stream().mapToInt(List::size).sum();
            player.sendMessage("§7  第 " + entry.getKey() + " 间：门 " + (mark.door == null ? "—" : "✓")
                    + " 板 " + (mark.plate == null ? "—" : "✓")
                    + " 箱 " + (mark.chest == null ? "—" : "✓")
                    + " 刷怪点 " + spawns);
        }
    }

    private void sendInto(Instances.Slot slot, Player player, String label) {
        slot.modeOf.put(player.getUniqueId(), player.getGameMode());
        instances.rememberMode(player, player.getGameMode());
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(slot.spawn);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        player.sendMessage("§5[副本]§r 进入 " + label + " §r副本，限时 " + slot.limitMinutes + " 分钟。");
        player.sendMessage("§7体力：" + stamina.describe(player));
        player.sendMessage("§7脚下的磁石平台就是第一个检查点；死亡会回到最近的检查点，物品不会掉落。");
        player.sendMessage("§7随时可以用 §f/dungeon leave §7离开副本。");
    }

    private Location feet(Player player) {
        return player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
    }

    private Block targetBlock(Player player) {
        return player.getTargetBlockExact(6);
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String namesOrNone() {
        List<String> names = editor.names();
        return names.isEmpty() ? "（无）" : String.join(", ", names);
    }

    private String mobNamesOrNone() {
        return mobTemplates.isEmpty() ? "（无，检查 mobs.yml）" : String.join(", ", mobTemplates.keySet());
    }

    private static String fmt(Location location) {
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Arrays.asList("spawn", "test", "leave", "list", "locate", "tp", "release", "stamina", "edit", "template", "reload").stream()
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

    public int recycleMinutes() {
        return recycleMinutes;
    }

    public Stamina stamina() {
        return stamina;
    }

}
