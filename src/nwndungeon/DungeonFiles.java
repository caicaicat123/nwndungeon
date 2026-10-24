package nwndungeon;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** v2文档边界：严格校验后翻译成既有运行结构；不让配置格式侵入战斗状态机。 */
public final class DungeonFiles {
    public record Document(File file, String revision, YamlConfiguration yaml, YamlConfiguration legacy) {
        public String id() { return yaml.getString("id"); }
        public boolean generated() { return "generated".equals(yaml.getString("kind")); }
        public boolean enabled() { return yaml.getBoolean("enabled", true); }
    }
    public record Snapshot(Map<String, Document> documents) { }
    private DungeonFiles() { }

    public static Snapshot load(File folder) {
        File dir = new File(folder, "dungeons");
        Map<String, Document> docs = new LinkedHashMap<>();
        File[] files = dir.listFiles(f -> f.isFile() && (f.getName().toLowerCase(Locale.ROOT).endsWith(".yml") || f.getName().toLowerCase(Locale.ROOT).endsWith(".yaml")));
        if (files == null) {
            if (dir.exists()) { throw new SafeYaml.Problem("dungeons 不是可读取的目录"); }
            return new Snapshot(docs);
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String before = SafeYaml.revision(file);
            YamlConfiguration yaml = SafeYaml.read(file);
            YamlConfiguration legacy;
            try { legacy = normalize(yaml); }
            catch (RuntimeException e) { throw new SafeYaml.Problem(file.getName() + "：" + e.getMessage(), e); }
            if (!before.equals(SafeYaml.revision(file))) { throw new SafeYaml.Problem("读取中配置被修改：" + file); }
            Document doc = new Document(file, before, yaml, legacy);
            String key = doc.id().toLowerCase(Locale.ROOT);
            if (docs.putIfAbsent(key, doc) != null) { throw new SafeYaml.Problem("副本ID重复：" + doc.id() + "（" + file.getName() + "）"); }
        }
        return new Snapshot(docs);
    }

