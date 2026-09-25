package nwndungeon;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自定义怪物模板：种类 / 名字 / 血量 / 攻击力 / 移速 / 装备（含附魔）/ 掉不掉装备 / 额外掉落。
 *
 * 写在 {@code plugins/NWNDungeon/mobs.yml}，副本刷怪点用 {@code mob: <模板名>} 引用。
 * 不需要 MythicMobs —— 属性、装备、掉落都由本插件自己实现。
 */
public record MobTemplate(String id, EntityType type, String name, double health, double damage, double speed,
                          double dropChance, EnumMap<EquipmentSlot, ItemStack> equipment, List<DropSpec> drops) {

    /** 额外掉落：物品 / 概率 / 数量区间。 */
    public record DropSpec(Material material, double chance, int min, int max) {
    }

    public static Map<String, MobTemplate> loadAll(JavaPlugin plugin) {
        Map<String, MobTemplate> out = new LinkedHashMap<>();
        File file = new File(plugin.getDataFolder(), "mobs.yml");
        if (!file.exists()) {
            plugin.saveResource("mobs.yml", false);
        }
        if (!file.exists()) {
            return out;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("mobs");
        if (root == null) {
            return out;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                out.put(id.toLowerCase(Locale.ROOT), from(id.toLowerCase(Locale.ROOT), section));
            } catch (Exception e) {
                plugin.getLogger().warning("怪物模板 " + id + " 读取失败：" + e.getMessage());
            }
        }
        return out;
    }

    public static MobTemplate from(String id, ConfigurationSection section) {
        EntityType type;
        try {
            type = EntityType.valueOf(section.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            type = EntityType.ZOMBIE;
        }
        EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        ConfigurationSection eq = section.getConfigurationSection("equipment");
        if (eq != null) {
            for (String key : eq.getKeys(false)) {
                EquipmentSlot slot = slotOf(key);
                ConfigurationSection itemSection = eq.getConfigurationSection(key);
                if (slot == null || itemSection == null) {
                    continue;
                }
                Material material = Material.matchMaterial(String.valueOf(itemSection.getString("item", "")));
                if (material == null || material.isAir()) {
                    continue;
                }
                ItemStack stack = new ItemStack(material);
                ConfigurationSection enchants = itemSection.getConfigurationSection("enchants");
                if (enchants != null) {
                    for (String name : enchants.getKeys(false)) {
                        Enchantment enchantment = Registry.ENCHANTMENT.get(
                                NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
                        if (enchantment != null) {
                            stack.addUnsafeEnchantment(enchantment, Math.max(1, enchants.getInt(name)));
                        }
                    }
                }
                equipment.put(slot, stack);
            }
        }
        List<DropSpec> drops = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("drops")) {
            Object item = raw.get("item");
            Material material = item == null ? null : Material.matchMaterial(String.valueOf(item));
            if (material == null) {
                continue;
            }
            double chance = raw.get("chance") instanceof Number number ? number.doubleValue() : 1.0;
            int min = raw.get("min") instanceof Number number ? number.intValue() : 1;
            int max = raw.get("max") instanceof Number number ? number.intValue() : min;
            drops.add(new DropSpec(material, Math.max(0, Math.min(1, chance)), Math.max(1, min), Math.max(min, max)));
        }
        boolean dropEquipment = section.getBoolean("drop-equipment", true);
        double dropChance = section.isSet("drop-chance")
                ? section.getDouble("drop-chance")
                : (dropEquipment ? 1.0 : 0.0);
        return new MobTemplate(id,
                type,
                section.getString("name", id),
                Math.max(1, section.getDouble("health", 20)),
                Math.max(0, section.getDouble("damage", 2)),
                Math.max(0.01, section.getDouble("speed", 0.23)),
                Math.max(0, Math.min(1, dropChance)),
                equipment,
                drops);
    }

    private static EquipmentSlot slotOf(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "helmet", "head" -> EquipmentSlot.HEAD;
            case "chestplate", "chest" -> EquipmentSlot.CHEST;
            case "leggings", "legs" -> EquipmentSlot.LEGS;
            case "boots", "feet" -> EquipmentSlot.FEET;
            case "mainhand", "hand" -> EquipmentSlot.HAND;
            case "offhand" -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    /** 把模板套到刷出来的实体上：名字 / 血量 / 攻击 / 移速 / 装备 / 装备掉率。 */
    public void apply(LivingEntity entity) {
        if (name != null && !name.isBlank()) {
            entity.setCustomName(name);
            entity.setCustomNameVisible(true);
        }
        AttributeInstance maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
            entity.setHealth(Math.min(health, maxHealth.getValue()));
        }
        AttributeInstance attack = entity.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(damage);
        }
        AttributeInstance moveSpeed = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (moveSpeed != null) {
            moveSpeed.setBaseValue(speed);
        }
        EntityEquipment equip = entity.getEquipment();
        if (equip == null) {
            return;
        }
        equipment.forEach((slot, stack) -> equip.setItem(slot, stack.clone(), true));
        if (!equipment.isEmpty()) {
            float chance = (float) dropChance;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                equip.setDropChance(slot, chance);
            }
        }
    }
}
