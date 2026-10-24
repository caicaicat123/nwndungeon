package nwndungeon;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 普通怪失踪恢复；同一只怪换 UUID 后继续使用原预算，不能无限补刷。仅主线程调用。 */
public final class MobRecovery {
    public static final int MAX_ATTEMPTS_PER_TICK = 2;
    private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    final SpawnRecovery recovery = new SpawnRecovery();
    private final EntityType type;
    private String mythicIdentity;
    private final Location origin;
    private final Consumer<LivingEntity> configure;
    private final Predicate<Location> inside;
    private final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private final EnumMap<EquipmentSlot, Float> dropChances = new EnumMap<>(EquipmentSlot.class);
    private final List<PotionEffect> effects;
    private final String name;
    private final boolean nameVisible;
    private final boolean baby;
    private final int slimeSize;
    private double maximum;
    private double remaining;
    private RuntimeException lastFailure;

    public MobRecovery(LivingEntity entity, Consumer<LivingEntity> configure, Predicate<Location> inside) {
        this.type = entity.getType();
        this.origin = entity.getLocation().clone();
        this.configure = configure;
        this.inside = inside;
        this.name = entity.getCustomName();
        this.nameVisible = entity.isCustomNameVisible();
        this.baby = entity instanceof Ageable ageable ? !ageable.isAdult()
                : entity instanceof Zombie zombie && zombie.isBaby();
        this.slimeSize = entity instanceof Slime slime ? slime.getSize() : 0;
        this.effects = new ArrayList<>(entity.getActivePotionEffects());
        var gear = entity.getEquipment();
        if (gear != null) {
            for (EquipmentSlot slot : SLOTS) {
                equipment.put(slot, gear.getItem(slot).clone());
                dropChances.put(slot, gear.getDropChance(slot));
            }
        }
        observe(entity);
    }

    private void observe(LivingEntity entity) {
        if (entity == null) { return; }
        String identity = MythicMobsSupport.identity(entity);
        if (identity != null) { mythicIdentity = identity; }
        // remove() 后引用通常仍可读，优先保留最后一次伤害后的血量；0 不代表已确认击杀。
        double health = entity.getHealth();
        var attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute != null && health > 0) {
            maximum = attribute.getValue();
            remaining = Math.min(health, maximum);
        }
    }

    public int attempts() { return recovery.attempts(); }
    public RuntimeException lastFailure() { return lastFailure; }

    /** 每局每轮限额；名单始终保留待恢复成员，只有死亡事件才能减少清怪数。 */
    public static int tick(NWNDungeon plugin, World world, String context, List<UUID> mobs, UUID bossId,
                           Map<UUID, LivingEntity> entities, Map<UUID, Location> lastSeen,
                           Map<UUID, MobRecovery> recoveries, int budget) {
        for (var iterator = mobs.listIterator(); iterator.hasNext();) {
            UUID uuid = iterator.next();
            if (uuid.equals(bossId)) { continue; }
            MobRecovery state = recoveries.get(uuid);
            if (state == null) { throw new MobSpawner.Failure("缺少怪物恢复记录：" + uuid); }
            int previous = state.attempts();
            LivingEntity entity = state.findOrRecover(world, uuid, entities.get(uuid), lastSeen.get(uuid),
                    System.currentTimeMillis(), budget > 0);
            if (state.attempts() > previous) {
                budget--;
                String result = entity == null ? "补刷未成功，继续等待" : "已补刷 " + entity.getUniqueId();
                String message = context + " 普通怪异常消失 " + state.type + " / " + uuid
                        + "，最后位置 " + location(lastSeen.get(uuid))
                        + "，恢复 " + state.attempts() + "/" + SpawnRecovery.MAX_RETRIES + "：" + result;
                plugin.getLogger().warning(message);
                FailureDiagnostics.record(plugin, context, message, state.lastFailure());
            }
            if (entity == null) { continue; }
            UUID current = entity.getUniqueId();
            if (!uuid.equals(current)) {
                PlayerHits.forget(entities.get(uuid));
                iterator.set(current);
                entities.remove(uuid);
                lastSeen.remove(uuid);
                recoveries.remove(uuid);
                recoveries.put(current, state);
            }
            entities.put(current, entity);
            lastSeen.put(current, entity.getLocation().clone());
        }
        return budget;
    }

    /** null = 继续等待；返回新 UUID 的实体 = 原位恢复；耗尽预算才抛出失败。 */
    public LivingEntity findOrRecover(World world, UUID uuid, LivingEntity reference, Location seen,
                                      long now, boolean allowRecovery) {
        LivingEntity found = MobSpawner.find(world, uuid, reference, seen);
        if (found != null) {
            observe(found);
            recovery.found();
            return found;
        }
        observe(reference);
        Location actual = reference == null ? seen : reference.getLocation();
        boolean loaded = actual != null && world.equals(actual.getWorld()) && MobSpawner.entitiesReady(actual);
        if (!allowRecovery) { return null; }
        SpawnRecovery.Decision decision = recovery.missing(now, loaded);
        if (decision == SpawnRecovery.Decision.ABORT) {
            throw new MobSpawner.Failure("怪物恢复失败：" + type + " / " + uuid + "，已尝试 " + attempts()
                    + " 次，最后位置 " + location(actual)
                    + (lastFailure == null ? "" : "，原因：" + lastFailure.getMessage()), lastFailure);
        }
        if (decision != SpawnRecovery.Decision.RETRY) { return null; }
        if (mythicIdentity != null) {
            throw new MobSpawner.Failure("神话怪 " + mythicIdentity + " 持续失踪，无法安全重建技能阶段；本局异常结束，不判通关");
        }
        LivingEntity restored = null;
        try {
            restored = MobSpawner.spawn(world, origin, type, entity -> {
                if (configure != null) { configure.accept(entity); }
                if (entity instanceof Ageable ageable) { if (baby) { ageable.setBaby(); } else { ageable.setAdult(); } }
                if (entity instanceof Zombie zombie) { zombie.setBaby(baby); }
                if (entity instanceof Slime slime) { slime.setSize(slimeSize); }
                entity.setCustomName(name);
                entity.setCustomNameVisible(nameVisible);
                MobHealth.apply(entity, maximum);
                var gear = entity.getEquipment();
                if (gear != null) {
                    for (EquipmentSlot slot : equipment.keySet()) {
                        gear.setItem(slot, equipment.get(slot).clone());
                        gear.setDropChance(slot, dropChances.get(slot));
                    }
                }
                for (PotionEffect effect : effects) { entity.addPotionEffect(effect); }
            }, inside);
            restored.setHealth(Math.min(remaining, restored.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
            lastFailure = null;
            recovery.found();
            return restored;
        } catch (RuntimeException e) {
            if (restored != null) { restored.remove(); }
            lastFailure = e;
            return null;
        }
    }

    private static String location(Location point) {
        return point == null ? "未知" : point.getBlockX() + "," + point.getBlockY() + "," + point.getBlockZ();
    }
}
