package nwndungeon;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/** MythicMobs 5.x 可选桥接；不把第三方类打进插件，也不操作其私有血量字段。仅主线程调用。 */
public final class MythicMobsSupport {
    private static Plugin provider;
    private static Object helper;
    private static String unavailable = "未安装或未启用 MythicMobs 5.x";

    private MythicMobsSupport() { }

    public static void connect(Plugin host) {
        disconnect();
        Plugin found = host.getServer().getPluginManager().getPlugin("MythicMobs");
        if (found == null || !found.isEnabled()) { return; }
        try {
            String version = found.getDescription().getVersion();
            if (!version.startsWith("5.")) { throw new IllegalStateException("暂不支持版本 " + version); }
            Object api = call(found, "getAPIHelper");
            api.getClass().getMethod("getMythicMob", String.class);
            api.getClass().getMethod("getMythicMobInstance", Entity.class);
            api.getClass().getMethod("spawnMythicMob", String.class, Location.class, int.class);
            provider = found;
            helper = api;
            host.getLogger().info("已接入 MythicMobs " + version + "；神话怪血量、技能与装备由 MythicMobs 管理");
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            unavailable = "MythicMobs 接口不可用：" + e.getMessage();
            host.getLogger().warning(unavailable + "；原版怪物仍可使用，MYTHIC: 引用将明确报错");
        }
    }

    public static void disconnect() { provider = null; helper = null; unavailable = "未安装或未启用 MythicMobs 5.x"; }

    public static boolean reference(String text) {
        return text != null && text.regionMatches(true, 0, "MYTHIC:", 0, 7);
    }

    public static String id(String reference) {
        String id = reference.substring(7).trim();
        if (id.isEmpty() || id.indexOf(':') >= 0 || id.indexOf(',') >= 0 || id.chars().anyMatch(Character::isWhitespace)) {
            throw new MobSpawner.Failure("神话怪引用应为 MYTHIC:怪物ID：" + reference);
        }
        return id; // Mythic 内部ID区分大小写，绝不能整串转大写。
    }

    private static Object api() {
        if (helper == null || provider == null || !provider.isEnabled()) { throw new MobSpawner.Failure(unavailable); }
        return helper;
    }

    /** 只接受可战斗的原版Mob载体，拒绝展示实体、玩家及不明载体。 */
    public static EntityType type(String reference) {
        Object definition = call(api(), "getMythicMob", new Class<?>[]{String.class}, id(reference));
        if (definition == null) { throw new MobSpawner.Failure("MythicMobs 中不存在怪物ID：" + id(reference)); }
        String base = String.valueOf(call(definition, "getEntityTypeString"));
        try {
            EntityType type = EntityType.valueOf(base.toUpperCase(Locale.ROOT));
            if (!MobSpawner.supported(type)) { throw new IllegalArgumentException("非战斗载体"); }
            return type;
        } catch (IllegalArgumentException e) {
            throw new MobSpawner.Failure("神话怪 " + id(reference) + " 的载体不支持安全生成：" + base, e);
        }
    }

    public static LivingEntity spawn(String reference, Location at) {
        Object result = call(api(), "spawnMythicMob", new Class<?>[]{String.class, Location.class, int.class}, id(reference), at, 1);
        if (!(result instanceof LivingEntity living) || !MobSpawner.supported(living.getType())) {
            if (result instanceof Entity entity) { remove(entity); }
            throw new MobSpawner.Failure("神话怪生成未返回有效战斗实体：" + reference);
        }
        return living;
    }

    /** 同样识别由 Mythic 随机生成规则接管的原版实体，不能与其争夺血量。 */
    public static boolean managed(LivingEntity entity) { return active(entity) != null; }

    public static String identity(LivingEntity entity) {
        Object active = active(entity);
        return active == null ? null : "MYTHIC:" + call(call(active, "getType"), "getInternalName");
    }

    private static Object active(Entity entity) {
        if (entity == null || helper == null) { return null; }
        return call(api(), "getMythicMobInstance", new Class<?>[]{Entity.class}, entity);
    }

    /** 清场通过Mythic注销活动实例，避免只删Bukkit实体后残留技能任务/血条。 */
    public static void remove(Entity entity) {
        if (entity == null) { return; }
        try {
            Object active = provider != null && provider.isEnabled() ? active(entity) : null;
            if (active != null) {
                call(active, "remove");
                // remove()在部分5.x版本延后5tick注销；主线程清场明确同步释放注册表。
                call(active, "unregister", new Class<?>[]{boolean.class}, true);
            }
        } catch (RuntimeException e) {
            if (provider != null) { provider.getLogger().warning("副本清场时Mythic活动实例注销失败：" + e.getMessage()); }
        } finally { entity.remove(); }
    }

    private static Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { return target.getClass().getMethod(name, types).invoke(target, args); }
        catch (ReflectiveOperationException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException invocation ? invocation.getCause() : e;
            throw new MobSpawner.Failure("MythicMobs API " + name + " 失败：" + cause.getMessage(), cause);
        }
    }
}
