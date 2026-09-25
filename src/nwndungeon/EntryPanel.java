package nwndungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 进本前的"确认队伍"面板。
 *
 * 只有当入口区块里**不止一个人**时才弹：按按钮 → 先看清楚这次会带谁进去 → 点「确认进入」才开始 3 秒读条。
 * 这么做的意义是防止路过的人被顺手带进副本（以及让开本的人确认一下名单）。
 */
public final class EntryPanel {

    public static final int CONFIRM_SLOT = 22;
    public static final int CANCEL_SLOT = 26;

    /** GUI 的 holder：记着是哪扇门、这次名单是谁。 */
    public static final class Holder implements InventoryHolder {
        private final Location door;
        private final List<UUID> members;
        private Inventory inventory;

        Holder(Location door, List<UUID> members) {
            this.door = door;
            this.members = members;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public Location door() {
            return door;
        }

        public List<UUID> members() {
            return members;
        }
    }

    private EntryPanel() {
    }

    /** 打开确认面板。确认后由调用方（NWNDungeon）去查槽位、查体力、开始读条。 */
    public static void open(Player viewer, Location door, String tierName, List<Player> members, PanelConfig cfg) {
        List<UUID> ids = new ArrayList<>();
        for (Player member : members) {
            ids.add(member.getUniqueId());
        }
        Holder holder = new Holder(door, ids);
        PanelConfig config = cfg == null ? new PanelConfig() : cfg;
        Inventory inventory = Bukkit.createInventory(holder, 27, config.title);
        holder.inventory = inventory;

        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "§8");
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(4, named(new ItemStack(Material.PAPER), config.header(members.size()),
                config.hint(tierName), config.hint2));
        int slot = 10;
        for (Player member : members) {
            if (slot > 16) {
                break;
            }
            inventory.setItem(slot++, head(member, "§a" + member.getName(),
                    member.getUniqueId().equals(viewer.getUniqueId()) ? "§7（是你）" : "§7点确认后一起进本"));
        }
        inventory.setItem(CONFIRM_SLOT, named(new ItemStack(Material.LIME_CONCRETE),
                config.confirm, config.confirmLore));
        inventory.setItem(CANCEL_SLOT, named(new ItemStack(Material.RED_CONCRETE),
                config.cancel, config.cancelLore));
        viewer.openInventory(inventory);
    }

    private static ItemStack head(Player owner, String name, String lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        if (stack.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            meta.setDisplayName(name);
            meta.setLore(List.of(lore));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static ItemStack named(ItemStack stack, String name, String... lore) {
        if (stack.getItemMeta() instanceof org.bukkit.inventory.meta.ItemMeta meta) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(List.of(lore));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
