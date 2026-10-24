package nwndungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 程序化副本：一条由铁门隔开的房间链。
 *
 * 布局：大厅(0) → 战斗房(1..n-1) → 首领房(n)，房间之间由走廊相连，走廊尽头是一扇关闭的铁门。
 * 每个房间可以配多波怪：进本只刷第一波，清完一波等 3 秒刷下一波，最后一波清完才算这间完成
 * （门前地板上放石压力板 + 角落给补给箱；首领房给最终奖励 + 中央木门）。
 * 大厅的检查点平台直接铺在出生点下面（进本第一脚就踩在检查点上），其余检查点在偶数房间西侧。
 * 怪物全部是刷出来的实体（不是刷怪笼），并设为持久化，不会自然消失。
 */
public final class DungeonBuilder {

    public static final int ROOM = 25;
    public static final int HEIGHT = 10;   // 房间净高：抬高后大厅/战斗房更开阔（走廊仍是低拱）
    public static final int PITCH = 40;

    /** 每间房里 9 个刷怪点（相对房间原点的 x/z 偏移）。 */
    private static final List<int[]> SPOTS = List.of(
            new int[]{6, 6}, new int[]{6, 12}, new int[]{6, 18},
            new int[]{12, 6}, new int[]{12, 12}, new int[]{12, 18},
            new int[]{18, 6}, new int[]{18, 12}, new int[]{18, 18});

    private final Random random = new Random();
    private java.util.function.Consumer<Runnable> edits = Runnable::run;

    public record Plan(Dungeon dungeon, List<Runnable> operations) { }

    /** 只生成内存中的方块计划，不在这一轮访问或修改世界。 */
    public Plan plan(World world, int ox, int oy, int oz, Tier tier) {
        List<Runnable> operations = new ArrayList<>();
        edits = operations::add;
        try { return new Plan(build(world, ox, oy, oz, tier), operations); }
        finally { edits = Runnable::run; }
    }

    public Dungeon build(World world, int ox, int oy, int oz, Tier tier) {
        // 在准备阶段验证神话首领引用，避免玩家打到最后一关才发现ID错误。
        if (MythicMobsSupport.reference(tier.bossType())) { MobSpawner.resolve(tier.bossType()); }
        Dungeon dungeon = new Dungeon();

        List<List<String>> specs = new ArrayList<>();
        specs.add(List.of());                        // 大厅：没有怪
        specs.addAll(tier.rooms());                  // 战斗房（每个房间 = 若干波）
        specs.add(tier.bossRoomWaves());             // 首领房（最后一波随首领登场）
        int total = specs.size();

        for (int i = 0; i < total; i++) {
            Dungeon.Room room = new Dungeon.Room(i);
            room.bossRoom = i == total - 1;
            room.origin = new Location(world, ox + i * PITCH, oy, oz);
            buildRoomShell(world, ox + i * PITCH, oy, oz, i, i == 0, i == total - 1, dungeon, room);
            dungeon.rooms.add(room);
        }

        for (int i = 1; i < total; i++) {
            Dungeon.Room room = dungeon.rooms.get(i);
            room.waves = parseWaves(specs.get(i), room.bossRoom);
            room.displayIndex = i;          // 大厅(0) 没怪不编号，第 1 间有怪的房间就是"第 1 间"
            if (room.waves.isEmpty()) {
                room.cleared = true;    // 没配阵容的房间直接当已清，别卡住流程
                continue;
            }
            // 1.5.0-M7：**不在这里刷怪了**。
            // 以前建房时把每一间房的第一波全刷出来（注释写"进本只刷第一波"，但它在循环里），
            // 10 间房的副本里最远那间离玩家几百格 → 不在模拟距离内（不 tick）、
            // 区块一卸载就被卸载 → 插件看成"怪不见了/首领自己消失" → 甚至直接结算。
            // 现在改成"玩家走进这一间才开始刷"（见 Instances#startRoomsNearPlayers）。
            room.started = false;
        }

        dungeon.spawn = new Location(world, ox + ROOM / 2.0 + 0.5, oy + 1, oz + ROOM / 2.0 + 0.5, 90f, 0f);
        return dungeon;
    }

