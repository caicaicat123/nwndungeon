package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天输入捕获：GUI 里没法打字，所以"新建副本"这类需要名字的动作改成
 * 「点图标 → 聊天里提示 → 把你下一条消息当输入」。
 *
 * <p>{@code AsyncPlayerChatEvent} 是异步的，所以回调**统一切回主线程**执行；
 * 消息会被取消（吃掉），不会广播出去。
 */
public final class ChatPrompt {

    /** 收到输入后做什么。 */
    public interface Handler {
        void accept(Player player, String text);
    }

    private final NWNDungeon plugin;
    private final Map<UUID, Handler> waiting = new HashMap<>();

    public ChatPrompt(NWNDungeon plugin) {
        this.plugin = plugin;
    }

    public boolean isWaiting(Player player) {
        return waiting.containsKey(player.getUniqueId());
    }

    /** 问一句，等玩家在聊天里回。 */
    public void ask(Player player, String hint, Handler handler) {
        waiting.put(player.getUniqueId(), handler);
        player.closeInventory();
        player.sendMessage("§5[编辑器]§r " + hint);
        player.sendMessage("§7（想取消就打 §f取消§7）");
    }

    public void cancel(Player player) {
        if (waiting.remove(player.getUniqueId()) != null) {
            player.sendMessage("§7已取消。");
        }
    }

    /** 玩家下线时静默丢掉等待（别在名单里留垃圾）。 */
    public void forget(Player player) {
        waiting.remove(player.getUniqueId());
    }

    /**
     * 处理一条聊天消息。返回 true = 这条消息被吃掉了（调用方要把事件取消掉）。
     */
    public boolean handle(Player player, String message) {
        Handler handler = waiting.remove(player.getUniqueId());
        if (handler == null) {
            return false;
        }
        String text = message == null ? "" : message.trim();
        if (text.equalsIgnoreCase("取消") || text.equalsIgnoreCase("cancel")) {
            player.sendMessage("§7已取消。");
            return true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> handler.accept(player, text));
        return true;
    }
}
