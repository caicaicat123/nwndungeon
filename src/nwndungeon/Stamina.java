package nwndungeon;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * 副本体力（每日次数）。
 *
 * 一个池子（默认 500 点），**每 3456 tick（172.8 秒）回 1 点 → 正好 24 小时回满**。
 * 离线也照回：数值与"上次结算时间"都存在玩家数据里，下次上线时按时间差补算，不需要后台任务。
 */
public final class Stamina {

    private final NWNDungeon plugin;
    private final NamespacedKey poolKey;
    private final NamespacedKey timeKey;
    private boolean enabled;
    private int max;
    private long regenTicks;

    public Stamina(NWNDungeon plugin) {
        this.plugin = plugin;
        this.poolKey = new NamespacedKey(plugin, "stamina");
        this.timeKey = new NamespacedKey(plugin, "stamina_time");
        reload();
    }

    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("stamina.enabled", true);
        max = Math.max(1, cfg.getInt("stamina.max", 500));
        regenTicks = Math.max(1, cfg.getLong("stamina.regen-ticks", 3456));
    }

    public boolean enabled() {
        return enabled;
    }

    public int max() {
        return max;
    }

    public long regenTicks() {
        return regenTicks;
    }

    /** 当前体力（顺带把离线期间该回的都补上）。 */
    public int current(Player player) {
        return (int) state(player)[0];
    }

    /** 扣体力：不够就不扣、返回 false。 */
    public boolean spend(Player player, int cost) {
        if (!enabled || cost <= 0) {
            return true;
        }
        long[] state = state(player);
        if (state[0] < cost) {
            return false;
        }
        write(player, state[0] - cost, state[1]);
        return true;
    }

    /** 距离回下一点还有多少秒（已满返回 0）。 */
    public long secondsToNextPoint(Player player) {
        long[] state = state(player);
        if (state[0] >= max) {
            return 0;
        }
        long perPoint = regenTicks * 50L;
        long remain = Math.max(0, perPoint - (System.currentTimeMillis() - state[1]));
        return (remain + 999) / 1000;
    }

    /** 一行展示文本。 */
    public String describe(Player player) {
        long seconds = secondsToNextPoint(player);
        return "§f" + current(player) + "/" + max
                + (seconds <= 0 ? " §7(已满)" : " §7(下一点 " + (seconds / 60) + " 分 " + (seconds % 60) + " 秒)");
    }

    /** {当前值, 上次结算时间(ms)}，顺带结算离线回复。 */
    private long[] state(Player player) {
        Integer stored = player.getPersistentDataContainer().get(poolKey, PersistentDataType.INTEGER);
        Long last = player.getPersistentDataContainer().get(timeKey, PersistentDataType.LONG);
        long now = System.currentTimeMillis();
        long value = stored == null ? max : Math.min(max, stored);
        long since = last == null ? now : last;
        long perPoint = regenTicks * 50L;
        long gained = (now - since) / perPoint;
        if (gained > 0) {
            value = Math.min(max, value + gained);
            since += gained * perPoint;
            if (value >= max) {
                since = now;
            }
            write(player, value, since);
        } else if (stored == null || last == null) {
            write(player, value, since);
        }
        return new long[]{value, since};
    }

    private void write(Player player, long value, long since) {
        player.getPersistentDataContainer().set(poolKey, PersistentDataType.INTEGER, (int) value);
        player.getPersistentDataContainer().set(timeKey, PersistentDataType.LONG, since);
    }
}
