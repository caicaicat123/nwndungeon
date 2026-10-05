package nwndungeon;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自建副本的登记表：{@code dungeons.yml}（每个副本一段配置）＋ {@code registry.yml}（名字 ↔ 世界名 slug）。
 *
 * <p>为什么要有 registry.yml：slug 虽然能从名字推出来，但**推出来的东西会随改名/升级而变**，
 * 而模板文件夹是按 slug 命名的。把 slug 固定存一份，改名就不会把模板弄丢。
 */
public final class DungeonRegistry {

    /** 这几个名字被内置副本占了，自建副本不许用。 */
    private static final Set<String> RESERVED = Set.of("iron", "gold", "diamond", "template");

    private final NWNDungeon plugin;
    private final File file;
    private final File registryFile;
    private YamlConfiguration yaml = new YamlConfiguration();
    private YamlConfiguration registry = new YamlConfiguration();
    /** 名字 → 定义（保持文件里的顺序）。 */
    private final Map<String, DungeonDef> dungeons = new LinkedHashMap<>();

    public DungeonRegistry(NWNDungeon plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "dungeons.yml");
        this.registryFile = new File(plugin.getDataFolder(), "registry.yml");
    }

    // ---------------------------------------------------------------- 读

    public void load() {
        dungeons.clear();
        if (!file.exists()) {
            writeDefaultFile();
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        registry = registryFile.exists() ? YamlConfiguration.loadConfiguration(registryFile)
                : new YamlConfiguration();
        ConfigurationSection root = yaml.getConfigurationSection("dungeons");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            DungeonDef def = DungeonDef.from(name, section);
            def.slug = readSlug(name);
            dungeons.put(name, def);
        }
        plugin.getLogger().info("自建副本 " + dungeons.size() + " 个："
                + (dungeons.isEmpty() ? "（无）" : String.join("、", dungeons.keySet())));
    }

    private String readSlug(String name) {
        String stored = registry.getString("dungeons." + name + ".slug");
        if (stored != null && Names.isValidWorldName(stored)) {
            return stored;
        }
        String slug = allocateSlug(name);
        registry.set("dungeons." + name + ".slug", slug);
        return slug;
    }

    private void writeDefaultFile() {
        try {
            List<String> lines = List.of(
                    "# 管理员自建副本（v1.5.0）——用 /nwmdungeon edit 打开面板来建；下面的字段手改也行。",
                    "#",
                    "# 名字 = 副本名 = loot.yml 里的段名；磁盘上的世界名（维度键）由 registry.yml 里的 slug",
                    "# 决定，必须是小写 ASCII（中文名当不了世界名，插件会自动生成 slug）。",
                    "#",
                    "# 字段：",
                    "#   display                 界面上显示的名字（带颜色代码）",
                    "#   icon                    面板里的图标（Material 名，例如 IRON_DOOR / LODESTONE）",
                    "#   world.enabled           关掉 = 这个副本不可用、入口也不送人",
                    "#   world.force-adventure   进本是否强制冒险模式",
                    "#   world.generator         void / flat / normal（建模板时用哪种就记哪种）",
                    "#   entrance.*              入口建筑的随机生成与权重（M5）",
                    "#   instance-mode           party（一队一份世界，当前只实现了这个）",
                    "#   time-limit-minutes      限时（分钟）",
                    "#   stamina-cost            进本消耗体力（0 = 不消耗）",
                    "#   money-reward            通关发多少金币（走服务器的 eco give）",
                    "#   max-concurrent-runs     同时最多几局（卡的是内存与 tick，不是磁盘）",
                    "#   warn-world-size-mb      模板超过这个体积就在保存时标红提醒",
                    "#   markers                 标记（插件自己维护，不用手写）",
                    "#   stages                  每一关的阵容（**这一段只读**，插件不会改写它）",
                    "#   boss                    首领：位置来自标记 markers.boss，属性写在这里",
                    "#",
                    "# 例子（把前面的 # 去掉即可）：",
                    "# dungeons:",
                    "#   熔岩要塞:",
                    "#     display: '§6熔岩要塞'",
                    "#     time-limit-minutes: 20",
                    "#     stamina-cost: 25",
                    "#     money-reward: 1000",
                    "#     stages:",
                    "#       - name: 第一间",
                    "#         waves: ['ZOMBIE:3', 'ZOMBIE:2,SKELETON:2']   # 每项 = 一波",
                    "#       - name: 第二间",
                    "#         waves: ['LAVA_BRUTE:3']                      # 也可以写 mobs.yml 里的模板名",
                    "#     boss:",
                    "#       type: WITHER_SKELETON",
                    "#       name: '§c熔岩领主'",
                    "#       health: 160",
                    "#       waves: ['WITHER_SKELETON:2']                 # 首领登场前的小怪波",
                    "#       guards: 'CREEPER:3'                          # 随首领一起登场的护卫",
                    "#     markers: {}                                    # 这个由面板写",
                    "dungeons: {}");
            java.nio.file.Files.writeString(file.toPath(), String.join(System.lineSeparator(), lines)
                    + System.lineSeparator());
        } catch (Exception e) {
            plugin.getLogger().warning("dungeons.yml 建不出来：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 写

    public void save() {
        try {
            for (Map.Entry<String, DungeonDef> entry : dungeons.entrySet()) {
                ConfigurationSection section = yaml.getConfigurationSection("dungeons." + entry.getKey());
                if (section == null) {
                    section = yaml.createSection("dungeons." + entry.getKey());
                }
                entry.getValue().writeTo(section);
                registry.set("dungeons." + entry.getKey() + ".slug", entry.getValue().slug);
                if (!registry.isSet("dungeons." + entry.getKey() + ".created")) {
                    registry.set("dungeons." + entry.getKey() + ".created", System.currentTimeMillis());
                }
            }
            yaml.save(file);
            registry.save(registryFile);
        } catch (Exception e) {
            plugin.getLogger().warning("dungeons.yml 保存失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 查

    public Collection<DungeonDef> all() {
        return dungeons.values();
    }

    public int size() {
        return dungeons.size();
    }

    /** 名字或 slug 都能查（返回 null 表示没有）。 */
    /**
     * 按**显示名**找副本：忽略颜色代码与大小写。
     *
     * <p>为什么要这个：配置里存的是带颜色的名字（例如 {@code §f测试要塞}），
     * 而玩家在聊天里打的是 {@code 测试要塞} —— 精确匹配（{@link #get}）对不上，
     * 结果"重复用同一个名字新建"会又建一个出来（而不是进那个已有的副本去改）。
     */
    public DungeonDef findByName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = stripColors(raw).trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        for (DungeonDef def : dungeons.values()) {
            if (stripColors(def.name).trim().toLowerCase(Locale.ROOT).equals(key)) {
                return def;
            }
        }
        return null;
    }

    /** 去掉 §x 颜色代码（§ 后面跟一个字符）。 */
    public static String stripColors(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }

    public DungeonDef get(String key) {
        if (key == null) {
            return null;
        }
        DungeonDef direct = dungeons.get(key);
        if (direct != null) {
            return direct;
        }
        for (DungeonDef def : dungeons.values()) {
            if (def.slug != null && def.slug.equalsIgnoreCase(key)) {
                return def;
            }
        }
        return null;
    }

    /** 模糊匹配：先精确（名字/slug），再找唯一一个"包含"的（方便少打字）。 */
    public DungeonDef find(String key) {
        DungeonDef exact = get(key);
        if (exact != null) {
            return exact;
        }
        if (key == null || key.isBlank()) {
            return null;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        List<DungeonDef> matches = new ArrayList<>();
        for (DungeonDef def : dungeons.values()) {
            if (def.name.toLowerCase(Locale.ROOT).contains(lower)
                    || (def.slug != null && def.slug.contains(lower))) {
                matches.add(def);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public List<String> names() {
        return new ArrayList<>(dungeons.keySet());
    }

    /** 名字里的字符能不能当 yml 的段键（点号会破坏路径式读写，颜色码会毁掉可读性）。 */
    public static String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "名字不能是空的。";
        }
        String name = raw.trim();
        if (name.length() > 24) {
            return "名字太长了（最多 24 个字）。";
        }
        if (name.contains(".")) {
            return "名字里不能有点号（会破坏配置文件的结构）。";
        }
        if (name.contains("§") || name.contains("&")) {
            return "名字里不能有颜色代码（颜色在配置的 display 里写）。";
        }
        if (name.contains(":") || name.contains("/") || name.contains("\\")) {
            return "名字里不能有冒号或斜杠。";
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            return "「" + name + "」被内置副本占用了，换个名字。";
        }
        return null;
    }

    // ---------------------------------------------------------------- 增 / 删

    /** 新建一个副本（只登记配置与世界名；模板世界要另外建）。已存在返回 null。 */
    public DungeonDef create(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (dungeons.containsKey(name)) {
            return null;
        }
        DungeonDef def = new DungeonDef(name);
        def.slug = allocateSlug(name);
        dungeons.put(name, def);
        save();
        plugin.getLogger().info("新建自建副本「" + name + "」，世界名 " + def.slug);
        return def;
    }

    /** 分配一个没被占用的 slug（撞了就加 _2 / _3…）。 */
    private String allocateSlug(String name) {
        String base = Names.slugOf(name);
        Set<String> used = new LinkedHashSet<>();
        for (DungeonDef def : dungeons.values()) {
            if (def.slug != null) {
                used.add(def.slug.toLowerCase(Locale.ROOT));
            }
        }
        for (String key : registry.getConfigurationSection("dungeons") == null
                ? Set.<String>of() : registry.getConfigurationSection("dungeons").getKeys(false)) {
            String stored = registry.getString("dungeons." + key + ".slug");
            if (stored != null) {
                used.add(stored.toLowerCase(Locale.ROOT));
            }
        }
        String slug = base;
        int suffix = 2;
        while (used.contains(slug.toLowerCase(Locale.ROOT))) {
            slug = base + "_" + suffix++;
        }
        return slug;
    }

    /**
     * 删除一个副本：配置与登记都清掉，模板文件夹**不硬删**，挪进
     * {@code templates/_deleted/<slug>-<时间>/} 留一条退路（符合项目一贯做法）。
     */
    public boolean delete(String key, WorldStore store) {
        DungeonDef def = get(key);
        if (def == null) {
            return false;
        }
        dungeons.remove(def.name);
        yaml.set("dungeons." + def.name, null);
        registry.set("dungeons." + def.name, null);
        save();
        File template = store.templateFolder(def.slug);
        if (template.isDirectory()) {
            File graveyard = new File(store.templatesRoot().getParentFile(), "_deleted");
            if (!graveyard.isDirectory()) {
                graveyard.mkdirs();
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            File target = new File(graveyard, def.slug + "-" + stamp);
            if (!template.renameTo(target)) {
                plugin.getLogger().warning("模板移进回收站失败：" + template);
            } else {
                plugin.getLogger().info("模板已移进 templates/_deleted/" + target.getName());
            }
        }
        plugin.getLogger().info("已删除自建副本「" + def.name + "」");
        return true;
    }
}
