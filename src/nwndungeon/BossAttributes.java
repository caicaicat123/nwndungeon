package nwndungeon;

import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import java.util.*;

/** 新格式首领属性与装备快照；只改显式配置的项，空装备保留原版武器。 */
public record BossAttributes(Map<Attribute, Double> attributes, Map<EquipmentSlot, ItemStack> equipment,
                             boolean legacyStrength, float dropChance) {
    public static BossAttributes read(ConfigurationSection boss) {
        if (boss == null || !boss.getBoolean("structured", false)) { return null; }
        if (boss.isSet("attributes.legacy-strength") && !(boss.get("attributes.legacy-strength") instanceof Boolean)) { throw new SafeYaml.Problem("boss.attributes.legacy-strength 必须是 true/false"); }
        Map<Attribute, Double> attributes = new LinkedHashMap<>();
        for (var pair : Map.of("damage", Attribute.GENERIC_ATTACK_DAMAGE, "movement-speed", Attribute.GENERIC_MOVEMENT_SPEED,
                "armor", Attribute.GENERIC_ARMOR, "armor-toughness", Attribute.GENERIC_ARMOR_TOUGHNESS,
                "knockback-resistance", Attribute.GENERIC_KNOCKBACK_RESISTANCE).entrySet()) {
            Object raw = boss.get("attributes." + pair.getKey());
            if (raw == null) { continue; }
            if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0) { throw new SafeYaml.Problem("boss.attributes." + pair.getKey() + " 必须是非负有限数字"); }
            attributes.put(pair.getValue(), ((Number) raw).doubleValue());
        }
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        ConfigurationSection eq = boss.getConfigurationSection("equipment");
        if (eq != null) {
            for (String key : eq.getKeys(false)) {
                EquipmentSlot slot = switch (key) {
                    case "mainhand", "main-hand", "hand" -> EquipmentSlot.HAND;
                    case "offhand", "off-hand" -> EquipmentSlot.OFF_HAND;
                    case "helmet", "head" -> EquipmentSlot.HEAD;
                    case "chestplate", "chest" -> EquipmentSlot.CHEST;
                    case "leggings", "legs" -> EquipmentSlot.LEGS;
                    case "boots", "feet" -> EquipmentSlot.FEET;
                    default -> throw new SafeYaml.Problem("boss.equipment 未知部位：" + key);
                };
                ConfigurationSection item = eq.getConfigurationSection(key);
                if (item == null) { throw new SafeYaml.Problem("boss.equipment." + key + " 必须是物品配置表"); }
                Material material = Material.matchMaterial(item.getString("item", ""));
                if (material == null) { throw new SafeYaml.Problem("boss.equipment." + key + ".item 未知物品"); }
                ItemStack stack = new ItemStack(material);
                ConfigurationSection enchants = item.getConfigurationSection("enchants");
                if (enchants != null) {
                    for (String name : enchants.getKeys(false)) {
                        var enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
                        Object level = enchants.get(name);
                        if (enchant == null || !(level instanceof Integer number) || number < 1 || number > 255) { throw new SafeYaml.Problem("boss.equipment." + key + ".enchants." + name + " 未知附魔或等级不在1~255"); }
                        stack.addUnsafeEnchantment(enchant, (Integer) level);
                    }
                }
                equipment.put(slot, stack);
            }
        }
        Object chance = boss.get("equipment-drop-chance", 0.0);
        if (!(chance instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0 || number.doubleValue() > 1) { throw new SafeYaml.Problem("boss.equipment-drop-chance 必须为0~1"); }
        return new BossAttributes(Map.copyOf(attributes), Map.copyOf(equipment), boss.getBoolean("attributes.legacy-strength", false), ((Number) chance).floatValue());
    }
    public void apply(LivingEntity entity) {
        attributes.forEach((key, value) -> { var attribute = entity.getAttribute(key); if (attribute != null) { attribute.setBaseValue(value); } });
        if (entity.getEquipment() != null) {
            equipment.forEach((slot, item) -> { entity.getEquipment().setItem(slot, item.clone()); entity.getEquipment().setDropChance(slot, dropChance); });
        }
    }
}
