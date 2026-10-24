package nwndungeon;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/** 出生后5秒内的只读证据；绝不根据追踪结果回血、扣血或终止副本。仅主线程使用。 */
public final class BossHealthTrace {
    private final NWNDungeon plugin;
    private final double configured;
    private int damageEvents, healEvents;
    private double damageTotal, healTotal;
    private boolean enabled = true;

    public BossHealthTrace(NWNDungeon plugin, double configured) {
        this.plugin = plugin;
        this.configured = configured;
    }

    public void sample(String phase, LivingEntity entity) {
        if (!enabled) { return; }
        try {
            var attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            var point = entity.getLocation();
            String detail = "phase=" + phase + " uuid=" + entity.getUniqueId()
                    + " type=" + entity.getType() + " name=" + clean(entity.getCustomName())
                    + " configured=" + configured + " health=" + entity.getHealth()
                    + " maximum=" + (attribute == null ? "missing" : attribute.getValue())
                    + " base=" + (attribute == null ? "missing" : attribute.getBaseValue())
                    + " fireTicks=" + entity.getFireTicks() + " ageTicks=" + entity.getTicksLived()
                    + " damageEvents=" + damageEvents + " finalDamageTotal=" + damageTotal
                    + " healEvents=" + healEvents + " healAmountTotal=" + healTotal
                    + " valid=" + entity.isValid() + " dead=" + entity.isDead()
                    + " world=" + (point.getWorld() == null ? "unknown" : point.getWorld().getName())
                    + " xyz=" + point.getBlockX() + "," + point.getBlockY() + "," + point.getBlockZ();
            FailureDiagnostics.bossHealth(plugin, "首领血量追踪 version=" + plugin.getDescription().getVersion(), detail);
            if (phase.equals("registered")) {
                plugin.getLogger().info("[首领血量追踪] " + entity.getUniqueId() + " " + clean(entity.getCustomName())
                        + " health=" + entity.getHealth() + " maximum=" + (attribute == null ? "missing" : attribute.getValue())
                        + " configured=" + configured + "；后续见 diagnostics/boss-health.log");
            }
        } catch (RuntimeException | LinkageError e) {
            enabled = false;
            plugin.getLogger().warning("首领血量只读追踪已停用：" + e.getMessage());
        }
    }

    public void damage(EntityDamageEvent event, LivingEntity entity) {
        damageEvents++;
        damageTotal += Math.max(0, event.getFinalDamage());
        if (damageEvents <= 12) {
            sample("damage-before-apply cause=" + event.getCause() + " final=" + event.getFinalDamage(), entity);
        }
    }

    public void heal(EntityRegainHealthEvent event, LivingEntity entity) {
        healEvents++;
        healTotal += Math.max(0, event.getAmount());
        if (healEvents <= 12) {
            sample("heal-before-apply reason=" + event.getRegainReason() + " amount=" + event.getAmount(), entity);
        }
    }

    private static String clean(String text) {
        return text == null ? "none" : text.replaceAll("§.", "").replaceAll("[\\r\\n\\t]", " ");
    }
}
