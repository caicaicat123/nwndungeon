package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编辑态：把模板复制成一份**真世界**（{@code nwndtpl_<slug>}）让管理员进去搭，保存时再搬回
 * {@code templates/worlds/<slug>/}。
 *
 * <p>为什么要"复制出来改、保存时搬回去"：模板本身必须是一份**平时不加载的文件夹**
 * （放在 {@code plugins/} 下不会被当成维度扫到，不占内存）；而搭副本必须要有一个真世界。
 * 这样还顺便拿到了"改崩了也不会毁掉正在用的模板"这条退路 —— 放弃 = 删掉临时那份。
 */
public final class Editor {

    /** 一次编辑会话。 */
    public static final class Session {
        public final UUID player;
        public final String dungeonName;
        public final String slug;
        public final String worldName;
        public final String generator;
        public final Location back;
        public final GameMode backMode;
        public final boolean backAllowFlight;
        public final boolean backFlying;
        /** 面板里"当前关卡 / 当前波次"（刷怪点按它登记）—— 0 基，显示时 +1。 */
        public int stage;
        public int wave = 1;
        /** 面板里"显示标记提示"开关。 */
        public boolean showMarkers = true;
        /** 已经提示过"失效"的检查点下标（避免每秒刷屏）。 */
        public final java.util.Set<Integer> brokenCheckpoints = new java.util.HashSet<>();

        Session(UUID player, DungeonDef def, String worldName, Location back, GameMode backMode,
                boolean backAllowFlight, boolean backFlying) {
            this.player = player;
            this.dungeonName = def.name;
            this.slug = def.slug;
            this.worldName = worldName;
            this.generator = def.generator;
            this.back = back;
            this.backMode = backMode;
            this.backAllowFlight = backAllowFlight;
            this.backFlying = backFlying;
        }
    }

    private final NWNDungeon plugin;
    private final WorldStore store;
    private final DungeonRegistry registry;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public Editor(NWNDungeon plugin, WorldStore store, DungeonRegistry registry) {
        this.plugin = plugin;
        this.store = store;
        this.registry = registry;
    }

    private File sessionFile() {
        return new File(plugin.getDataFolder(), "edit-sessions.yml");
    }

    public Session byPlayer(UUID uuid) {
        return sessions.get(uuid);
    }

    public Session byWorld(String worldName) {
        for (Session session : sessions.values()) {
            if (session.worldName.equals(worldName)) {
                return session;
            }
        }
        return null;
    }

    public boolean isEditingWorld(World world) {
        return world != null && byWorld(world.getName()) != null;
    }

    public int activeCount() {
        return sessions.size();
    }

    // ---------------------------------------------------------------- 进入

