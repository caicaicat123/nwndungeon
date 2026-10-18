package nwndungeon;

import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.BoundingBox;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 两种副本共用的生成入口：检查体型、落脚点、防自然消失和生成结果。仅主线程调用。 */
public final class MobSpawner {
    public static final int MAX_WAVE_SIZE = 256;
    private static final int SEARCH_RADIUS = 4;
    private static final int[] HEIGHT_OFFSETS = {0, 1, -1, 2, -2};

    public static final class Failure extends RuntimeException {
        public Failure(String message) { super(message); }
        public Failure(String message, Throwable cause) { super(message, cause); }
    }

    private MobSpawner() { }

    public static boolean supported(EntityType type) {
        Class<? extends Entity> clazz = type == null ? null : type.getEntityClass();
        return type != null && type.isSpawnable() && clazz != null
                && org.bukkit.entity.Mob.class.isAssignableFrom(clazz);
    }

    public static LivingEntity spawn(World world, Location at, EntityType type,
                                      Consumer<LivingEntity> configure) {
        return spawn(world, at, type, configure, point -> true);
    }

    public static LivingEntity spawn(World world, Location at, EntityType type,
                                      Consumer<LivingEntity> configure, Predicate<Location> inside) {
        if (!supported(type)) {
            throw new Failure("不支持的战斗怪物类型：" + type);
        }
        Class<? extends LivingEntity> clazz = type.getEntityClass().asSubclass(LivingEntity.class);
        if (world.getDifficulty() == Difficulty.PEACEFUL && Monster.class.isAssignableFrom(clazz)) {
            throw new Failure("副本世界处于和平难度，不能生成 " + type);
        }
        LivingEntity probe = null;
        LivingEntity[] created = {null};
        double[] configuredMaximum = {Double.NaN};
        try {
            at.getChunk().load();
            // 创建但不加入世界：先取得当前版本、当前模板的真实体型。
            probe = world.createEntity(at, clazz);
            if (configure != null) { configure.accept(probe); }
            LivingEntity shape = probe;
            Location spot = findSafeSpot(world, at, shape.getWidth(), shape.getHeight(),
                    point -> inside.test(point) && !shape.collidesAt(point));
            // 必须保留原版生成初始化：false 会让骷髅无弓、凋灵骷髅无剑。
            // Bukkit 在原版初始化之后调用 consumer，自定义模板仍可覆盖装备。
            LivingEntity spawned = world.spawn(spot, clazz, CreatureSpawnEvent.SpawnReason.CUSTOM, true, entity -> {
                created[0] = entity;
                if (configure != null) {
                    configure.accept(entity);
                    var health = entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    if (health != null) { configuredMaximum[0] = health.getValue(); }
                }
                // 原版初始化可能改变年龄或史莱姆尺寸，以最终体型复核，避免塞进墙里。
                if (!clearBody(world, spot, entity.getWidth(), entity.getHeight()) || entity.collidesAt(spot)) {
                    throw new Failure("原版初始化后的怪物体型无法安全容纳：" + type);
                }
                entity.setPersistent(true);
                entity.setRemoveWhenFarAway(false);
            });
            if (spawned == null || !spawned.isValid() || spawned.isDead() || spawned.getHealth() <= 0) {
                throw new Failure("生成被取消或怪物在生成时失效：" + type + " @ " + spot);
            }
            // CreatureSpawnEvent 在配置回调之后触发，可能把首领属性重置回旧值。
            if (Double.isFinite(configuredMaximum[0])) { MobHealth.apply(spawned, configuredMaximum[0]); }
            return spawned;
        } catch (RuntimeException | LinkageError e) {
            if (created[0] != null) { created[0].remove(); }
            if (e instanceof Failure failure) { throw failure; }
            throw new Failure("生成 " + type + " 失败：" + e.getMessage(), e);
        } finally {
            if (probe != null) { probe.remove(); }
        }
    }

