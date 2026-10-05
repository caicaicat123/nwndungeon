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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /**
     * 老配置里还没有 {@code rules.allowed-commands} 这一段时用的兜底白名单。
     *
     * 没有它就会出现最坏的情况：升级 jar 但没动配置 → 白名单是空的 → 连 `/login` 都被拦，
     * 而掉线接续恰好会把玩家直接放回副本世界，人就真卡在里面了。
     *
     * 名单按服务器上**真实存在的命令**逐个核过（2026-09-27 用 RCON 试执行）：
     * `verification` 是 AuthMe 验证码命令的另一个名字（`/captcha` 的别名），1.4.11 一开始漏了它。
     */
    private static final List<String> DEFAULT_ALLOWED_COMMANDS = List.of(
            "msg", "tell", "w", "whisper", "r", "reply", "mail",
            "login", "l", "log", "register", "reg", "unregister", "unreg",
            "changepassword", "cp", "captcha", "verification", "email");
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

    // ---- v1.5.0 自建副本（管理员自己搭的副本，一局一个独立世界）----
    private WorldStore worldStore;
    private DungeonRegistry dungeonRegistry;
    /** 自建副本的编辑态（M2 起的图标面板 + 模板世界会话）。 */
    private Editor dungeonEditor;
    private InstanceWorlds instanceWorlds;
    private EditorPanel editorPanel;
    private ChatPrompt chatPrompt;
    private DevCommands devCommands;
    /** 删不掉的临时世界文件夹（一般是被句柄占着），下次启动再试。 */
    private final java.util.List<File> pendingDelete = new java.util.ArrayList<>();

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
    /** 副本里禁止使用其它命令（只放行 /dungeon 与 allowedCommands）。 */
    private boolean blockOtherCommands;
    private final Set<String> allowedCommands = new LinkedHashSet<>();
    private String instanceWorld;
    private int slotCount;
    private int slotSpacing;
    private int recycleMinutes;
    private int castSeconds;
    private double cancelDistance;
    private final Map<Location, Cast> casts = new HashMap<>();
    private final Map<String, MobTemplate> mobTemplates = new LinkedHashMap<>();
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
        mobKey = new NamespacedKey(this, "mob_template");

        // v1.5.0：自建副本（与世界复制路线）
        worldStore = new WorldStore(this);
        dungeonRegistry = new DungeonRegistry(this);
        dungeonRegistry.load();
        this.dungeonEditor = new Editor(this, worldStore, dungeonRegistry);
        instanceWorlds = new InstanceWorlds(this, worldStore, dungeonRegistry, this.dungeonEditor);
        chatPrompt = new ChatPrompt(this);
        editorPanel = new EditorPanel(this, dungeonRegistry, this.dungeonEditor, worldStore, chatPrompt);
        devCommands = new DevCommands(this, dungeonRegistry, worldStore, this.dungeonEditor,
                instanceWorlds, editorPanel, chatPrompt);
        loadPendingDelete();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("dungeon") != null) {
            getCommand("dungeon").setExecutor(this);
            getCommand("dungeon").setTabCompleter(this);
        }
        if (getCommand("nwmdungeon") != null) {
            getCommand("nwmdungeon").setExecutor(this);
            getCommand("nwmdungeon").setTabCompleter(this);
        }

        // 先清扫临时世界残留：这个版本的服务器**启动时会自动加载** dimensions 下的所有维度，
        // 崩溃留下的 nwndinst_* / nwndtpl_* 会被一起加载，白占内存
        getServer().getScheduler().runTask(this, () -> {
            worldStore.cleanupLeftovers();
            this.dungeonEditor.warnAboutLostSessions();
            retryPendingDelete();
        });
        // 建世界比较重，延后一tick执行，避免拖住启动
        getServer().getScheduler().runTask(this, () -> instances.setup());
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (instances.ready()) {
                instances.tick();
            }
            if (instanceWorlds != null) {
                instanceWorlds.tick();
            }
            if (dungeonEditor != null) {
                dungeonEditor.tick();
            }
        }, 40L, 20L);
        // 关卡门要关得更勤：光靠每秒一次，踩压力板那一瞬间足够挤过锁着的门
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (instanceWorlds != null) {
                instanceWorlds.fastTick();
            }
        }, 45L, 5L);

        loadMobs();
        registerPlaceholders();
        getLogger().info("副本插件已加载。入口生成=" + (generationEnabled ? "开" : "关")
                + "，副本世界=" + instanceWorld + "，自建副本=" + dungeonRegistry.size() + " 个");
    }

    @Override
    public void onDisable() {
        for (Cast cast : casts.values()) {
            cast.bar.removeAll();
        }
        casts.clear();
        // 撤掉给进行中的副本挂的区块票据，别把区块钉到下一个生命周期（1.4.13）
        if (instances != null) {
            instances.releaseAllTickets();
        }
        savePendingDelete();
        // 正在编辑的模板世界**不主动卸载**：它有自动保存，下次启动会被搬进
        // templates/_recovered/ 等管理员处理，不会丢
    }

    // ------------------------------------------------------------ 待删清单

    private File pendingDeleteFile() {
        return new File(getDataFolder(), "pending-delete.txt");
    }

    public void addPendingDelete(File folder) {
        if (folder == null) {
            return;
        }
        for (File existing : pendingDelete) {
            if (existing.getAbsolutePath().equals(folder.getAbsolutePath())) {
                return;
            }
        }
        pendingDelete.add(folder);
        savePendingDelete();
    }

    private void loadPendingDelete() {
        pendingDelete.clear();
        File file = pendingDeleteFile();
        if (!file.exists()) {
            return;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath())) {
                String text = line.trim();
                if (!text.isEmpty()) {
                    pendingDelete.add(new File(text));
                }
            }
        } catch (Exception e) {
            getLogger().warning("待删清单读不出来：" + e.getMessage());
        }
    }

    private void savePendingDelete() {
        try {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (File folder : pendingDelete) {
                lines.add(folder.getAbsolutePath());
            }
            java.nio.file.Files.write(pendingDeleteFile().toPath(), lines);
        } catch (Exception e) {
            getLogger().warning("待删清单写不进去：" + e.getMessage());
        }
    }

    private void retryPendingDelete() {
        if (pendingDelete.isEmpty()) {
            return;
        }
        java.util.List<File> left = new java.util.ArrayList<>();
        for (File folder : pendingDelete) {
            if (!folder.exists()) {
                continue;
            }
            if (WorldStore.deleteFolder(folder)) {
                getLogger().info("补删成功：" + folder.getName());
            } else {
                left.add(folder);
                getLogger().warning("还是删不掉：" + folder);
            }
        }
        pendingDelete.clear();
        pendingDelete.addAll(left);
        savePendingDelete();
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
        blockOtherCommands = cfg.getBoolean("rules.block-other-commands", true);
        allowedCommands.clear();
        // 配置里**压根没有**这一段（老配置升级上来的情况）→ 用兜底名单；
        // 显式写了空列表（用户就是想全拦）→ 尊重用户的写法，不用兜底
        List<String> rawAllowed = cfg.getStringList("rules.allowed-commands");
        if (rawAllowed.isEmpty() && !cfg.isSet("rules.allowed-commands")) {
            rawAllowed = DEFAULT_ALLOWED_COMMANDS;
        }
        for (String raw : rawAllowed) {
            String name = normalizeCommand(raw);
            if (!name.isEmpty()) {
                allowedCommands.add(name);
            }
        }
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
        // 内置难度入口与自建副本入口共用一次抽签（自建副本的权重写在它自己的 entrance.weight）
        DungeonDef dungeon = pickDungeonEntrance(world);
        if (dungeon != null) {
            placeEntrance(dungeon, new Location(world, x, y, z));
        } else {
            buildEntrance(world, x, y, z, pickTier());
        }
    }

    /**
     * 按权重抽一个"自建副本入口"；返回 null 表示这次该生成内置难度的遗迹入口。
     *
     * <p>权重表 = 内置难度（{@code generation.difficulty-weights}）+ 每个自建副本的
     * {@code entrance.weight}（前提是它开着 {@code entrance.random-generation}、
     * 世界在它的 {@code entrance.worlds} 里、而且已经存过入口结构）。
     */
    private DungeonDef pickDungeonEntrance(World world) {
        if (dungeonRegistry == null || dungeonRegistry.size() == 0) {
            return null;
        }
        List<DungeonDef> candidates = new ArrayList<>();
        List<Integer> weightsOfCandidates = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            total += Math.max(0, entry.getValue());
        }
        for (DungeonDef def : dungeonRegistry.all()) {
            if (!def.worldEnabled || !def.entranceRandom) {
                continue;
            }
            if (!def.entranceWorlds.isEmpty() && !def.entranceWorlds.contains(world.getName())) {
                continue;
            }
            EntranceTemplate template = EntranceTemplate.load(this, def.slug, def.name);
            if (template == null || !template.saved(this)) {
                continue;
            }
            candidates.add(def);
            weightsOfCandidates.add(Math.max(1, def.entranceWeight));
            total += Math.max(1, def.entranceWeight);
        }
        if (candidates.isEmpty() || total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            roll -= Math.max(0, entry.getValue());
            if (roll < 0) {
                return null;   // 落在内置难度那一档
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weightsOfCandidates.get(i);
            if (roll < 0) {
                return candidates.get(i);
            }
        }
        return null;
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

    /**
     * 把某个自建副本的**入口建筑**贴到指定位置（选区最小角对齐），并登记门 / 触发点。
     *
     * <p>登记用的是 v2 格式（显式记"哪一格是门、哪一格是开关"以及**门到底是什么材质**），
     * 所以管理员放木门、铜门、拉杆都能用 —— 这是计划书 R2 要修的东西。
     */
    public boolean placeEntrance(DungeonDef def, Location at) {
        if (def == null || at == null || at.getWorld() == null) {
            return false;
        }
        EntranceTemplate template = EntranceTemplate.load(this, def.slug, def.name);
        if (template == null || !template.saved(this)) {
            getLogger().warning("「" + def.name + "」还没有入口结构（用 /nwmdungeon new entrance create 搭一座）");
            return false;
        }
        EntranceTemplate.Placed placed = template.paste(this, template.alignTo(at));
        if (placed == null || placed.door() == null || placed.trigger() == null) {
            getLogger().warning("贴入口失败（" + def.name + "）：结构里没记门或触发点");
            return false;
        }
        Material material = placed.door().getBlock().getType();
        if (!Entrances.isDoorMaterial(material)) {
            getLogger().warning("入口门那一格贴出来不是门（是 " + material + "），结构或标记可能不对");
        }
        entrances.register(placed.door(), Entrances.KIND_DUNGEON, def.name, placed.trigger(), material);
        entranceRegistry.add(at.getWorld().getName(), placed.door().getBlockX(),
                placed.door().getBlockY(), placed.door().getBlockZ(), def.name);
        getLogger().info("贴出自建副本入口「" + def.name + "」@ " + at.getWorld().getName()
                + " " + placed.door().getBlockX() + "," + placed.door().getBlockY()
                + "," + placed.door().getBlockZ());
        return true;
    }

    /**
     * 自然生成的抽签池（给 {@code /nwmdungeon doctor} 用）：告诉管理员
     * "为什么我的入口不生成" —— 权重是多少、哪个副本因为什么被排除了。
     */
    public List<String> describeGenerationPool() {
        List<String> out = new ArrayList<>();
        if (!generationEnabled) {
            out.add("入口生成是关的（generation.enabled: false）");
            return out;
        }
        out.add("内置难度权重：" + (weights.isEmpty() ? "（没配）" : weights.toString()));
        out.add("允许生成的世界：" + String.join("、", genWorlds) + "（每 " + genChance + " 个新区块抽一次）");
        if (dungeonRegistry == null || dungeonRegistry.size() == 0) {
            out.add("自建副本：还没有（所以只生成内置遗迹入口）");
            return out;
        }
        for (DungeonDef def : dungeonRegistry.all()) {
            StringBuilder why = new StringBuilder();
            if (!def.worldEnabled) {
                why.append("world.enabled=false");
            }
            if (!def.entranceRandom) {
                append(why, "entrance.random-generation=false");
            }
            if (!def.entranceWorlds.isEmpty()) {
                why.append(why.length() > 0 ? "；" : "").append("只允许世界 ")
                        .append(String.join("、", def.entranceWorlds));
            }
            EntranceTemplate template = EntranceTemplate.load(this, def.slug, def.name);
            if (template == null || !template.saved(this)) {
                append(why, "还没存过入口结构");
            }
            out.add("自建副本「" + def.name + "」权重 " + def.entranceWeight
                    + (why.length() == 0 ? " → 参与抽签" : " → 不参与：" + why));
        }
        return out;
    }

    private static void append(StringBuilder builder, String text) {
        builder.append(builder.length() > 0 ? "；" : "").append(text);
    }

    /** locate 里一个入口显示成什么名字（难度名 / 自建副本名 / 原样）。 */
    private String locateLabel(String key) {
        Tier tier = tier(key);
        if (tier != null) {
            return tier.display();
        }
        DungeonDef def = dungeonRegistry == null ? null : dungeonRegistry.find(key);
        return def == null ? key : def.coloredDisplay();
    }

    /** 入口在提示里显示成什么（难度名或自建副本名）。 */
    private String entryLabel(Entrances.Entry entry) {
        if (entry == null) {
            return "?";
        }
        if (entry.isDungeon()) {
            DungeonDef def = dungeonRegistry == null ? null : dungeonRegistry.find(entry.target());
            return def == null ? entry.target() : def.coloredDisplay();
        }
        Tier tier = tier(entry.target());
        return tier == null ? entry.target() : tier.display();
    }

    // ------------------------------------------------------------ 红石触发

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getNewCurrent() <= 0 || event.getOldCurrent() > 0) {
            return;
        }
        Block block = event.getBlock();
        // 门是什么材质都行（木门 / 铁门 / 铜门）—— R2：不再硬编码铁门
        if (!(block.getBlockData() instanceof Door)) {
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
        if (!Entrances.isDoorMaterial(door.getBlock().getType())) {
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
        // 自建副本：先看它现在还能不能开（关掉了 / 不送人就只当装饰）
        DungeonDef dungeonDef = null;
        if (entry.isDungeon()) {
            dungeonDef = dungeonRegistry == null ? null : dungeonRegistry.find(entry.target());
            if (dungeonDef == null) {
                getLogger().warning("入口指向的自建副本不存在了：" + entry.target());
                return;
            }
            if (!dungeonDef.worldEnabled || !dungeonDef.entranceTeleport) {
                return;
            }
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
            EntryPanel.open(presser, door, entryLabel(entry), members, panelConfig);
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
        if (!instances.hasFreeSlot() && !entry.isDungeon()) {
            if (denyWhenFull) {
                members.forEach(p -> p.sendMessage("§5[副本]§r §c副本已满，稍后再来。"));
            }
            return;
        }
        // 体力检查：谁不够就不给进（够的话读条结束后统一扣）
        int cost = staminaCostOf(entry);
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

    /** 进本要多少体力（内置难度看 Tier，自建副本看副本配置）。 */
    private int staminaCostOf(Entrances.Entry entry) {
        if (entry == null) {
            return 0;
        }
        if (entry.isDungeon()) {
            DungeonDef def = dungeonRegistry == null ? null : dungeonRegistry.find(entry.target());
            if (def == null || stamina == null || !stamina.enabled()) {
                return 0;
            }
            return def.staminaCost;
        }
        return staminaCostFor(entry.target());
    }

    // ------------------------------------------------------------ 读条进本

    /** 读条中的一次进本（进度条 + 音效 + 粒子；跑远 / 死亡 / 掉线会取消）。 */
    private static final class Cast {
        final Location door;
        final String tierId;
        /** 显示用的名字（难度名或自建副本名）—— 自建副本没有 Tier，不能靠 tier(id) 反推。 */
        final String label;
        final List<UUID> party = new ArrayList<>();
        final BossBar bar;
        final boolean lenient;   // 多人在区块里确认进本：不因为离门远而掉队
        int ticks;
        int taskId = -1;

        Cast(Location door, String tierId, String label, boolean lenient) {
            this.door = door;
            this.tierId = tierId;
            this.label = label;
            this.lenient = lenient;
            this.bar = Bukkit.createBossBar("§5进入副本", BarColor.PURPLE, BarStyle.SOLID);
        }
    }

    private void startCast(Location door, Entrances.Entry entry, List<Player> party, boolean lenient) {
        Cast cast = new Cast(door, entry.target(), entryLabel(entry), lenient);
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
        String name = cast.label;
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

    /** 读条完成（或 0 秒配置）后真正进本：内置副本分槽位、自建副本复制一个世界。 */
    private void enter(Entrances.Entry entry, List<Player> party) {
        if (entry.isDungeon()) {
            DungeonDef def = dungeonRegistry == null ? null : dungeonRegistry.find(entry.target());
            if (def == null) {
                party.forEach(p -> p.sendMessage("§5[副本]§r §c这个副本已经不在了。"));
                return;
            }
            instanceWorlds.start(def, party, false);   // 体力、并发上限都在里面查
            return;
        }
        Tier tier = tier(entry.target());
        Instances.Slot slot = instances.allocate(entry.target(), party);
        if (slot == null) {
            if (denyWhenFull) {
                party.forEach(p -> p.sendMessage("§5[副本]§r §c副本已满，稍后再来。"));
            }
            return;
        }
        int cost = staminaCostOf(entry);
        if (cost > 0) {
            for (Player player : party) {
                if (!stamina.spend(player, cost)) {
                    player.sendMessage("§5[副本]§r §c体力不足，本次没扣（需要 " + cost + " 点）。");
                }
            }
        }
        for (Player player : party) {
            sendInto(slot, player, tier == null ? entry.target() : tier.display());
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
            if (same && perm(player, "use")) {
                result.add(player);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 副本内命令限制

    /**
     * 副本里只让用 /dungeon（外加配置里的白名单命令），其它命令一律拦掉。
     *
     * 两个要点：
     * - 用 LOWEST 优先级取消，这样 Bukkit 的原版命令分发根本不会发生；
     * - **登录类命令必须留在白名单里**：掉线接续会把玩家直接放回副本世界，
     *   这时 AuthMe 要玩家先 /login，要是连它都拦了人就真卡死了。
     *
     * 有 {@code nwndungeon.admin}（默认 op）的人不受限制，方便管理员在副本里调试。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!blockOtherCommands || instances == null || !instances.ready()) {
            return;
        }
        Player player = event.getPlayer();
        if (perm(player, "admin") || perm(player, "dev")) {
            return;   // 管理员与创作者不受限制，方便在副本里调试自己的东西
        }
        // 在自建副本的临时世界里也算"在副本里"（v1.5.0）
        boolean inside = instances.byPlayer(player.getUniqueId()) != null
                || (instanceWorlds != null && instanceWorlds.byPlayer(player.getUniqueId()) != null);
        if (!inside) {
            return;   // 不在副本里（含还在读条阶段）不限制
        }
        String label = commandLabel(event.getMessage());
        if (label.isEmpty() || label.equals("dungeon") || label.equals("nwmdungeon")
                || label.equals("nwd") || allowedCommands.contains(label)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage("§5[副本]§r §c副本里不能用这个命令。");
        player.sendMessage("§7离开副本：§f/dungeon leave§7；可用：§f" + allowedSummary());
    }

    /** 从 "/dungeon leave" 里取出命令名 "dungeon"（去掉斜杠、命名空间前缀与参数，转小写）。 */
    private static String commandLabel(String message) {
        if (message == null || message.length() < 2 || message.charAt(0) != '/') {
            return "";
        }
        String label = message.substring(1).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    /** 把配置里的白名单项统一成命令名（容忍写成 "/msg" 或 "MSG"）。 */
    private static String normalizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        return commandLabel("/" + text);
    }

    /** 提示里那串可用命令（太长就截断，别刷屏）。 */
    private String allowedSummary() {
        StringBuilder builder = new StringBuilder("/dungeon");
        int shown = 0;
        for (String allowed : allowedCommands) {
            if (shown++ >= 5) {
                builder.append(" …");
                break;
            }
            builder.append(", /").append(allowed);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------ 保护规则

    /** 是不是"玩家正在打的那个副本世界"（内置副本槽位 + v1.5.0 自建副本的临时世界）。 */
    private boolean inProtectedDungeonWorld(World world) {
        if (world == null) {
            return false;
        }
        if (instances != null && instances.ready() && instances.isInstanceWorld(world)) {
            return true;
        }
        return instanceWorlds != null && instanceWorlds.isInstanceWorld(world);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (dungeonEditor != null && dungeonEditor.isEditingWorld(event.getBlock().getWorld())) {
            // 编辑态里当然要能拆方块；但**拆掉箱子要顺手把该箱子的标记删掉**
            // （不然开本时插件还会照坐标放一个新箱子出来，而管理员明明已经清掉了）
            dungeonEditor.handleEditorBreak(player, event.getBlock());
            return;
        }
        if (inProtectedDungeonWorld(event.getBlock().getWorld())) {
            if (!allowBreak && player.getGameMode() != GameMode.CREATIVE) {
                event.setCancelled(true);
                player.sendMessage("§5[副本]§r §c副本里不能破坏地形。");
            }
            return;
        }
        Block block = event.getBlock();
        // 入口门被拆：门是什么材质都行（R2）
        if (block.getBlockData() instanceof Door) {
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
        if (dungeonEditor != null && dungeonEditor.isEditingWorld(event.getBlock().getWorld())) {
            return;
        }
        if (!inProtectedDungeonWorld(event.getBlock().getWorld())) {
            return;
        }
        if (!allowPlace && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§5[副本]§r §c副本里不能放置方块。");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (disableExplosions && inProtectedDungeonWorld(event.getEntity().getWorld())) {
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
        // v1.5.0 自建副本：这只怪属于哪一局就推进哪一局
        if (instanceWorlds != null) {
            DungeonRun run = instanceWorlds.byWorld(entity.getWorld());
            if (run != null && run.onMobDeath(entity)) {
                return;
            }
        }
        if (instances.ready()) {
            instances.onMobDeath(entity);
        }
    }

    /** 通关后房间中央的木门：右键即离开副本；自建副本的关卡门在没清场时不许推开。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player handPlayer = event.getPlayer();
        // ⓪ 编辑时钟：拿着它右键（对着方块或对空气都行）就打开编辑菜单 —— 不用每次打命令
        if (dungeonEditor != null && editorPanel != null
                && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && dungeonEditor.isClock(event.getItem())) {
            event.setCancelled(true);
            if (dungeonEditor.entranceSession(handPlayer) != null) {
                editorPanel.openEntranceEditor(handPlayer);
            } else if (dungeonEditor.byPlayer(handPlayer.getUniqueId()) != null) {
                editorPanel.openEditor(handPlayer);
            } else {
                dungeonEditor.takeClock(handPlayer);   // 已经不在编辑态了，收走免得留着碍事
                editorPanel.openList(handPlayer);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.PHYSICAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // ① v1.5.0 自建副本：关卡门锁 / 出口门离开（用事件拦，光靠"每秒关一次"拦不住）
        if (instanceWorlds != null) {
            DungeonRun run = instanceWorlds.byPlayer(event.getPlayer().getUniqueId());
            if (run != null) {
                if (block.getBlockData() instanceof Door) {
                    Location lower = Markers.doorLower(block);
                    if (run.gateLocked(lower)) {
                        event.setCancelled(true);
                        event.getPlayer().sendMessage("§5[副本]§r §c这一关还没清完，门打不开。");
                        return;
                    }
                    if (run.isExitDoor(lower)) {
                        event.setCancelled(true);
                        instanceWorlds.leave(event.getPlayer(), "你从副本里走了出来。");
                        return;
                    }
                } else if (event.getAction() == Action.PHYSICAL
                        && run.gateLockedNear(block.getLocation(), 3.0)) {
                    // 门旁边的压力板/按钮：也不许拿来给锁着的门通电
                    event.setCancelled(true);
                    return;
                }
            }
        }
        // ② 入口触发点（按钮 / 压力板 / 拉杆都行 —— 不再只认石头按钮）：
        // 记下是谁按的；红石事件进来时才知道该给谁弹"确认队伍"面板
        if (Entrances.isTriggerMaterial(block.getType()) && !instances.isInstanceWorld(block.getWorld())) {
            Entrances.Entry entry = entrances.byTrigger(block.getLocation());
            if (entry != null && entry.alive() && entry.door() != null) {
                buttonPresser.put(entry.door(), event.getPlayer().getUniqueId());
            }
            return;
        }
        // ③ 内置副本：通关后的离开木门
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !instances.ready()) {
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
        // 编辑器面板（v1.5.0 自建副本）
        if (editorPanel != null && editorPanel.handleClick(event)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof Instances.SummaryHolder) {
            event.setCancelled(true);
            return;
        }
        if (event.getInventory().getHolder() instanceof DungeonRun.SummaryHolder) {
            event.setCancelled(true);   // 自建副本的结算界面同样只读
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

    /** 关掉确认面板 = 取消（没点确认就不进本）；关掉奖励箱界面 = 把内容写进 loot.yml。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (editorPanel != null && editorPanel.handleClose(event)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof EntryPanel.Holder holder) {
            openPanels.remove(holder.door());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (disableExplosions && inProtectedDungeonWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ 聊天输入（编辑器里问名字等）

    /**
     * 捕获"编辑器要的下一步输入"（例如新建副本时的名字）。
     *
     * <p>用 {@link org.bukkit.event.player.AsyncPlayerChatEvent} 而不是 Paper 的新聊天事件，
     * 是因为新事件给的是 Adventure 组件，而把组件转成纯文本要 {@code adventure-text-serializer-plain}
     * —— 它不在我们编译用的 classpath 里（M0 探针踩过这个坑）。这里只需要原始字符串，用老事件最省事。
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        if (chatPrompt == null || !chatPrompt.isWaiting(event.getPlayer())) {
            return;
        }
        if (chatPrompt.handle(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);   // 吃掉这条消息，别广播出去
        }
    }

    // ------------------------------------------------------------ 死亡与退出

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // v1.5.0 自建副本：记死亡次数 + 按 rules.keep-inventory-on-death 处理
        if (instanceWorlds != null) {
            DungeonRun run = instanceWorlds.byPlayer(player.getUniqueId());
            if (run != null) {
                run.onPlayerDeath(player);
                if (keepInventory) {
                    event.setKeepInventory(true);
                    event.getDrops().clear();
                    event.setKeepLevel(true);
                    event.setDroppedExp(0);
                }
                return;
            }
        }
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
        if (instanceWorlds != null) {
            DungeonRun run = instanceWorlds.byPlayer(player.getUniqueId());
            if (run != null && run.world().equals(player.getWorld())) {
                // 自建副本：回到最近踩到的、且脚下方块没被改过的检查点
                event.setRespawnLocation(run.respawnFor(player.getUniqueId()));
                return;
            }
        }
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
        if (chatPrompt != null) {
            chatPrompt.forget(event.getPlayer());
        }
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
        Bukkit.getScheduler().runTaskLater(this, () -> {
            instances.resume(player);
            if (instanceWorlds != null) {
                instanceWorlds.resume(player);
            }
        }, 1L);
    }

    // ------------------------------------------------------------ 指令

    /** 权限节点按 nwd.* 给；老的 nwndungeon.* 照样认，免得已经发出去的权限组失效。 */
    private static boolean perm(CommandSender sender, String shortName) {
        return sender.hasPermission("nwd." + shortName)
                || sender.hasPermission("nwndungeon." + shortName);
    }

    /** 不带参数时打什么。 */
    private void printUsage(CommandSender sender) {
        sender.sendMessage("§5[副本]§r /nwd <子命令>（也写作 /dungeon、/nwmdungeon，三个名字一样）");
        sender.sendMessage("§7 玩家：§fleave§7(离开) §flocate§7(找入口) §fstamina§7(体力) §fhelp");
        if (perm(sender, "admin")) {
            sender.sendMessage("§7 管理：§fspawn <难度>§7 §ftest <难度>§7 §flist§7 §ftp§7 §frelease§7 §freload");
        }
        if (perm(sender, "dev")) {
            sender.sendMessage("§7 创作：§fedit§7(图标面板) §fnew§7(标记/世界) §ftest <副本名>§7 "
                    + "§fspawn <副本名>§7 §fend§7 §flist§7 §fdelete§7 §fdoctor§7 §fhelp");
        }
        sender.sendMessage("§7 看详细：§f/nwd help§7（管理）· §f/nwd help 1§7 起翻页看创作命令");
    }

    /** 一次把两边的配置都重载了（统一 reload）。 */
    private void reloadEverything(CommandSender sender) {
        boolean admin = perm(sender, "admin");
        boolean dev = perm(sender, "dev");
        if (!admin && !dev) {
            sender.sendMessage("§c你没有权限。");
            return;
        }
        if (admin) {
            lootTables = LootTables.load(this);
            panelConfig = PanelConfig.load(this);
            loadSettings();
            loadMobs();
        }
        if (dev) {
            devCommands.handle(sender, new String[]{"reload"});
            return;
        }
        sender.sendMessage("§a配置已重载。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /dungeon = /nwd = /nwmdungeon，三个名字完全等价；按**子命令名**分发。
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // 只属于自建副本工具的（没有重名，直接转过去）
        if (sub.equals("edit") || sub.equals("new") || sub.equals("delete")
                || sub.equals("end") || sub.equals("doctor")) {
            return devCommands.handle(sender, args);
        }
        // 两边同名的 test / spawn：参数是内置难度名就走内置，否则当自建副本名
        if ((sub.equals("test") || sub.equals("spawn"))
                && !(args.length >= 2 && tier(args[1]) != null)
                && sender instanceof Player) {
            return devCommands.handle(sender, args);
        }
        if (sub.equals("reload")) {
            reloadEverything(sender);
            return true;
        }
        if (args.length == 0) {
            printUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                // 三个名字共用一个命令树，所以 help 也要能翻到"创作命令"那几页
                if (args.length >= 2 && perm(sender, "dev")) {
                    String topic = args[1].toLowerCase(Locale.ROOT);
                    boolean devTopic = topic.equals("dev") || topic.equals("edit") || topic.equals("new")
                            || topic.equals("delete") || topic.equals("end")
                            || topic.equals("doctor") || topic.equals("spawn") || topic.equals("test");
                    if (devTopic) {
                        return devCommands.handle(sender, topic.equals("dev")
                                ? new String[]{"help", "1"}
                                : new String[]{"help", topic});
                    }
                }
                Help.send(sender, "/nwd", perm(sender, "admin")
                        ? Help.GROUP_ADMIN : Help.GROUP_PLAYER, args);
            }
            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只能由玩家使用。");
                    return true;
                }
                if (!perm(player, "use")) {
                    player.sendMessage("§c你没有权限。");
                    return true;
                }
                if (instanceWorlds != null && instanceWorlds.byPlayer(player.getUniqueId()) != null) {
                    instanceWorlds.leave(player, "你离开了副本。");
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
                        if (match != null) {
                            filter = match.id();
                        } else {
                            // 也认自建副本的名字（它们的入口同样登记在 entrances.yml 里）
                            DungeonDef def = dungeonRegistry == null ? null : dungeonRegistry.find(raw);
                            if (def == null) {
                                player.sendMessage("§c没有这个副本名。可用：" + tierNames()
                                        + (dungeonRegistry != null && dungeonRegistry.size() > 0
                                        ? "、" + String.join("、", dungeonRegistry.names()) : "")
                                        + "，或 any（不限难度）");
                                return true;
                            }
                            filter = def.name;
                        }
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
            case "edit", "template" -> {
                sender.sendMessage("§7老的半成品编辑器已经退役了（v1.5.0 起换成自建副本工具）。");
                sender.sendMessage("§7用 §f/nwmdungeon edit§7 打开图标面板，或 §f/nwmdungeon help§7 看用法。");
            }
            default -> sender.sendMessage("§c未知子命令。用法：/dungeon <spawn|test|leave|list|locate|tp|release|stamina|help|reload>");
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!perm(sender, "admin")) {
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

    // ------------------------------------------------------------ 老编辑器（已删除）
    //
    // 原先这里是 /dungeon edit / /dungeon template 的实现（handleEdit / printInfo / namesOrNone），
    // 对应的 TemplateEditor 会话编辑器在 1.5.0 已退役（改用 /nwmdungeon edit 的图标面板）。
    // 骨架类 Template / TemplateEditor 暂时留着：M5 做入口建筑要抽里面的结构读写。

    /** 把玩家送进内置副本的槽位（自建副本走 InstanceWorlds，不用这一条）。 */
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

    private static String fmt(Location location) {
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 三个名字共用一个命令树，第一层给两边的并集
        if (perm(sender, "dev") && args.length >= 2) {
            String sub0 = args[0].toLowerCase(Locale.ROOT);
            if (sub0.equals("new") || sub0.equals("edit") || sub0.equals("delete")
                    || sub0.equals("end") || sub0.equals("doctor")) {
                return devCommands.complete(sender, args);
            }
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> all = new ArrayList<>(Arrays.asList("spawn", "test", "leave", "list",
                    "locate", "tp", "release", "stamina", "help", "reload"));
            if (perm(sender, "dev")) {
                all.addAll(Arrays.asList("edit", "new", "delete", "end", "doctor"));
            }
            return all.stream().filter(s -> s.startsWith(prefix)).distinct()
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return new ArrayList<>(tiers.keySet());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            return Arrays.stream(new String[]{"1", "2"}).collect(Collectors.toList());
        }
        return List.of();
    }

    // ------------------------------------------------------------ 对外接口

    public Tier tier(String id) {
        return id == null ? null : tiers.get(id.toLowerCase(Locale.ROOT));
    }

    /** 掉落表（含每个副本的随机附魔池）；`/dungeon reload` 会换成新实例，别缓存它。 */
    public LootTables lootTables() {
        return lootTables;
    }

    /** 跑一遍自检（{@code /nwmdungeon doctor}；DevCommands 只是转发）。 */
    public boolean runSelfTest(CommandSender sender) {
        return new SelfTest(this, worldStore, entrances).run(sender);
    }

    /** 重新读一遍 loot.yml（编辑器写完奖励箱之后调用）。 */
    public void reloadLoot() {
        lootTables = LootTables.load(this);
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