    /** 明确schema、房间顺序、数值类型和阵容；拒绝将Map.toString当成怪物名。 */
    public static YamlConfiguration normalize(YamlConfiguration yaml) {
        if (!(yaml.get("schema-version") instanceof Integer version) || version != 2) { bad("schema-version 必须为 2"); }
        String id = yaml.getString("id", "").trim();
        if (id.isBlank() || id.length() > 64 || id.matches(".*[.\\\\/:&§].*")) { bad("id不能为空，也不能含点号、冒号、斜杠或颜色代码"); }
        String kind = yaml.getString("kind", "");
        if (!Set.of("generated", "template").contains(kind)) { bad("kind 必须是 generated（程序生成）或 template（自建地图）"); }
        if (kind.equals("template") && Set.of("iron", "gold", "diamond", "template").contains(id.toLowerCase(Locale.ROOT))) { bad("自建副本不能占用内置ID：" + id); }
        YamlConfiguration out = new YamlConfiguration();
        out.set("structured-config", true);
        out.set("display", color(yaml.getString("name", id)));
        out.set("icon", yaml.getString("icon", "IRON_DOOR"));
        copy(yaml, out, "world", "world");
        copy(yaml, out, "markers", "markers");
        copy(yaml, out, "entrance", "entrance");
        out.set("block", yaml.getString("entrance.block", "IRON_BLOCK"));
        ConfigurationSection settings = yaml.getConfigurationSection("settings");
        if (settings != null) { for (String key : settings.getKeys(false)) { out.set(key, settings.get(key)); } }
        for (String key : List.of("time-limit-minutes", "max-concurrent-runs", "warn-world-size-mb")) {
            integer(out.get(key), key, 1, Integer.MAX_VALUE, true);
        }
        for (String key : List.of("stamina-cost", "money-reward")) { integer(out.get(key), key, 0, Integer.MAX_VALUE, true); }
        integer(yaml.get("entrance.weight"), "entrance.weight", 1, Integer.MAX_VALUE, true);
        for (String key : List.of("enabled", "entrance.random-generation", "entrance.teleport", "world.enabled", "world.force-adventure")) {
            if (yaml.isSet(key) && !(yaml.get(key) instanceof Boolean)) { bad(key + " 必须是 true/false"); }
        }
        if (!yaml.getBoolean("enabled", true)) { return out; } // 删除/禁用文档仍保留ID，阻止旧配置复活。
        List<?> rooms = yaml.getList("rooms");
        if (rooms == null || rooms.isEmpty()) { bad("rooms 至少需要一间首领房"); }
        List<Map<String, Object>> stages = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            Map<String, Object> room = map(rooms.get(i), "rooms[" + (i + 1) + "]");
            integer(room.get("id"), "rooms[" + (i + 1) + "].id", i + 1, i + 1, false);
            if (room.containsKey("boss") && !(room.get("boss") instanceof Boolean)) { bad("rooms[].boss 必须是 true/false"); }
            boolean boss = Boolean.TRUE.equals(room.get("boss"));
            if (boss != (i == rooms.size() - 1)) { bad("只有最后一间房必须写 boss: true"); }
            String name = color(String.valueOf(room.getOrDefault("name", "第 " + (i + 1) + " 关")));
            List<String> waves = waves(room.get("waves"), "rooms[" + (i + 1) + "].waves");
            if (!boss && waves.isEmpty()) { bad("普通房间至少需要一波怪物：rooms[" + (i + 1) + "]"); }
            stages.add(new LinkedHashMap<>(Map.of("name", name, "waves", waves)));
        }
        out.set("stages", stages);
        out.set("rooms", new ArrayList<>(stages.subList(0, stages.size() - 1)));
        out.set("room-names", stages.stream().map(r -> r.get("name")).toList());
        ConfigurationSection boss = yaml.getConfigurationSection("boss");
        if (boss == null) { bad("缺少 boss 配置段"); }
        if (!MythicMobsSupport.reference(boss.getString("type", "ZOMBIE")) && !boss.isSet("attributes.health")) { bad("原版/模板首领必须填写 boss.attributes.health"); }
        ConfigurationSection attributes = boss.getConfigurationSection("attributes");
        if (attributes != null) {
            for (String key : attributes.getKeys(false)) {
                if (!Set.of("health", "damage", "movement-speed", "armor", "armor-toughness", "knockback-resistance", "legacy-strength").contains(key)) { bad("boss.attributes 未知属性：" + key); }
            }
        }
        out.set("boss.type", yaml.getString("boss.type", "ZOMBIE").trim());
        out.set("boss.name", color(yaml.getString("boss.name", "&c首领")));
        out.set("boss.health", MobHealth.read(boss, "attributes.health", 60));
        copy(yaml, out, "boss.attributes", "boss.attributes");
        copy(yaml, out, "boss.equipment", "boss.equipment");
        copy(yaml, out, "boss.equipment-drop-chance", "boss.equipment-drop-chance");
        out.set("boss.structured", true);
        out.set("boss.waves", stages.get(stages.size() - 1).get("waves"));
        out.set("boss.guards", mobs(yaml.get("boss.guards"), "boss.guards", true));
        copy(yaml, out, "rewards.supply", "supply-chest");
        copy(yaml, out, "rewards.completion", "reward-chest");
        copy(yaml, out, "rewards.enchant-pool", "enchant-pool");
        copy(yaml, out, "rewards.enchant-levels", "enchant-levels");
        return out;
    }

    private static List<String> waves(Object raw, String path) {
        if (raw == null) { return List.of(); }
        if (!(raw instanceof List<?>)) { bad(path + " 必须是波次列表"); }
        List<String> out = new ArrayList<>();
        int n = 0;
        for (Object item : (List<?>) raw) {
            Map<String, Object> wave = map(item, path + "[" + (++n) + "]");
            out.add(mobs(wave.get("mobs"), path + "[" + n + "].mobs", false));
        }
        return out;
    }
    private static String mobs(Object raw, String path, boolean emptyAllowed) {
        if (raw == null && emptyAllowed) { return ""; }
        Map<String, Object> entries = map(raw, path);
        List<String> out = new ArrayList<>();
        int total = 0;
        for (var entry : entries.entrySet()) {
            String name = entry.getKey().trim();
            if (name.isBlank() || name.contains(",") || (name.contains(":") && !MythicMobsSupport.reference(name))) { bad(path + " 怪物名无效：" + name); }
            if (MythicMobsSupport.reference(name)) { MythicMobsSupport.id(name); }
            integer(entry.getValue(), path + "." + name, 1, MobSpawner.MAX_WAVE_SIZE, false);
            total += ((Number) entry.getValue()).intValue();
            if (total > MobSpawner.MAX_WAVE_SIZE) { bad(path + " 一波最多256只"); }
            out.add(name + ":" + entry.getValue());
        }
        if (out.isEmpty() && !emptyAllowed) { bad(path + " 不能是空阵容"); }
        return String.join(",", out);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String path) {
        if (value instanceof ConfigurationSection section) { return section.getValues(false); }
        if (!(value instanceof Map<?, ?>)) { bad(path + " 必须是键值表"); }
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
    private static void integer(Object raw, String path, int min, int max, boolean optional) {
        if (raw == null && optional) { return; }
        if (!(raw instanceof Integer number) || number < min || number > max) { bad(path + " 必须是整数 " + min + " 到 " + max); }
    }
    private static void bad(String message) { throw new SafeYaml.Problem(message); }
    public static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    static void copy(ConfigurationSection from, ConfigurationSection to, String src, String dst) {
        Object value = from.get(src);
        if (value instanceof ConfigurationSection section) {
            to.createSection(dst);
            for (String key : section.getKeys(false)) { copy(section, to, key, dst + "." + key); }
        } else if (value instanceof Map<?, ?> entries) {
            to.createSection(dst, entries);
        } else { to.set(dst, value); }
    }
    static void merge(ConfigurationSection from, ConfigurationSection to, String prefix) {
        to.createSection(prefix);
        for (String key : from.getKeys(false)) { copy(from, to, key, prefix + "." + key); }
    }
    public static void defaults(NWNDungeon plugin, ConfigurationSection config) {
        File dir = new File(plugin.getDataFolder(), "dungeons");
        if (config.getConfigurationSection("tiers") == null && !dir.exists()) {
            for (String id : List.of("iron", "gold", "diamond")) { plugin.saveResource("dungeons/" + id + ".yml", false); }
        }
    }
    static YamlConfiguration update(Document doc, DungeonDef def) {
        YamlConfiguration yaml = SafeYaml.parse(doc.yaml().saveToString(), doc.file().getName());
        YamlConfiguration old = new YamlConfiguration(); def.writeTo(old);
        yaml.set("name", def.display); yaml.set("icon", def.icon);
        for (String key : List.of("world", "entrance", "markers")) { copy(old, yaml, key, key); }
        for (String key : List.of("instance-mode", "time-limit-minutes", "stamina-cost", "money-reward", "max-concurrent-runs", "warn-world-size-mb")) { copy(old, yaml, key, "settings." + key); }
        return yaml;
    }

    /** 保留旧运行语义：普通房间、多波、首领前置波和最后护卫均不丢失。 */
    public static YamlConfiguration export(String id, boolean generated, ConfigurationSection old, ConfigurationSection loot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 2); yaml.set("id", id); yaml.set("kind", generated ? "generated" : "template");
        yaml.set("name", old.getString("display", id)); yaml.set("icon", old.getString("icon", generated ? "IRON_BLOCK" : "IRON_DOOR"));
        for (String key : List.of("world", "entrance", "markers")) { copy(old, yaml, key, key); }
        if (generated) { yaml.set("entrance.block", old.getString("block", "IRON_BLOCK")); }
        if (!yaml.isSet("entrance.weight")) { yaml.set("entrance.weight", 10); }
        for (String key : List.of("instance-mode", "time-limit-minutes", "stamina-cost", "money-reward", "max-concurrent-runs", "warn-world-size-mb")) { copy(old, yaml, key, "settings." + key); }
        List<Map<String, Object>> rooms = new ArrayList<>();
        List<?> raw = old.getList(generated ? "rooms" : "stages", List.of());
        int normalCount = raw.size();
        String bossRoomName = "首领关";
        Markers markers = new Markers();
        if (!generated && old.getConfigurationSection("markers") != null) {
            markers.readFrom(old.getConfigurationSection("markers"));
            normalCount = markers.stageCount() - 1;
            if (raw.size() > markers.stageCount()) { bad("旧stages数量超过地图关卡数，请先核对标记再转换：" + id); }
            if (raw.size() == markers.stageCount()) { bossRoomName = String.valueOf(map(raw.get(normalCount), "旧首领关").getOrDefault("name", "首领关")); }
        }
        for (int index = 0; index < normalCount; index++) {
            Object item = index < raw.size() ? raw.get(index) : Map.of();
            Map<String, Object> section = item instanceof String ? Map.of("waves", List.of(item)) : map(item, "旧关卡");
            Object waveData = section.get("waves");
            if (!generated && (!(waveData instanceof List<?> list) || list.isEmpty())) {
                int count = markers.spawnersAt(index, 0).size();
                if (count == 0) { bad("旧第 " + (index + 1) + " 关缺少阵容和刷怪点，请补齐后转换"); }
                waveData = List.of("ZOMBIE:" + count);
            }
            rooms.add(exportRoom(rooms.size() + 1, String.valueOf(section.getOrDefault("name", "第 " + (rooms.size() + 1) + " 关")), waveData, false));
        }
        rooms.add(exportRoom(rooms.size() + 1, bossRoomName, old.getList("boss.waves", List.of()), true));
        yaml.set("rooms", rooms);
        yaml.set("boss.type", old.getString("boss.type", "ZOMBIE"));
        yaml.set("boss.name", old.getString("boss.name", "&c首领"));
        yaml.set("boss.attributes.health", MobHealth.read(old, "boss.health", 60));
        yaml.set("boss.guards", exportMobs(old.getString("boss.guards", "")));
        copy(old, yaml, "boss.attributes", "boss.attributes");
        if (!yaml.isSet("boss.attributes.health")) { yaml.set("boss.attributes.health", MobHealth.read(old, "boss.health", 60)); }
        copy(old, yaml, "boss.equipment", "boss.equipment");
        // 迁移旧版强化效果/装备，保持实际战斗语义；新文档可明确改成空效果、空装备。
        yaml.set("boss.attributes.legacy-strength", old.getBoolean("boss.structured", false) ? old.getBoolean("boss.attributes.legacy-strength", false) : true);
        if (generated && !yaml.isSet("boss.equipment")) {
            yaml.set("boss.equipment.mainhand.item", "DIAMOND_SWORD");
            yaml.set("boss.equipment.helmet.item", "DIAMOND_HELMET"); yaml.set("boss.equipment.chestplate.item", "DIAMOND_CHESTPLATE");
        }
        ConfigurationSection pool = loot == null ? old : loot;
        for (String[] pair : List.of(new String[]{"supply-chest", "supply"}, new String[]{"reward-chest", "completion"}, new String[]{"enchant-pool", "enchant-pool"}, new String[]{"enchant-levels", "enchant-levels"})) { copy(pool, yaml, pair[0], "rewards." + pair[1]); }
        yaml.setComments("schema-version", List.of("新版配置：一个副本一个文件。修改后 /nwd reload，新开一局生效。"));
        yaml.setComments("entrance.weight", List.of("相对权重：本入口权重 / 所有可用入口权重之和。", "例如10、30、60三种入口，权重10的入口占10/100=10%；不是固定10%。"));
        yaml.setComments("rooms", List.of("id从1连续编号；name是玩家进入这一关时屏幕上的Title。", "每项waves是一波；mobs下逐行写 怪物名: 数量。最后一间必须 boss: true。", "首领房的waves为前置小怪波，最后自动生成boss.guards和首领本人。"));
        yaml.setComments("boss", List.of("属性与装备分栏；MYTHIC:ID的属性、装备在MythicMobs中管理。"));
        normalize(yaml);
        return yaml;
    }
    private static Map<String, Object> exportRoom(int id, String name, Object raw, boolean boss) {
        Map<String, Object> room = new LinkedHashMap<>(); room.put("id", id); room.put("name", name);
        if (boss) { room.put("boss", true); }
        List<Map<String, Object>> waves = new ArrayList<>();
        if (raw instanceof List<?> list) { for (Object entry : list) { waves.add(Map.of("mobs", exportMobs(String.valueOf(entry)))); } }
        room.put("waves", waves); return room;
    }
    private static Map<String, Integer> exportMobs(String spec) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<WaveSpec.Item> parsed = WaveSpec.parse(spec);
        long tokens = spec == null ? 0 : Arrays.stream(spec.split(",")).filter(s -> !s.isBlank()).count();
        if (parsed.size() != tokens) { bad("旧阵容格式错误，未进行转换：" + spec); }
        for (WaveSpec.Item item : parsed) { out.merge(item.name(), item.count(), Integer::sum); }
        if (spec != null && !spec.isBlank() && out.isEmpty()) { bad("旧阵容无法转换：" + spec); }
        return out;
    }

    public static String migrate(NWNDungeon plugin) {
        File folder = plugin.getDataFolder();
        YamlConfiguration config = SafeYaml.read(new File(folder, "config.yml"));
        YamlConfiguration legacy = SafeYaml.read(new File(folder, "dungeons.yml"));
        YamlConfiguration loot = SafeYaml.read(new File(folder, "loot.yml"));
        Snapshot existing = load(folder);
        List<SafeYaml.Write> writes = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (boolean generated : List.of(true, false)) {
            ConfigurationSection root = (generated ? config : legacy).getConfigurationSection(generated ? "tiers" : "dungeons");
            if (root == null) { continue; }
            for (String id : root.getKeys(false)) {
                if (existing.documents().containsKey(id.toLowerCase(Locale.ROOT))) { continue; }
                String filename = generated ? id : Names.slugOf(id);
                if (!used.add(filename.toLowerCase(Locale.ROOT))) { throw new SafeYaml.Problem("转换文件名冲突：" + filename); }
                File target = new File(folder, "dungeons/" + filename + ".yml");
                if (target.exists()) { throw new SafeYaml.Problem("不覆盖已有文件：" + target); }
                ConfigurationSection old = root.getConfigurationSection(id);
                ConfigurationSection pool = loot.getConfigurationSection("dungeons." + id);
                if (pool == null) { pool = loot.getConfigurationSection("tiers." + id); }
                YamlConfiguration yaml = export(id, generated, old, pool);
                if (generated) { yaml.set("entrance.weight", config.getInt("generation.difficulty-weights." + id, 10)); }
                writes.add(new SafeYaml.Write(target, yaml, SafeYaml.MISSING));
            }
        }
        if (writes.isEmpty()) { return "没有需要转换的旧副本；已有新版文件保持原样。"; }
        File backup = new File(folder, "config-backups/" + System.currentTimeMillis());
        try {
            Files.createDirectories(backup.toPath());
            for (String name : List.of("config.yml", "dungeons.yml", "registry.yml", "loot.yml", "mobs.yml")) {
                File source = new File(folder, name); if (source.isFile()) { Files.copy(source.toPath(), new File(backup, name).toPath(), StandardCopyOption.COPY_ATTRIBUTES); }
            }
        } catch (Exception e) { throw new SafeYaml.Problem("备份失败，未进行转换：" + e.getMessage(), e); }
        SafeYaml.saveAll(writes);
        return "已转换 " + writes.size() + " 个副本到 dungeons/；旧文件保留，备份：" + backup.getAbsolutePath();
    }
}