    /** 最多横向找 4 格、上下找 2 格；不穿楼层、不铺台、不改建筑。 */
    public static Location findSafeSpot(World world, Location at, double width, double height,
                                         Predicate<Location> fits) {
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) { continue; }
                    int x = at.getBlockX() + dx;
                    int z = at.getBlockZ() + dz;
                    for (int dy : HEIGHT_OFFSETS) {
                        int floorY = at.getBlockY() + dy - 1;
                        if (floorY < world.getMinHeight() || floorY >= world.getMaxHeight() - 1) { continue; }
                        Block floor = world.getBlockAt(x, floorY, z);
                        if (!floor.getType().isSolid() || dangerous(floor.getType())) { continue; }
                        double y = floor.getBoundingBox().getMaxY();
                        Location candidate = new Location(world, x + 0.5, y, z + 0.5);
                        if (y + height >= world.getMaxHeight() || !world.getWorldBorder().isInside(candidate)) {
                            continue;
                        }
                        if (clearBody(world, candidate, width, height) && fits.test(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        throw new Failure("标记点附近没有安全落脚点：" + at.getBlockX() + ","
                + at.getBlockY() + "," + at.getBlockZ());
    }

    private static boolean clearBody(World world, Location at, double width, double height) {
        double half = Math.max(0.1, width / 2.0) - 0.001;
        BoundingBox body = new BoundingBox(at.getX() - half, at.getY() + 0.001, at.getZ() - half,
                at.getX() + half, at.getY() + height - 0.001, at.getZ() + half);
        for (int x = (int) Math.floor(body.getMinX()); x <= (int) Math.floor(body.getMaxX()); x++) {
            for (int z = (int) Math.floor(body.getMinZ()); z <= (int) Math.floor(body.getMaxZ()); z++) {
                Block support = world.getBlockAt(x, (int) Math.floor(at.getY() - 0.001), z);
                if (!support.getType().isSolid() || dangerous(support.getType())
                        || Math.abs(support.getBoundingBox().getMaxY() - at.getY()) > 0.01) { return false; }
                for (int y = (int) Math.floor(body.getMinY()); y <= (int) Math.floor(body.getMaxY()); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isLiquid() || dangerous(block.getType())) { return false; }
                    if (!block.isPassable() && block.getBoundingBox().overlaps(body)) { return false; }
                }
            }
        }
        return true;
    }

    private static boolean dangerous(Material type) {
        return switch (type) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE,
                    POWDER_SNOW, SWEET_BERRY_BUSH, WITHER_ROSE, WATER, BUBBLE_COLUMN -> true;
            default -> false;
        };
    }

    /** 优先使用手里的实体引用定位实际区块，再加载实体；禁止只看过期的最后位置就补刷。 */
    public static LivingEntity find(World world, UUID id, LivingEntity reference, Location lastSeen) {
        if (id == null) { return null; }
        if (reference != null && !reference.isDead() && reference.isValid()
                && !world.equals(reference.getWorld())) {
            throw new Failure("副本怪物被移到了其他世界：" + id);
        }
        if (reference != null && !reference.isDead() && reference.isValid()
                && world.equals(reference.getWorld())) { return reference; }
        Entity known = org.bukkit.Bukkit.getEntity(id);
        if (known instanceof LivingEntity living && !living.isDead() && living.isValid()
                && world.equals(living.getWorld())) { return living; }
        Location point = reference != null && world.equals(reference.getWorld()) ? reference.getLocation() : lastSeen;
        if (point != null && world.equals(point.getWorld())) {
            Chunk chunk = point.getChunk();
            chunk.load();
            for (Entity entity : chunk.getEntities()) {
                if (id.equals(entity.getUniqueId()) && entity instanceof LivingEntity living
                        && living.isValid() && !living.isDead()) { return living; }
            }
        }
        return null;
    }

    public static boolean entitiesReady(Location at) {
        if (at == null) { return false; }
        Chunk chunk = at.getChunk();
        chunk.load();
        chunk.getEntities();
        return chunk.isEntitiesLoaded();
    }
}
