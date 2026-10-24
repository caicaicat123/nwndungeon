package nwndungeon;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;

/** 血量必须准确应用；服务器属性上限和设置失败不得静默变成原版血量。 */
public final class MobHealth {
    private MobHealth() { }

    /** 仅用于新首领的短暂出生初始化；真实伤害发生后永久切换为保留剩余血量。 */
    public static final class SpawnState {
        private boolean damaged;
        public void damaged() { damaged = true; }
        public void reconcile(LivingEntity entity, double configuredMaximum) {
            if (MythicMobsSupport.managed(entity)) { return; }
            if (damaged) { synchronizeMaximum(entity, configuredMaximum); }
            else { apply(entity, configuredMaximum); }
        }
    }

    public static double read(ConfigurationSection section, String path, double fallback) {
        Object raw = section.get(path);
        if (raw == null) { return fallback; }
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() <= 0) {
            throw new SafeYaml.Problem(path + " 必须是大于 0 的有限数字（20 点 = 10 颗心）");
        }
        return number.doubleValue();
    }

    public static void apply(LivingEntity entity, double requested) {
        if (!Double.isFinite(requested) || requested <= 0) { throw new MobSpawner.Failure("无效的怪物血量：" + requested); }
        AttributeInstance attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute == null) { throw new MobSpawner.Failure("怪物没有最大生命值属性：" + entity.getType()); }
        try {
            setMaximum(attribute, requested);
            double effective = attribute.getValue();
            if (!close(effective, requested)) {
                throw new MobSpawner.Failure("配置血量 " + requested + "，服务器实际最大血量 " + effective
                        + "；请检查 spigot.yml 的 settings.attribute.maxHealth.max 和生命值属性修饰器");
            }
            entity.setHealth(effective);
            if (!close(entity.getHealth(), requested)) {
                throw new MobSpawner.Failure("血量设置后不一致：配置 " + requested + "，实际 " + entity.getHealth());
            }
        } catch (MobSpawner.Failure failure) { throw failure; }
        catch (RuntimeException e) { throw new MobSpawner.Failure("无法应用配置血量 " + requested + "：" + e.getMessage(), e); }
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) <= Math.max(0.001, Math.abs(b) * 0.000001); }

    /** 血量配置表示最终上限；保留修饰器，通过基值抵消随机或外部生命加成。 */
    private static void setMaximum(AttributeInstance attribute, double requested) {
        attribute.setBaseValue(requested);
        if (close(attribute.getValue(), requested)) { return; }
        double addition = 0, scalar = 0, product = 1;
        var modifiers = attribute.getModifiers();
        if (modifiers != null) {
            for (var modifier : modifiers) {
                switch (modifier.getOperation()) {
                    case ADD_NUMBER -> addition += modifier.getAmount();
                    case ADD_SCALAR -> scalar += modifier.getAmount();
                    case MULTIPLY_SCALAR_1 -> product *= 1 + modifier.getAmount();
                }
            }
        }
        double factor = (1 + scalar) * product;
        double base = requested / factor - addition;
        if (Double.isFinite(base) && Double.isFinite(factor) && factor > 0) { attribute.setBaseValue(base); }
    }

    /** 运行中校正上限时保留剩余血量，不能把已受伤的首领重新治满。 */
    private static void synchronizeMaximum(LivingEntity entity, double configuredMaximum) {
        AttributeInstance attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute == null) { throw new MobSpawner.Failure("首领没有最大生命值属性"); }
        double remaining = entity.getHealth();
        setMaximum(attribute, configuredMaximum);
        double effective = attribute.getValue();
        if (!Double.isFinite(effective) || !close(effective, configuredMaximum)) {
            throw new MobSpawner.Failure("首领血量无法校正：配置 " + configuredMaximum + "，实际上限 " + effective
                    + "；检查服务器生命上限或属性修饰器");
        }
        if (remaining > effective) { entity.setHealth(effective); }
    }

    public static LivingEntity forBar(java.util.UUID uuid, LivingEntity reference) {
        if (reference != null && reference.isValid() && !reference.isDead()) { return reference; }
        org.bukkit.entity.Entity found = org.bukkit.Bukkit.getEntity(uuid);
        return found instanceof LivingEntity living && living.isValid() && !living.isDead() ? living : null;
    }

    /** 标题和进度共用本局配置上限，分子读取实体实时血量；不伪装属性不一致的实体。 */
    public static void updateBar(BossBar bar, String name, LivingEntity entity, double configuredMaximum) {
        if (entity == null) {
            bar.setColor(BarColor.YELLOW);
            bar.setTitle("§e" + name + "恢复中 §7· §f最大生命 " + number(configuredMaximum));
            bar.setProgress(1.0);
            return;
        }
        AttributeInstance attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double actualMaximum = attribute == null ? Double.NaN : attribute.getValue();
        if (MythicMobsSupport.managed(entity)) {
            if (!Double.isFinite(actualMaximum) || actualMaximum <= 0) {
                throw new MobSpawner.Failure("神话首领没有有效最大生命值");
            }
            configuredMaximum = actualMaximum; // 包括Mythic技能动态改变上限的情况；只读，不回写。
            if (entity.getCustomName() != null && !entity.getCustomName().isBlank()) {
                name = org.bukkit.ChatColor.stripColor(entity.getCustomName());
            }
        }
        if (!Double.isFinite(actualMaximum) || !close(actualMaximum, configuredMaximum)) {
            synchronizeMaximum(entity, configuredMaximum);
        }
        double current = Math.max(0, entity.getHealth());
        bar.setColor(BarColor.RED);
        bar.setTitle("§c" + name + " §f" + number(current) + " / " + number(configuredMaximum));
        bar.setProgress(Math.max(0, Math.min(1, current / configuredMaximum)));
    }

    private static String number(double value) {
        java.math.BigDecimal rounded = java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP);
        if (value > 0 && rounded.signum() == 0) { return "0.01"; }
        return rounded.stripTrailingZeros().toPlainString();
    }
}
