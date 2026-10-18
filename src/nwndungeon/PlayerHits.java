package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "这只怪的死亡，算不算玩家打赢的？" —— 全插件只在这里判定一次。
 *
 * <p>为什么不能只看死因：首领/怪被玩家打进岩浆、打落虚空时，
 * {@code getLastDamageCause()} 报的是 {@code LAVA} / {@code VOID}，
 * 看起来像"自己死的"，但**这一下明明是玩家打的**。
 * 反过来，从头到尾没人碰过它、自己走进岩浆 → 不能算玩家打赢（1.4.13 就是这么把
 * "活着的首领"误判成通关的）。
 *
 * <p>所以规则是两条：
 * <ol>
 *   <li><b>直接击杀</b>：{@code getKiller()} 是玩家，或致命伤害来自玩家 / 玩家的弹射物 / 玩家的宠物 → 算；
 *   <li><b>环境死亡</b>（虚空 / 岩浆 / 火烧 / 摔落 / 窒息 / 溺水 / 凋零 …）：
 *       **{@value #ENV_WINDOW_MS} 毫秒内被玩家打过 → 算**（岩浆里烧一会儿、被击退掉下去都需要时间，
 *       所以窗口给得比"一击"宽）。
 * </ol>
 */
public final class PlayerHits {

    /**
     * 环境死亡时，"多久之内被玩家打过"才算玩家打赢。
     *
     * <p>给 30 秒是因为岩浆/火烧是**持续伤害**：玩家把怪打进岩浆之后，它可能十几秒后才死，
     * 太短的窗口会把这种情况漏掉，表现成"怪自己烧死了却没算我们赢"。
     */
    public static final long ENV_WINDOW_MS = 30_000L;

    private record Hit(UUID player, String name, long at) {
    }

    private static final Map<UUID, Hit> HITS = new HashMap<>();

    /** 记录表的上限；超过就顺手清一遍过期项（防止"全服每次伤害都记一条"把内存撑大）。 */
    private static final int SWEEP_THRESHOLD = 2048;
    private static int writes = 0;

    private PlayerHits() {
    }

    /** 记一次玩家造成的伤害（近战、弹射物、玩家的宠物都算）。 */
    public static void record(Entity damaged, Player player) {
        if (damaged == null || player == null) {
            return;
        }
        HITS.put(damaged.getUniqueId(),
                new Hit(player.getUniqueId(), player.getName(), System.currentTimeMillis()));
        // 这个监听是**全服**每次伤害都触发，而死掉的通常只有副本里的怪 ——
        // 所以必须自己清过期项，否则表会一直涨（2026-10-05 加）。
        if (++writes % SWEEP_THRESHOLD == 0 || HITS.size() > SWEEP_THRESHOLD * 2) {
            sweep();
        }
    }

    /** 清掉已经过期（超过判定窗口）的记录。 */
    public static void sweep() {
        long now = System.currentTimeMillis();
        HITS.entrySet().removeIf(entry -> now - entry.getValue().at() > ENV_WINDOW_MS);
    }

    /** 从伤害事件里判断"这一下是不是玩家打的"，是的话记下来。 */
    public static void recordFrom(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        Player player = attackerOf(event.getDamager());
        if (player != null) {
            record(event.getEntity(), player);
        }
    }

    /** 从"伤害来源实体"里找出玩家：直接打 / 玩家射的弹射物 / 玩家的宠物。 */
    public static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof Tameable tameable
                && tameable.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
    }

    /**
     * 这只怪的死亡算不算"玩家打赢的"。
     *
     * <p>这是唯一的判定入口：{@code Instances}（内置副本）与 {@code DungeonRun}（自建副本）
     * 都必须走这里，免得两边规则漂移。
     */
    public static boolean countsAsPlayerKill(LivingEntity entity) {
        return countsAsPlayerKill(entity, uuid -> true);
    }

    public static boolean countsAsPlayerKill(LivingEntity entity, java.util.function.Predicate<UUID> participant) {
        if (entity == null) {
            return false;
        }
        // ① 直接击杀：近战/远程/宠物
        if (entity.getKiller() != null && participant.test(entity.getKiller().getUniqueId())) {
            return true;
        }
        EntityDamageEvent last = entity.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attackerOf(byEntity.getDamager());
            if (attacker != null && participant.test(attacker.getUniqueId())) { return true; }
        }
        // ② 环境死亡：30 秒内被玩家打过就算
        Hit hit = hitOf(entity);
        return hit != null && participant.test(hit.player());
    }

    /** 最近打过它的玩家（在 {@link #ENV_WINDOW_MS} 之内）；没有则 null。 */
    public static Player recentAttacker(Entity entity) {
        Hit hit = hitOf(entity);
        return hit == null ? null : Bukkit.getPlayer(hit.player());
    }

    /** 最近打过它的玩家名字（就算玩家已经下线也给得出来）；没有则 null。 */
    public static String recentAttackerName(Entity entity) {
        Hit hit = hitOf(entity);
        return hit == null ? null : hit.name();
    }

    /** 距最近一次玩家伤害过了多少毫秒；没有记录返回 -1。 */
    public static long millisSincePlayerHit(Entity entity) {
        Hit hit = hitOf(entity);
        return hit == null ? -1L : System.currentTimeMillis() - hit.at();
    }

    private static Hit hitOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        Hit hit = HITS.get(entity.getUniqueId());
        if (hit == null) {
            return null;
        }
        if (System.currentTimeMillis() - hit.at() > ENV_WINDOW_MS) {
            HITS.remove(entity.getUniqueId());
            return null;
        }
        return hit;
    }

    /** 这只怪没了，清掉记录（免得 map 越积越大）。 */
    public static void forget(Entity entity) {
        if (entity != null) {
            HITS.remove(entity.getUniqueId());
        }
    }

    /** 死亡原因的可读说明（日志用），例如 {@code LAVA}、{@code VOID}、{@code ENTITY_ATTACK(玩家 Steve)}。 */
    public static String causeName(EntityDamageEvent last) {
        if (last == null) {
            return "未知（没有伤害记录）";
        }
        String cause = last.getCause().name();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Player player = attackerOf(byEntity.getDamager());
            if (player != null) {
                return cause + "（玩家 " + player.getName() + "）";
            }
            return cause + "（" + byEntity.getDamager().getType() + "）";
        }
        return cause;
    }

    /**
     * 一句给日志用的完整说明：死因 + 最近玩家伤害 + 距今多久 + 判定结果。
     * 排查"boss 自己消失了"这类问题时，靠的就是这一行。
     */
    public static String describe(LivingEntity entity) {
        if (entity == null) {
            return "（实体已不在）";
        }
        String cause = causeName(entity.getLastDamageCause());
        String killer = entity.getKiller() == null ? null : entity.getKiller().getName();
        String recent = recentAttackerName(entity);
        long since = millisSincePlayerHit(entity);
        StringBuilder out = new StringBuilder("死因=").append(cause);
        out.append("，getKiller=").append(killer == null ? "无" : killer);
        if (recent == null) {
            out.append("，最近 30 秒无玩家伤害");
        } else {
            out.append("，最近玩家伤害=").append(recent).append("（").append(since / 1000).append(" 秒前）");
        }
        out.append(" → ").append(countsAsPlayerKill(entity) ? "算玩家击杀" : "不算击杀");
        return out.toString();
    }
}