    /**
     * 打开（或新建）编辑态。
     *
     * @param fromTemplate true = 把已保存的模板复制出来继续改；false = 从空世界开始新建
     * @return 出错时返回原因文本，成功返回 null
     */
    public String open(Player player, DungeonDef def, String generator, boolean fromTemplate) {
        if (sessions.containsKey(player.getUniqueId())) {
            return "你已经在编辑「" + sessions.get(player.getUniqueId()).dungeonName + "」了，先保存或放弃。";
        }
        String worldName = Names.templateWorld(def.slug);
        for (Session existing : sessions.values()) {
            if (existing.worldName.equals(worldName)) {
                return "「" + def.name + "」正被另一个管理员编辑中。";
            }
        }
        if (!Names.isValidWorldName(worldName)) {
            return "世界名不合法（" + worldName + "），检查配置里的 slug。";
        }
        File folder = store.folderOf(worldName);
        if (folder.exists()) {
            // 上一次没收拾干净：先卸掉，再挪进 templates/_recovered/（里面可能是别人搭了一半的活，不硬删）
            World stale = Bukkit.getWorld(worldName);
            if (stale != null) {
                store.unload(stale, false);
            }
            File recovered = new File(store.templatesRoot().getParentFile(), "_recovered");
            if (!recovered.isDirectory()) {
                recovered.mkdirs();
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File target = new File(recovered, folder.getName() + "-" + stamp);
            if (!folder.renameTo(target) && !WorldStore.deleteFolder(folder)) {
                return "旧的编辑世界文件夹既挪不动也删不掉：" + folder.getName();
            }
        }
        if (fromTemplate) {
            File template = store.templateFolder(def.slug);
            if (!WorldStore.looksLikeWorld(template)) {
                return "还没保存过模板（" + template.getName() + " 不存在），先用「新建」搭一座。";
            }
            try {
                store.copyFolder(template, folder);       // 纯文件 IO，量很小（M0 实测通常 < 1 MB）
            } catch (Exception e) {
                plugin.getLogger().warning("复制模板失败：" + e);
                return "复制模板失败：" + e.getMessage();
            }
        }
        World world = store.create(worldName, generator, true, false);
        if (world == null) {
            WorldStore.deleteFolder(folder);
            return "世界创建失败，看控制台日志（世界名 " + worldName + "）。";
        }
        if (!fromTemplate) {
            // 空世界是虚空，先铺一块落脚台，不然管理员一进去就往虚空里掉
            buildStarterPad(world, def);
        }
        File actual = world.getWorldFolder();
        if (!actual.getAbsolutePath().equals(folder.getAbsolutePath())) {
            plugin.getLogger().warning("世界文件夹和预期不一致：预期 " + folder + "，实际 " + actual
                    + "（不影响使用，插件以实际路径为准）");
        }
        def.generator = DungeonDef.normalizeGenerator(generator);
        registry.save();

        Session session = new Session(player.getUniqueId(), def, worldName,
                player.getLocation().clone(), player.getGameMode(),
                player.getAllowFlight(), player.isFlying());
        sessions.put(player.getUniqueId(), session);
        writeSessionFile();

        giveClock(player);
        Location target = startLocation(def, world);
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.teleport(target);
        return null;
    }

    /** 进本落点：配置里标过就用标记的，否则用世界出生点。 */
    public Location startLocation(DungeonDef def, World world) {
        if (def.markers.start != null && def.markers.start.length >= 3) {
            return new Location(world, def.markers.start[0] + 0.5, def.markers.start[1], def.markers.start[2] + 0.5);
        }
        Location spawn = world.getSpawnLocation();
        return new Location(world, spawn.getBlockX() + 0.5, spawn.getBlockY(), spawn.getBlockZ() + 0.5);
    }

    /**
     * 新建（不是从模板复制）时铺一块落脚台：虚空世界里出生点脚下没方块，管理员一进去就往虚空掉。
     * 顺手把落点标在这块台子上（没标过的话），这样立刻就能 {@code /nwmdungeon test} 跑一局。
     */
    private void buildStarterPad(World world, DungeonDef def) {
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX();
        int y = Math.max(world.getMinHeight() + 2, spawn.getBlockY());
        int z = spawn.getBlockZ();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.getBlockAt(x + dx, y - 1, z + dz)
                        .setType(dx == 0 && dz == 0 ? Material.LODESTONE : Material.STONE_BRICKS, false);
                for (int dy = 0; dy <= 3; dy++) {
                    world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
                }
            }
        }
        world.setSpawnLocation(x, y, z);
        if (def.markers.start == null) {
            def.markers.start = new int[]{x, y, z};
            plugin.getLogger().info("「" + def.name + "」的落点默认设在落脚台 " + x + "," + y + "," + z);
        }
    }

    /** 标下落点（相对于模板世界的绝对坐标，实例世界是同一份拷贝，坐标直接通用）。 */
    public void markStart(Player player, Session session) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            return;
        }
        def.markers.setStart(markPos(player));
        registry.save();
        player.sendMessage("§a落点已记在 §f" + Markers.fmt(def.markers.start) + "§a。");
    }

    /** 检查点：位置 + 脚下 3×3 的方块签名（打掉任意一块就失效）。 */
    public void markCheckpoint(Player player, Session session) {
        DungeonDef def = registry.get(session.dungeonName);
        World world = Bukkit.getWorld(session.worldName);
        if (def == null || world == null) {
            return;
        }
        int[] pos = markPos(player);
        def.markers.addCheckpoint(pos, Markers.signatureOf(world, pos));
        registry.save();
        player.sendMessage("§a检查点已记在 §f" + Markers.fmt(pos)
                + "§a（脚下 3×3 已记账，拆掉任意一块这个检查点就失效）。");
    }

    /** 刷怪点：记在"当前关卡的第 N 波"。 */
    public void markSpawner(Player player, Session session, int wave) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            return;
        }
        int stage = clampStage(def, session.stage);
        int useWave = Math.max(1, wave);
        int[] pos = markPos(player);
        def.markers.addSpawner(pos, stage, useWave);
        registry.save();
        player.sendMessage("§a刷怪点已记入 §f第 " + (stage + 1) + " 关 · 第 " + useWave + " 波§a："
                + Markers.fmt(pos) + "（这一波现在有 "
                + def.markers.spawnersAt(stage, useWave).size() + " 个点）");
    }

    /** 关卡门：看着一扇门点它 —— **他放的是什么门就存什么门**，命令里不写材质。 */
    public boolean markGate(Player player, Session session, Block block) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null || block == null) {
            return false;
        }
        int[] pos = Markers.of(Markers.doorLower(block));
        if (Markers.same(pos, def.markers.exitDoor)) {
            player.sendMessage("§c这扇门已经是「出口门」了 —— 同一扇门不能既是关卡门又是出口门。"
                    + "§7想改就先把出口门删掉（面板里右键「出口门」）。");
            return false;
        }
        def.markers.addGate(pos);
        registry.save();
        player.sendMessage("§a关卡门已登记：§f" + block.getType() + "§a @ " + Markers.fmt(pos)
                + "；之后标的刷怪点属于 §f第 " + def.markers.stageCount() + " 关§a。");
        return true;
    }

    /**
     * 删掉一道关卡门：**优先删你看着的那扇门**；没看着门（或看着的不是关卡门）就删"当前关卡那道门"。
     * 删完后面的关卡会自动往前挪一位。
     */
    public boolean removeGate(Player player, Session session, Block block) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            return false;
        }
        Markers markers = def.markers;
        if (block != null) {
            int[] pos = Markers.of(Markers.doorLower(block));
            int index = -1;
            for (int i = 0; i < markers.gates.size(); i++) {
                if (Markers.same(markers.gates.get(i), pos)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                player.sendMessage("§c这扇门不是已登记的关卡门。§7（只登记了 " + markers.gates.size()
                        + " 道门；用面板的「标记清单」看都在哪）");
                return false;
            }
            markers.removeGateByIndex(index);
            registry.save();
            player.sendMessage("§a已删除第 " + (index + 1) + " 道关卡门（" + Markers.fmt(pos) + "）。"
                    + "§7现在 " + markers.gates.size() + " 道门 / " + markers.stageCount()
                    + " 关，后面关卡的刷怪点已自动往前挪一位。");
            return true;
        }
        int index = clampStage(def, session.stage);
        if (index >= markers.gates.size()) {
            player.sendMessage("§c第 " + (index + 1) + " 关没有关卡门可删（最后一关是首领区）。"
                    + "§7要删别的话：先用准星看着那扇门再点「删除关卡门」。");
            return false;
        }
        int[] pos = markers.gates.get(index).clone();
        markers.removeGateByIndex(index);
        registry.save();
        player.sendMessage("§a已删除第 " + (index + 1) + " 道关卡门（" + Markers.fmt(pos) + "）。"
                + "§7现在 " + markers.gates.size() + " 道门 / " + markers.stageCount() + " 关。");
        return true;
    }

    /** 出口门：同样照原样记下你放的那扇门。 */
    public boolean markExitDoor(Player player, Session session, Block block) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null || block == null) {
            return false;
        }
        int[] pos = Markers.of(Markers.doorLower(block));
        if (def.markers.isGate(pos)) {
            player.sendMessage("§c这扇门已经是「关卡门」了 —— 同一扇门不能既是关卡门又是出口门。"
                    + "§7想改就先把那道关卡门删掉（面板里右键「关卡门」）。");
            return false;
        }
        def.markers.exitDoor = pos;
        def.markers.exitDoorMaterial = block.getType().name();
        registry.save();
        player.sendMessage("§a出口门已登记：§f" + block.getType() + "§a @ "
                + Markers.fmt(def.markers.exitDoor) + "（通关后右键它就能离开）。");
        return true;
    }

    /** 清掉出口门标记（没标的话通关时会用首领位的默认木门兜底）。 */
    public void clearExitDoor(Player player, Session session) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            return;
        }
        if (def.markers.exitDoor == null) {
            player.sendMessage("§7本来就没标出口门。");
            return;
        }
        def.markers.clearExitDoor();
        registry.save();
        player.sendMessage("§a已清掉出口门标记。§7没标的话，通关时会在首领位放一扇默认木门兜底。");
    }

    /** 首领位。 */
    public void markBoss(Player player, Session session) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            return;
        }
        def.markers.boss = markPos(player);
        registry.save();
        player.sendMessage("§a首领位已记在 §f" + Markers.fmt(def.markers.boss) + "§a。");
    }

    /** 补给箱 / 奖励箱（位置来自你看着的那个箱子；里面放什么在 GUI 里填）。 */
    public boolean markChest(Player player, Session session, Block block, String kind) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null || block == null) {
            return false;
        }
        int stage = clampStage(def, session.stage);
        int[] pos = Markers.of(block.getLocation());
        def.markers.addChest(pos, kind, stage);
        registry.save();
        player.sendMessage("§a" + ("reward".equalsIgnoreCase(kind) ? "奖励箱" : "补给箱")
                + "已登记 @ " + Markers.fmt(pos) + "§a（第 " + (stage + 1) + " 关）");
        return true;
    }

    // ---------------------------------------------------------------- 编辑时钟

    /** 编辑时钟的物品标签（防止别人拿个普通钟来点）。 */
    private NamespacedKey clockKey;

    private NamespacedKey clockKey() {
        if (clockKey == null) {
            clockKey = new NamespacedKey(plugin, "editor_clock");
        }
        return clockKey;
    }

    /** 给一个"打开编辑菜单"的时钟；手里已经有就不重复给。 */
    public void giveClock(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isClock(stack)) {
                return;
            }
        }
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta meta = clock.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b编辑菜单 §7(右键打开)");
            meta.setLore(List.of("§7右键 = 打开编辑器面板",
                    "§7保存 / 放弃之后会自动收走"));
            meta.getPersistentDataContainer().set(clockKey(), PersistentDataType.BYTE, (byte) 1);
            clock.setItemMeta(meta);
        }
        // 优先放快捷栏第一个空位，其次塞背包，都满了就丢在脚下
        int firstEmpty = player.getInventory().firstEmpty();
        if (firstEmpty >= 0 && firstEmpty < 9) {
            player.getInventory().setItem(firstEmpty, clock);
        } else if (firstEmpty >= 0) {
            player.getInventory().setItem(firstEmpty, clock);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), clock);
        }
        player.sendMessage("§7给了你一个 §b编辑时钟§7：右键就能打开编辑菜单，不用打命令。");
    }

    /** 收走编辑时钟（保存/放弃之后）。 */
    public void takeClock(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            if (isClock(player.getInventory().getItem(i))) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    /** 这个物品是不是编辑时钟。 */
    public boolean isClock(ItemStack stack) {
        return stack != null && stack.getType() == Material.CLOCK
                && stack.getItemMeta() != null
                && stack.getItemMeta().getPersistentDataContainer()
                .has(clockKey(), PersistentDataType.BYTE);
    }

    /**
     * 编辑世界里打掉方块时的联动：**把箱子打掉 = 那个补给箱/奖励箱标记也删掉**。
     *
     * @return true = 这个方块的处理已经接管（调用方不用再管）
     */
    public boolean handleEditorBreak(Player player, Block block) {
        Session session = byWorld(block.getWorld().getName());
        if (session == null) {
            return false;
        }
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            return false;
        }
        int[] pos = Markers.of(block.getLocation());
        if (block.getState() instanceof org.bukkit.block.Container
                || block.getType() == Material.CHEST
                || block.getType() == Material.TRAPPED_CHEST) {
            int removed = def.markers.removeChestAt(pos);
            if (removed > 0) {
                registry.save();
                player.sendMessage("§5[编辑器]§r §c这个箱子有 " + removed
                        + " 条箱子标记，已经跟着删掉了（" + Markers.fmt(pos) + "）。");
            }
        }
        // 检查点/刷怪点不在这里删：它们本来就靠"方块签名"失效，不需要动标记
        return false;
    }

    /** 把 session 的当前关卡夹到合法范围。 */
    public int clampStage(DungeonDef def, int stage) {
        return Math.max(0, Math.min(def.markers.stageCount() - 1, stage));
    }

    private int[] markPos(Player player) {
        Location location = player.getLocation();
        return new int[]{location.getBlockX(), location.getBlockY(), location.getBlockZ()};
    }

    // ---------------------------------------------------------------- 看着哪个方块

    /** 准星指着的门（取下半格）；取不到就往正前方 4 格找最近的一扇门（M0 的 R4 退路）。 */
    public Block targetedDoor(Player player) {
        return targeted(player, block -> block.getBlockData() instanceof Door);
    }

    /** 准星指着的箱子（或其它容器）。 */
    public Block targetedChest(Player player) {
        return targeted(player, block -> block.getState() instanceof org.bukkit.block.Container);
    }

    /** 准星指着的触发点（按钮 / 压力板 / 拉杆）。 */
    public Block targetedTrigger(Player player) {
        return targeted(player, block -> Entrances.isTriggerMaterial(block.getType()));
    }

    private Block targeted(Player player, java.util.function.Predicate<Block> accept) {
        Block block = player.getTargetBlockExact(6);
        if (block != null && accept.test(block)) {
            return block;
        }
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection().normalize();
        for (double step = 1.0; step <= 4.0; step += 0.5) {
            Block probe = eye.clone().add(direction.clone().multiply(step)).getBlock();
            if (accept.test(probe)) {
                return probe;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 每秒校验

    /**
     * 编辑态每秒跑一次：检查点脚下的方块被改掉就**立刻在聊天里提示**（同一条只提示一次）。
     *
     * <p>失效判定是"**方块说了算**"：拆掉就失效、照原样补回来就恢复 ——
     * 不用额外记"死亡状态"，也不会出现"明明修好了却还用不了"。
     */
    public void tick() {
        for (Session session : new ArrayList<>(sessions.values())) {
            Player player = Bukkit.getPlayer(session.player);
            if (player == null || !player.isOnline()) {
                continue;
            }
            DungeonDef def = registry.get(session.dungeonName);
            World world = Bukkit.getWorld(session.worldName);
            if (def == null || world == null) {
                continue;
            }
            for (int i = 0; i < def.markers.checkpoints.size(); i++) {
                Markers.Checkpoint checkpoint = def.markers.checkpoints.get(i);
                boolean ok = Markers.signatureMatches(world, checkpoint);
                boolean known = session.brokenCheckpoints.contains(i);
                if (!ok && !known) {
                    session.brokenCheckpoints.add(i);
                    player.sendMessage("§5[编辑器]§r §c第 " + (i + 1) + " 个检查点脚下的方块被改动了，"
                            + "这个检查点已失效 §7（" + Markers.fmt(checkpoint.pos)
                            + "）；重新标一次就会恢复。");
                } else if (ok && known) {
                    session.brokenCheckpoints.remove(i);
                    player.sendMessage("§5[编辑器]§r §a第 " + (i + 1) + " 个检查点恢复了。");
                }
            }
            if (session.showMarkers) {
                showMarkerParticles(player, def, world);
            }
        }
    }

    /** 把各类标记用粒子标出来（编辑时"看得见自己标了什么"）。 */
    private void showMarkerParticles(Player player, DungeonDef def, World world) {
        for (Markers.Spawner spawner : def.markers.spawners) {
            particle(player, world, spawner.pos, org.bukkit.Particle.FLAME);
        }
        for (int[] gate : def.markers.gates) {
            particle(player, world, gate, org.bukkit.Particle.END_ROD);
        }
        for (Markers.Checkpoint checkpoint : def.markers.checkpoints) {
            boolean ok = Markers.signatureMatches(world, checkpoint);
            particle(player, world, checkpoint.pos,
                    ok ? org.bukkit.Particle.COMPOSTER : org.bukkit.Particle.LARGE_SMOKE);
        }
        for (Markers.Chest chest : def.markers.chests) {
            particle(player, world, chest.pos, org.bukkit.Particle.HAPPY_VILLAGER);
        }
        particle(player, world, def.markers.start, org.bukkit.Particle.END_ROD);
        particle(player, world, def.markers.boss, org.bukkit.Particle.SOUL);
        particle(player, world, def.markers.exitDoor, org.bukkit.Particle.HAPPY_VILLAGER);
    }

    private void particle(Player player, World world, int[] pos, org.bukkit.Particle type) {
        if (pos == null) {
            return;
        }
        Location center = new Location(world, pos[0] + 0.5, pos[1] + 1.0, pos[2] + 0.5);
        if (center.distanceSquared(player.getLocation()) > 64 * 64) {
            return;   // 太远的别画，省客户端性能
        }
        player.spawnParticle(type, center, 3, 0.25, 0.25, 0.25, 0.0);
    }

    // ---------------------------------------------------------------- 入口建筑

    /** 一次入口搭建会话（搭完存成结构，世界就删掉）。 */
    public static final class EntranceSession {
        public final UUID player;
        public final String dungeonName;
        public final String slug;
        public final String worldName;
        public final Location back;
        public final GameMode backMode;
        public final boolean backAllowFlight;
        public final boolean backFlying;
        /** 门 / 触发点在**搭建世界里**的绝对坐标（保存时再换算成相对选区最小角）。 */
        public Location door;
        public Location trigger;

        EntranceSession(UUID player, DungeonDef def, Location back, GameMode backMode,
                        boolean backAllowFlight, boolean backFlying) {
            this.player = player;
            this.dungeonName = def.name;
            this.slug = def.slug;
            this.worldName = Names.entranceWorld(def.slug);
            this.back = back;
            this.backMode = backMode;
            this.backAllowFlight = backAllowFlight;
            this.backFlying = backFlying;
        }
    }

    private final Map<UUID, EntranceSession> entranceSessions = new HashMap<>();

    public EntranceSession entranceSession(Player player) {
        return entranceSessions.get(player.getUniqueId());
    }

    public EntranceSession entranceSession(UUID uuid) {
        return entranceSessions.get(uuid);
    }

    public EntranceSession entranceByWorld(String worldName) {
        for (EntranceSession session : entranceSessions.values()) {
            if (session.worldName.equals(worldName)) {
                return session;
            }
        }
        return null;
    }

    /** 打开（或继续）入口搭建：已有结构就先贴出来，方便接着改。 */
    public String openEntrance(Player player, DungeonDef def) {
        if (entranceSessions.containsKey(player.getUniqueId())) {
            return "你已经在搭一个入口了，先保存或放弃。";
        }
        String worldName = Names.entranceWorld(def.slug);
        File folder = store.folderOf(worldName);
        if (folder.exists()) {
            World stale = Bukkit.getWorld(worldName);
            if (stale != null) {
                store.unload(stale, false);
            }
            WorldStore.deleteFolder(folder);
        }
        World world = store.create(worldName, "void", true, false);
        if (world == null) {
            WorldStore.deleteFolder(folder);
            return "入口搭建世界创建失败，看控制台日志（世界名 " + worldName + "）。";
        }
        EntranceSession session = new EntranceSession(player.getUniqueId(), def,
                player.getLocation().clone(), player.getGameMode(),
                player.getAllowFlight(), player.isFlying());
        entranceSessions.put(player.getUniqueId(), session);

        Location target;
        EntranceTemplate existing = EntranceTemplate.load(plugin, def.slug, def.name);
        if (existing != null && existing.saved(plugin)) {
            Location at = new Location(world, 8, Math.max(world.getMinHeight() + 2,
                    world.getSpawnLocation().getBlockY()), 8);
            EntranceTemplate.Placed placed = existing.paste(plugin, at);
            if (placed != null) {
                session.door = placed.door();
                session.trigger = placed.trigger();
                player.sendMessage("§7已把上次存的入口贴回来（" + existing.size[0] + "×" + existing.size[2]
                        + "），改完再保存。");
            }
        } else {
            buildEntrancePad(world);
        }
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        giveClock(player);   // 搭入口也要给时钟：不然只能靠打命令开面板
        player.teleport(session.door != null ? session.door.clone().add(0, 1, 0)
                : new Location(world, 8.5, Math.max(world.getMinHeight() + 2,
                world.getSpawnLocation().getBlockY()) + 1, 12.5));
        player.sendMessage("§a入口搭建世界就绪。搭一座门楼（一扇门 + 一个按钮/压力板），"
                + "然后：§f看着门点「入口门」§a、§f看着开关点「触发点」§a，最后保存。");
        return null;
    }

    private void buildEntrancePad(World world) {
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX();
        int y = Math.max(world.getMinHeight() + 2, spawn.getBlockY());
        int z = spawn.getBlockZ();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                world.getBlockAt(x + dx, y - 1, z + dz).setType(Material.STONE_BRICKS, false);
                for (int dy = 0; dy <= 6; dy++) {
                    world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
                }
            }
        }
        world.setSpawnLocation(x, y, z);
    }

    /** 记下"门"（看着的那一扇，材质照原样记）。 */
    public boolean markEntranceDoor(Player player, EntranceSession session, Block block) {
        if (block == null) {
            return false;
        }
        session.door = EntranceTemplate.doorLower(block);
        player.sendMessage("§a入口门已记下：§f" + block.getType() + "§a @ "
                + Markers.of(session.door)[0] + "," + Markers.of(session.door)[1] + ","
                + Markers.of(session.door)[2]);
        return true;
    }

    /** 记下触发点（按钮 / 压力板 / 拉杆都行）。 */
    public boolean markEntranceTrigger(Player player, EntranceSession session, Block block) {
        if (block == null) {
            return false;
        }
        session.trigger = block.getLocation();
        player.sendMessage("§a触发点已记下：§f" + block.getType());
        return true;
    }

    /**
     * 保存入口：自动框出建筑范围 → 存结构 → 记下门/触发点的相对坐标 → 卸载并删掉搭建世界。
     */
    public boolean saveEntrance(Player player, EntranceSession session) {
        if (session.trigger == null) {
            player.sendMessage("§c还没记触发点（按钮/压力板）：先用准星看着它，点「触发点」。");
            return false;
        }
        if (session.door == null) {
            session.door = inferDoor(session);
        }
        if (session.door == null) {
            player.sendMessage("§c还没记入口门：先用准星看着一扇门，点「入口门」。");
            return false;
        }
        World world = Bukkit.getWorld(session.worldName);
        if (world == null) {
            player.sendMessage("§c搭建世界不见了，没法保存。");
            return false;
        }
        world.save();
        int[] bounds = EntranceTemplate.boundsOf(world, world.getSpawnLocation(), 48, 128);
        if (bounds == null) {
            player.sendMessage("§c这个世界里没找到任何方块 —— 先搭一座门楼。");
            return false;
        }
        if (bounds[6] == 1) {
            player.sendMessage("§c建筑太大了（超过 128 格）：入口只该是一座小门楼，别把整个世界都铺满。");
            return false;
        }
        Location min = new Location(world, bounds[0], bounds[1], bounds[2]);
        Location max = new Location(world, bounds[3], bounds[4], bounds[5]);
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            player.sendMessage("§c这个副本的配置已经不在了。");
            return false;
        }
        EntranceTemplate template = new EntranceTemplate(def.name);
        template.slug = def.slug;
        template.display = def.coloredDisplay() + " §7入口";
        template.doorRel = new int[]{session.door.getBlockX() - bounds[0],
                session.door.getBlockY() - bounds[1], session.door.getBlockZ() - bounds[2]};
        template.triggerRel = new int[]{session.trigger.getBlockX() - bounds[0],
                session.trigger.getBlockY() - bounds[1], session.trigger.getBlockZ() - bounds[2]};
        org.bukkit.block.Block doorBlock = session.door.getBlock();
        template.doorMaterial = doorBlock.getType().name();
        if (doorBlock.getBlockData() instanceof org.bukkit.block.data.type.Door doorData) {
            template.doorFacing = doorData.getFacing().name();
            template.doorHinge = doorData.getHinge().name();
        }
        try {
            template.save(plugin, world, min, max);
        } catch (Exception e) {
            plugin.getLogger().warning("保存入口结构失败：" + e);
            player.sendMessage("§c保存结构失败：" + e.getMessage());
            return false;
        }
        player.sendMessage("§a入口已保存（" + template.size[0] + "×" + template.size[1] + "×"
                + template.size[2] + "，门 " + template.doorMaterial + "）。"
                + "§7用 §f/nwmdungeon spawn " + def.name + "§7 贴一座出来。");
        endEntranceSession(player, session, false);
        return true;
    }

    /** 门没记的话：在触发点周围 3 格找一扇门。 */
    private Location inferDoor(EntranceSession session) {
        World world = Bukkit.getWorld(session.worldName);
        if (world == null || session.trigger == null) {
            return null;
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block block = world.getBlockAt(session.trigger.getBlockX() + dx,
                            session.trigger.getBlockY() + dy, session.trigger.getBlockZ() + dz);
                    if (block.getBlockData() instanceof Door) {
                        return EntranceTemplate.doorLower(block);
                    }
                }
            }
        }
        return null;
    }

    /** 放弃入口搭建：删掉搭建世界（已保存的结构不动）。 */
    public void cancelEntrance(Player player, EntranceSession session) {
        World world = Bukkit.getWorld(session.worldName);
        if (world != null) {
            store.unload(world, false);
        }
        WorldStore.deleteFolder(store.folderOf(session.worldName));
        player.sendMessage("§7已放弃这次入口搭建。");
        endEntranceSession(player, session, false);
    }

    private void endEntranceSession(Player player, EntranceSession session, boolean saved) {
        entranceSessions.remove(player.getUniqueId());
        takeClock(player);
        World world = Bukkit.getWorld(session.worldName);
        if (world != null) {
            store.unload(world, false);
        }
        WorldStore.deleteFolder(store.folderOf(session.worldName));
        player.closeInventory();
        player.setGameMode(session.backMode);
        player.setAllowFlight(session.backAllowFlight);
        player.setFlying(session.backFlying);
        if (session.back != null && session.back.getWorld() != null) {
            player.teleport(session.back);
        }
    }

    // ---------------------------------------------------------------- 保存 / 放弃

    /**
     * 递归复制一个文件夹（给"rename 失败时退而求其次"用的）。
     *
     * <p>为什么需要：Windows 上 JVM 会一直占着刚卸载世界的区块文件，{@code File#renameTo}
     * 会因为文件被占用而**静默返回 false**（这在 Linux 上几乎不会发生）。复制则通常读得动，
     * 所以用"复制 + 稍后删源"保证管理员点了保存就真的存下来了。
     */
    private static boolean copyTree(File from, File to) {
        try {
            if (from.isDirectory()) {
                if (!to.isDirectory() && !to.mkdirs()) {
                    return false;
                }
                File[] children = from.listFiles();
                if (children == null) {
                    return false;
                }
                for (File child : children) {
                    if (!copyTree(child, new File(to, child.getName()))) {
                        return false;
                    }
                }
                return true;
            }
            java.nio.file.Files.copy(from.toPath(), to.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** 保存：落盘 → 卸载 → 备份旧模板 → 把临时世界搬成模板。 */
    public boolean save(Player player, Session session) {
        DungeonDef def = registry.get(session.dungeonName);
        if (def == null) {
            player.sendMessage("§c这个副本的配置已经不在了。");
            return false;
        }
        World world = Bukkit.getWorld(session.worldName);
        File folder = world != null ? world.getWorldFolder() : store.folderOf(session.worldName);
        if (world != null) {
            world.save();
            store.unload(world, true);
        }
        if (!WorldStore.looksLikeWorld(folder)) {
            player.sendMessage("§c世界文件夹不见了，没法保存：" + folder);
            return false;
        }
        File template = store.templateFolder(def.slug);
        if (template.isDirectory()) {
            File backupRoot = new File(store.templatesRoot().getParentFile(), "_backup");
            if (!backupRoot.isDirectory()) {
                backupRoot.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File backup = new File(backupRoot, def.slug + "-" + stamp);
            if (!template.renameTo(backup)) {
                player.sendMessage("§c旧模板备份失败（" + template.getName() + "），这次先不覆盖，避免丢东西。");
                return false;
            }
        }
        if (!folder.renameTo(template)) {
            // Windows 上刚卸载的世界，区块文件句柄可能还占着 → renameTo 直接返回 false（Linux 上几乎不会）。
            // 退一步：把目录**复制**成模板，源目录记进"待删清单"，等句柄释放后（下次启动）自动清掉。
            if (!copyTree(folder, template)) {
                player.sendMessage("§c把编辑世界搬成模板失败：" + folder);
                return false;
            }
            plugin.addPendingDelete(folder);
            player.sendMessage("§7（这次是用「复制」保存的：临时世界还占着文件句柄，"
                    + "已记进待删清单，下次启动会自动清掉）");
        }
        registry.save();

        long bytes = WorldStore.sizeOf(template);
        player.sendMessage("§a已保存模板「" + def.coloredDisplay() + "§a」："
                + WorldStore.describeSize(template));
        if (bytes > def.warnWorldSizeMb * 1024L * 1024L) {
            player.sendMessage("§e⚠ 这个模板有 " + WorldStore.describeSize(template)
                    + "，超过了 warn-world-size-mb=" + def.warnWorldSizeMb
                    + "；一局要拷一次，注意 max-concurrent-runs。");
        }
        endSession(player, session, true);
        return true;
    }

    /** 放弃：卸载并删掉临时世界，模板原样不动。 */
    public void cancel(Player player, Session session) {
        World world = Bukkit.getWorld(session.worldName);
        File folder = world != null ? world.getWorldFolder() : store.folderOf(session.worldName);
        if (world != null) {
            store.unload(world, false);
        }
        if (!WorldStore.deleteFolder(folder)) {
            plugin.addPendingDelete(folder);
            player.sendMessage("§e临时世界删不掉，已记进待删清单，下次启动再删。");
        }
        player.sendMessage("§7已放弃「" + session.dungeonName + "」这次的改动。");
        endSession(player, session, false);
    }

    /** 退出编辑态：还原游戏模式 / 飞行 / 位置。 */
    private void endSession(Player player, Session session, boolean saved) {
        sessions.remove(player.getUniqueId());
        writeSessionFile();
        player.closeInventory();
        takeClock(player);
        player.setGameMode(session.backMode);
        player.setAllowFlight(session.backAllowFlight);
        player.setFlying(session.backFlying);
        Location back = session.back;
        if (back != null && back.getWorld() != null) {
            player.teleport(back);
        }
        player.sendMessage(saved ? "§7已退出编辑态。" : "§7已退出编辑态（改动已丢弃）。");
    }

    // ---------------------------------------------------------------- 会话落盘 / 启动清扫

    private void writeSessionFile() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session session : sessions.values()) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("player", session.player.toString());
            map.put("dungeon", session.dungeonName);
            map.put("world", session.worldName);
            map.put("since", System.currentTimeMillis());
            out.add(map);
        }
        yaml.set("sessions", out);
        try {
            yaml.save(sessionFile());
        } catch (Exception e) {
            plugin.getLogger().warning("edit-sessions.yml 写不进去：" + e.getMessage());
        }
    }

    /**
     * 启动时调用：上一次没保存完的编辑世界已经被 {@link WorldStore#cleanupLeftovers()} 删掉了，
     * 这里只负责提醒管理员一声，免得以为存过了。
     */
    public void warnAboutLostSessions() {
        File file = sessionFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yaml.getMapList("sessions")) {
            Object dungeon = raw.get("dungeon");
            if (dungeon != null) {
                plugin.getLogger().warning("上次编辑「" + dungeon + "」没有保存就结束了（重启/崩溃），"
                        + "那份改动已经清掉；模板本身没动。");
            }
        }
        file.delete();
    }
}