    // ---------------------------------------------------------------- 结构

    private void buildRoomShell(World world, int rx, int oy, int oz, int index,
                                boolean first, boolean last, Dungeon dungeon, Dungeon.Room room) {
        int x2 = rx + ROOM - 1;
        int z2 = oz + ROOM - 1;
        int cz = oz + ROOM / 2;
        int wallTop = oy + HEIGHT - 1;
        int ceil = oy + HEIGHT;

        // 地板：外圈包边 + 内圈走道 + 中央拼花
        for (int dx = 0; dx < ROOM; dx++) {
            for (int dz = 0; dz < ROOM; dz++) {
                set(world, rx + dx, oy, oz + dz, floorMaterial(dx, dz));
            }
        }
        // 天花
        for (int dx = 0; dx < ROOM; dx++) {
            for (int dz = 0; dz < ROOM; dz++) {
                set(world, rx + dx, ceil, oz + dz,
                        random.nextInt(7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            }
        }
        // 四面墙：墙裙 + 石砖 + 顶部檐口
        for (int y = oy + 1; y <= wallTop; y++) {
            for (int dx = 0; dx < ROOM; dx++) {
                set(world, rx + dx, y, oz, wallMaterial(y, oy, wallTop));
                set(world, rx + dx, y, z2, wallMaterial(y, oy, wallTop));
            }
            for (int dz = 0; dz < ROOM; dz++) {
                set(world, rx, y, oz + dz, wallMaterial(y, oy, wallTop));
                set(world, x2, y, oz + dz, wallMaterial(y, oy, wallTop));
            }
        }
        // 壁柱：向房间内凸一格（避开门洞与检查点所在的中间带）
        for (int offset : new int[]{3, 8, 18, 23}) {
            pilaster(world, rx + offset, oz + 1, oy, wallTop);
            pilaster(world, rx + offset, z2 - 1, oy, wallTop);
            pilaster(world, rx + 1, oz + offset, oy, wallTop);
            pilaster(world, x2 - 1, oz + offset, oy, wallTop);
        }
        // 四盏吊灯（铁链 + 悬挂灯笼）
        for (int[] spot : new int[][]{{6, 6}, {6, 18}, {18, 6}, {18, 18}}) {
            hangingLantern(world, rx + spot[0], ceil, oz + spot[1]);
        }
        // 天花贴墙收口一圈半砖
        for (int dx = 0; dx < ROOM; dx++) {
            for (int dz = 0; dz < ROOM; dz++) {
                int edge = Math.min(Math.min(dx, ROOM - 1 - dx), Math.min(dz, ROOM - 1 - dz));
                if (edge == 1) {
                    set(world, rx + dx, ceil - 1, oz + dz, Material.STONE_BRICK_SLAB);
                }
            }
        }

        // 东墙开一道 1x2 的门洞（通往走廊），最后一间不留
        if (!last) {
            carveDoorway(world, x2, x2 - 1, oy, cz, ceil);
        }
        // 西墙同样开洞，让走廊通进来；大厅不开
        if (!first) {
            carveDoorway(world, rx, rx + 1, oy, cz, ceil);
        }

        // 检查点：大厅 + 偶数房间。大厅那块铺在出生点正下方，其余铺在房间西侧（避开 9 个刷怪点）
        if (index == 0 || index % 2 == 0) {
            int px = index == 0 ? rx + ROOM / 2 : rx + 3;
            int pz = index == 0 ? oz + ROOM / 2 : cz - 1;
            for (int x = px - 1; x <= px + 1; x++) {
                for (int z = pz - 1; z <= pz + 1; z++) {
                    set(world, x, oy, z, Material.LODESTONE);
                }
            }
            for (int x = px - 2; x <= px + 2; x++) {
                set(world, x, oy, pz - 2, Material.CHISELED_STONE_BRICKS);
                set(world, x, oy, pz + 2, Material.CHISELED_STONE_BRICKS);
            }
            for (int z = pz - 2; z <= pz + 2; z++) {
                set(world, px - 2, oy, z, Material.CHISELED_STONE_BRICKS);
                set(world, px + 2, oy, z, Material.CHISELED_STONE_BRICKS);
            }
            hangingLantern(world, px, ceil, pz - 2);
            dungeon.checkpoints.add(new Location(world, px + 0.5, oy + 1, pz + 0.5));
            set(world, px, oy + 1, pz - 3, Material.OAK_SIGN);
            int checkpointNumber = dungeon.checkpoints.size();
            edits.accept(() -> {
            Block signBlock = world.getBlockAt(px, oy + 1, pz - 3);
            if (signBlock.getState() instanceof Sign sign) {
                sign.setLine(0, "§b检查点 " + checkpointNumber);
                sign.setLine(1, index == 0 ? "§7入口大厅" : "§7第 " + index + " 间");
                sign.setLine(2, "§7死亡后回到这里");
                sign.update(true, false);
            }
            });
        }

        if (!first) {
            room.chestSpot = new Location(world, rx + ROOM - 4, oy + 1, oz + ROOM - 4);
        }
        room.center = new Location(world, rx + ROOM / 2.0 + 0.5, oy + 1, oz + ROOM / 2.0 + 0.5);
        if (last) {
            room.exitDoor = new Location(world, rx + ROOM / 2, oy + 1, cz);
        }

        if (!last) {
            buildCorridorAndDoor(world, rx, oy, oz, cz, room);
        }
    }

    /** 走廊 + 尽头的铁门（关闭状态），并记录压力板该放的位置。 */
    private void buildCorridorAndDoor(World world, int rx, int oy, int oz, int cz, Dungeon.Room room) {
        int x1 = rx + ROOM;
        int x2 = rx + PITCH - 1;

        for (int x = x1; x <= x2; x++) {
            boolean rib = (x - x1) % 6 == 0;   // 每 6 格一道拱肋
            for (int z = cz - 1; z <= cz + 1; z++) {
                set(world, x, oy, z, rib ? Material.CHISELED_STONE_BRICKS
                        : (z == cz ? Material.SMOOTH_STONE : brick()));
                set(world, x, oy + 4, z, rib && z == cz ? Material.SEA_LANTERN : Material.STONE_BRICKS);
                for (int y = oy + 1; y <= oy + 3; y++) {
                    set(world, x, y, z, Material.AIR);
                }
                for (int y = oy + 1; y <= oy + 3; y++) {
                    set(world, x, y, cz - 2, rib ? Material.CHISELED_STONE_BRICKS : brick());
                    set(world, x, y, cz + 2, rib ? Material.CHISELED_STONE_BRICKS : brick());
                }
            }
        }

        // 走廊尽头的墙 + 铁门（门占 oy+1 与 oy+2 两层）
        for (int y = oy + 1; y <= oy + 3; y++) {
            set(world, x2, y, cz - 1, brick());
            set(world, x2, y, cz + 1, brick());
        }
        set(world, x2, oy + 3, cz, brick());
        // 走廊是东西向的，门必须朝东/朝西（门板与通道同轴）。朝向写错（例如 NORTH）时
        // 门板会横卡在门框里，贴着门框边缘就能蹭过去——旧版"铁门像自动开着"就是这个原因。
        edits.accept(() -> placeIronDoor(world, x2, oy + 1, cz, BlockFace.EAST));
        room.doorLower = new Location(world, x2, oy + 1, cz);
        // 门旁那格换成凿制石砖当门柱（纯装饰）；清场后把压力板铺在门前的地板上（踩上去就给门通电）
        set(world, x2, oy + 1, cz + 1, Material.CHISELED_STONE_BRICKS);
        room.plateSpot = new Location(world, x2 - 1, oy + 1, cz);
    }

    // ---------------------------------------------------------------- 怪物（波次）

    /** 把 "ZOMBIE:5,SKELETON:3" 解析成一串实体类型（顺序保留）。 */
    private static List<String> parseSpec(String spec) {
        List<String> types = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return types;
        }
        for (String token : spec.split(",")) {
            int colon = token.lastIndexOf(':');
            if (colon <= 0) {
                throw new MobSpawner.Failure("阵容必须写成 怪物:数量：" + token);
            }
            try {
                String reference = token.substring(0, colon).trim();
                EntityType type = MobSpawner.resolve(reference);
                int count = Math.max(0, Integer.parseInt(token.substring(colon + 1).trim()));
                if (!MobSpawner.supported(type) || count <= 0 || count > MobSpawner.MAX_WAVE_SIZE
                        || types.size() + count > MobSpawner.MAX_WAVE_SIZE) {
                    throw new MobSpawner.Failure("无效的怪物类型或数量：" + token);
                }
                for (int n = 0; n < count; n++) {
                    types.add(reference);
                }
            } catch (Exception e) {
                throw new MobSpawner.Failure("阵容配置错误：" + token, e);
            }
        }
        return types;
    }

    private static List<List<String>> parseWaves(List<String> specs, boolean bossRoom) {
        List<List<String>> waves = new ArrayList<>();
        if (specs == null) {
            return waves;
        }
        for (int i = 0; i < specs.size(); i++) {
            String spec = specs.get(i);
            List<String> types = parseSpec(spec);
            if (!types.isEmpty() || (bossRoom && i == specs.size() - 1)) {
                waves.add(types);
            } else {
                throw new MobSpawner.Failure("普通战斗波不能是空阵容：" + spec);
            }
        }
        return waves;
    }

    /**
     * 刷出某个房间的第 waveIndex 波：进本时刷第 0 波，之后每清完一波延迟 3 秒刷下一波。
     * 首领房的最后一波会把首领本人一起带出来。
     */
    public static void spawnWave(World world, Dungeon.Room room, int waveIndex, Tier tier) {
        if (room == null || room.origin == null || tier == null
                || waveIndex < 0 || waveIndex >= room.waves.size()) {
            return;
        }
        room.waveIndex = waveIndex;
        room.wavePending = false;
        room.pendingWave = -1;
        room.emptySince = 0;
        room.mobs.clear();
        room.lastSeen.clear();
        room.missingSeconds.clear();
        room.entities.clear();
        room.mobRecovery.clear();
        room.spawning = true;
        int rx = room.origin.getBlockX();
        int oy = room.origin.getBlockY();
        int oz = room.origin.getBlockZ();
        int placed = 0;
        try {
            for (String type : room.waves.get(waveIndex)) {
                int[] spot = SPOTS.get(placed % SPOTS.size());
                Location location = new Location(world,
                        rx + spot[0] + 0.5, oy + 1, oz + spot[1] + 0.5);
                LivingEntity entity = MobSpawner.spawn(world, location, type, null,
                        point -> insideRoom(room, point));
                if (entity != null) {
                    room.mobs.add(entity.getUniqueId());
                    room.entities.put(entity.getUniqueId(), entity);
                    room.mobRecovery.put(entity.getUniqueId(), new MobRecovery(entity, null,
                            point -> insideRoom(room, point)));
                    // 记下"最后一次见到它"的位置：查不到实体时用它判断区块加载没加载
                    room.lastSeen.put(entity.getUniqueId(), entity.getLocation().clone());
                }
                placed++;
            }
            if (room.bossRoom && waveIndex == room.waves.size() - 1) {
                try { spawnBoss(world, room, tier); }
                catch (MobSpawner.Failure e) {
                    // 首次生成也进入统一的等待/恢复预算；绝不能设置 bossKilled。
                    room.bossKilled = false;
                    room.lastBossFailure = e;
                }
            }
            room.initialMobs = room.mobs.size();
        } finally {
            room.spawning = false;
        }
    }

    private static boolean insideRoom(Dungeon.Room room, Location point) {
        return point.getX() >= room.origin.getBlockX() + 2
                && point.getX() < room.origin.getBlockX() + ROOM - 2
                && point.getZ() >= room.origin.getBlockZ() + 2
                && point.getZ() < room.origin.getBlockZ() + ROOM - 2
                && point.getY() >= room.origin.getBlockY() + 1
                && point.getY() < room.origin.getBlockY() + HEIGHT;
    }

    /**
     * 给首领找一块"能站住"的地。
     *
     * <p>为什么要这一步：首领是按房间中心刷的，而程序生成的房间里中心可能是**洞、悬空、或者方块里**。
     * 直接刷进去，它一出来就掉出世界或窒息 —— 玩家看到的就是"boss 自己消失了"，
     * 而插件那边收到的是"首领死了"，于是直接进结算（这就是玩家报的那个 bug）。
     *
     * <p>M8：仅用于两格高的诊断实体；限定邻近范围，不铺台、不改建筑。
     * 实际生成使用 MobSpawner，根据真实实体体型检查。
     */
    public static Location safeSpawnSpot(World world, Location at) {
        return MobSpawner.findSafeSpot(world, at, 0.6, 2.0, point -> true);
    }

    /**
     * 刷首领。1.4.13 起改成 public：首领取不到时房间逻辑要能在原地补刷一只，
     * 而不是把"名单空了"当成通关（否则活着的首领会白送通关）。
     */
    public static void spawnBoss(World world, Dungeon.Room room, Tier tier) {
        Location center = room.center != null ? room.center
                : room.origin.clone().add(ROOM / 2.0 + 0.5, 1, ROOM / 2.0 + 0.5);
        // 先给它找一块"能站住"的地 —— 直接往 room.center 刷，遇到房间中央是洞/悬空时，
        // 首领一出来就掉出世界（玩家看到的就是"boss 自己消失了"）。
        LivingEntity boss = MobSpawner.spawn(world, center, tier.bossType(), entity -> configureBoss(entity, tier),
                point -> insideRoom(room, point));
        room.bossKilled = false;
        room.lastBossFailure = null;
        room.bossEntity = boss;
        room.mythicBoss = MythicMobsSupport.managed(boss);
        room.bossId = boss.getUniqueId();
        room.mobs.add(boss.getUniqueId());
        room.entities.put(boss.getUniqueId(), boss);
        room.lastSeen.put(boss.getUniqueId(), boss.getLocation().clone());
        room.missingSeconds.remove(boss.getUniqueId());
    }

    private static void configureBoss(LivingEntity boss, Tier tier) {
        boss.setCustomName(tier.bossName());
        boss.setCustomNameVisible(true);
        MobHealth.apply(boss, tier.bossHealth());
        if (tier.bossAttributes() != null) {
            tier.bossAttributes().apply(boss);
            if (!tier.bossAttributes().legacyStrength()) { return; }
        }
        if (tier.bossAttributes() == null && boss.getEquipment() != null) {
            boss.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            boss.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
            boss.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        }
        try {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, false, false));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 1, false, false));
        } catch (Throwable ignored) {
            // 药水名不兼容就跳过
        }
    }

    // ---------------------------------------------------------------- 工具

    public static void placeIronDoor(World world, int x, int y, int z, BlockFace facing) {
        placeDoor(world, x, y, z, Material.IRON_DOOR, facing);
    }

    /** 自由站立的木门（首领房离开用），朝北即可。 */
    public static void placeOakDoor(World world, int x, int y, int z) {
        placeDoor(world, x, y, z, Material.OAK_DOOR, BlockFace.NORTH);
    }

    private static void placeDoor(World world, int x, int y, int z, Material material, BlockFace facing) {
        Block lower = world.getBlockAt(x, y, z);
        lower.setType(material, false);
        if (lower.getBlockData() instanceof Door data) {
            data.setFacing(facing);
            data.setHalf(Bisected.Half.BOTTOM);
            data.setOpen(false);
            lower.setBlockData(data, false);
        }
        Block upper = world.getBlockAt(x, y + 1, z);
        upper.setType(material, false);
        if (upper.getBlockData() instanceof Door data) {
            data.setFacing(facing);
            data.setHalf(Bisected.Half.TOP);
            data.setOpen(false);
            upper.setBlockData(data, false);
        }
    }

    private Material brick() {
        int roll = random.nextInt(10);
        if (roll < 6) {
            return Material.STONE_BRICKS;
        }
        return roll < 8 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
    }

    /** 地板图案：最外圈包边、内圈浅色走道，其余拼花 + 两条 × 形石带。 */
    private Material floorMaterial(int dx, int dz) {
        int edge = Math.min(Math.min(dx, ROOM - 1 - dx), Math.min(dz, ROOM - 1 - dz));
        if (edge == 0) {
            return Material.CHISELED_STONE_BRICKS;
        }
        if (edge == 1) {
            return Material.POLISHED_ANDESITE;
        }
        if (dx == dz || dx + dz == ROOM - 1) {
            return Material.SMOOTH_STONE;
        }
        int roll = random.nextInt(10);
        if (roll < 6) {
            return Material.STONE_BRICKS;
        }
        return roll < 8 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
    }

    /** 墙面：最下面一格墙裙、最上两格檐口，中间石砖混裂缝/苔藓。 */
    private Material wallMaterial(int y, int oy, int wallTop) {
        if (y == oy + 1) {
            return Material.POLISHED_ANDESITE;
        }
        if (y >= wallTop - 1) {
            return Material.CHISELED_STONE_BRICKS;
        }
        int roll = random.nextInt(10);
        if (roll < 7) {
            return Material.STONE_BRICKS;
        }
        return roll < 9 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
    }

    /** 壁柱：从墙裙一直到檐口，顶端接到天花。 */
    private void pilaster(World world, int x, int z, int oy, int wallTop) {
        for (int y = oy + 1; y <= wallTop; y++) {
            set(world, x, y, z, Material.CHISELED_STONE_BRICKS);
        }
        set(world, x, wallTop + 1, z, Material.CHISELED_STONE_BRICKS);
    }

    /** 墙上开 1×2 门洞，包一圈凿制石砖门框，并在门内侧的天花上挂一盏灯。 */
    private void carveDoorway(World world, int wallX, int insideX, int oy, int cz, int ceil) {
        set(world, wallX, oy + 1, cz, Material.AIR);
        set(world, wallX, oy + 2, cz, Material.AIR);
        set(world, wallX, oy + 3, cz, Material.CHISELED_STONE_BRICKS);
        set(world, wallX, oy + 1, cz - 1, Material.CHISELED_STONE_BRICKS);
        set(world, wallX, oy + 2, cz - 1, Material.CHISELED_STONE_BRICKS);
        set(world, wallX, oy + 1, cz + 1, Material.CHISELED_STONE_BRICKS);
        set(world, wallX, oy + 2, cz + 1, Material.CHISELED_STONE_BRICKS);
        hangingLantern(world, insideX, ceil, cz);
    }

    /** 吊灯：天花下两格铁链 + 一格悬挂灯笼。 */
    private void hangingLantern(World world, int x, int ceil, int z) {
        set(world, x, ceil - 1, z, Material.CHAIN);
        set(world, x, ceil - 2, z, Material.CHAIN);
        edits.accept(() -> {
        Block lamp = world.getBlockAt(x, ceil - 3, z);
        lamp.setType(Material.LANTERN, false);
        if (lamp.getBlockData() instanceof Lantern lantern) {
            lantern.setHanging(true);
            lamp.setBlockData(lantern, false);
        }
        });
    }

    private void set(World world, int x, int y, int z, Material material) {
        edits.accept(() -> world.getBlockAt(x, y, z).setType(material, false));
    }
}
