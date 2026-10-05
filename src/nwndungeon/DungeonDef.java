package nwndungeon;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 一个「管理员自建副本」的配置（{@code dungeons.yml} 里的一段）。
 *
 * <p>名字（中文，配置段的键）与世界名（ASCII slug）是两件事：世界名要过维度键的字符集校验，
 * 见 {@link Names}。{@code stages} / {@code boss} 这些还没实现的部分**原样留在 yml 里**，
 * 我们只读写自己认识的键（{@link #writeTo} 只 set 自己的键，其它手写内容不动）。
 */
public final class DungeonDef {

    /** 配置段的名字（= 显示名，中文），同时是 {@code loot.yml} 的段名。 */
    public final String name;
    /** 世界名用的 ASCII slug（存 registry.yml，改名也不会变）。 */
    public String slug;
    /** 界面上显示的名字（带颜色代码）。 */
    public String display;
    /** 面板里的图标（Material 名）。 */
    public String icon = "IRON_DOOR";

    // —— 世界 ——
    public boolean worldEnabled = true;
    public boolean forceAdventure = true;
    /** void / flat / normal —— 建模板时用哪种就记哪种，开本时用同一种。 */
    public String generator = "void";

    // —— 入口 ——
    public boolean entranceRandom = true;
    public boolean entranceTeleport = true;
    public int entranceWeight = 10;
    public final List<String> entranceWorlds = new ArrayList<>();

    // —— 运行参数 ——
    public String instanceMode = "party";
    public int timeLimitMinutes = 20;
    public int staminaCost = 0;
    public int moneyReward = 0;
    /** 同时最多几局：卡的是**内存与 tick**（每个加载中的实例世界都要载区块、跑实体），不是磁盘。 */
    public int maxConcurrentRuns = 8;
    /** 保存模板时超过这个体积就标红提醒（正常量级是几百 KB）。 */
    public int warnWorldSizeMb = 20;

    // —— 标记（关卡门 / 刷怪点 / 检查点 / 奖励箱 / 落点 / 首领位 / 出口门）——
    public final Markers markers = new Markers();

    /**
     * 一关的阵容：{@code stages[i].waves[j]} = 第 i 关第 j+1 波的阵容字符串
     * （{@code "ZOMBIE:3,SKELETON:2"}，也可以写 {@code mobs.yml} 里的模板名）。
     *
     * <p><b>只读不写</b>：这一段是给管理员手写的（写回去会把注释和格式冲掉），
     * 插件自己维护的只有 {@link #markers}。
     */
    public static final class Stage {
        public String name = "";
        public final List<String> waves = new ArrayList<>();

        public static Stage from(Object raw) {
            Stage stage = new Stage();
            if (raw instanceof ConfigurationSection section) {
                stage.name = section.getString("name", "");
                stage.waves.addAll(clean(section.getList("waves")));
            } else if (raw instanceof Map<?, ?> map) {
                Object name = map.get("name");
                stage.name = name == null ? "" : String.valueOf(name);
                if (map.get("waves") instanceof List<?> waves) {
                    stage.waves.addAll(clean(waves));
                }
            }
            return stage;
        }

        static List<String> clean(List<?> raw) {
            List<String> out = new ArrayList<>();
            if (raw == null) {
                return out;
            }
            for (Object entry : raw) {
                if (entry != null && !String.valueOf(entry).isBlank()) {
                    out.add(String.valueOf(entry).trim());
                }
            }
            return out;
        }
    }

    /** 首领：位置来自 {@code markers.boss}，属性写在这里。 */
    public static final class BossSpec {
        public String type = "ZOMBIE";
        public String name = "§c首领";
        public int health = 60;
        /** 首领登场前的小怪波。 */
        public final List<String> waves = new ArrayList<>();
        /** 随首领一起登场的护卫（{@code "ZOMBIE:3"}）。 */
        public String guards = "";

        public static BossSpec from(ConfigurationSection section) {
            BossSpec spec = new BossSpec();
            if (section == null) {
                return spec;
            }
            spec.type = section.getString("type", "ZOMBIE");
            spec.name = section.getString("name", "§c首领");
            spec.health = Math.max(20, section.getInt("health", 60));
            spec.waves.addAll(Stage.clean(section.getList("waves")));
            spec.guards = section.getString("guards", "");
            return spec;
        }
    }

    public final List<Stage> stages = new ArrayList<>();
    public final BossSpec boss = new BossSpec();

    /** 第 index 关的阵容（没配返回 null，运行时用默认僵尸兜底）。 */
    public Stage stage(int index) {
        return index >= 0 && index < stages.size() ? stages.get(index) : null;
    }

    public DungeonDef(String name) {
        this.name = name;
        this.display = "§f" + name;
    }

    public String coloredDisplay() {
        return display == null || display.isBlank() ? "§f" + name : display;
    }

    public Material iconMaterial() {
        Material material = icon == null ? null : Material.matchMaterial(icon);
        return material == null ? Material.IRON_DOOR : material;
    }

    public boolean instanceModeIsParty() {
        return !"solo".equalsIgnoreCase(instanceMode);
    }

    // ---------------------------------------------------------------- 读

    public static DungeonDef from(String name, ConfigurationSection section) {
        DungeonDef def = new DungeonDef(name);
        if (section == null) {
            return def;
        }
        def.display = section.getString("display", "§f" + name);
        def.icon = section.getString("icon", "IRON_DOOR");
        def.worldEnabled = section.getBoolean("world.enabled", true);
        def.forceAdventure = section.getBoolean("world.force-adventure", true);
        def.generator = normalizeGenerator(section.getString("world.generator", "void"));
        def.entranceRandom = section.getBoolean("entrance.random-generation", true);
        def.entranceTeleport = section.getBoolean("entrance.teleport", true);
        def.entranceWeight = Math.max(1, section.getInt("entrance.weight", 10));
        def.entranceWorlds.clear();
        def.entranceWorlds.addAll(section.getStringList("entrance.worlds"));
        def.instanceMode = section.getString("instance-mode", "party");
        def.timeLimitMinutes = Math.max(1, section.getInt("time-limit-minutes", 20));
        def.staminaCost = Math.max(0, section.getInt("stamina-cost", 0));
        def.moneyReward = Math.max(0, section.getInt("money-reward", 0));
        def.maxConcurrentRuns = Math.max(1, section.getInt("max-concurrent-runs", 8));
        def.warnWorldSizeMb = Math.max(1, section.getInt("warn-world-size-mb", 20));
        def.markers.readFrom(section.getConfigurationSection("markers"));
        def.stages.clear();
        for (Object raw : section.getList("stages", List.of())) {
            def.stages.add(Stage.from(raw));
        }
        def.boss.waves.clear();
        BossSpec boss = BossSpec.from(section.getConfigurationSection("boss"));
        def.boss.type = boss.type;
        def.boss.name = boss.name;
        def.boss.health = boss.health;
        def.boss.guards = boss.guards;
        def.boss.waves.addAll(boss.waves);
        // 顺便记下"每关配了几波"，校验时用来提示管理员（阵容那一段是只读的，插件不改写）
        int stageCount = def.markers.stageCount();
        int[] waveCounts = new int[stageCount];
        for (int i = 0; i < stageCount; i++) {
            Stage stage = def.stage(i);
            waveCounts[i] = stage == null ? 0 : stage.waves.size();
        }
        def.markers.setWaveCounts(def.stages.isEmpty() ? null : waveCounts);
        return def;
    }

    public static String normalizeGenerator(String raw) {
        String mode = raw == null ? "void" : raw.toLowerCase(Locale.ROOT).trim();
        return switch (mode) {
            case "normal", "flat", "void" -> mode;
            default -> "void";
        };
    }

    /** 只写自己认识的键 —— 手工写的 {@code stages} / {@code boss} 等原样保留。 */
    public void writeTo(ConfigurationSection section) {
        section.set("display", coloredDisplay());
        section.set("icon", icon);
        section.set("world.enabled", worldEnabled);
        section.set("world.force-adventure", forceAdventure);
        section.set("world.generator", generator);
        section.set("entrance.random-generation", entranceRandom);
        section.set("entrance.teleport", entranceTeleport);
        section.set("entrance.weight", entranceWeight);
        if (!entranceWorlds.isEmpty()) {
            section.set("entrance.worlds", entranceWorlds);
        }
        section.set("instance-mode", instanceMode);
        section.set("time-limit-minutes", timeLimitMinutes);
        section.set("stamina-cost", staminaCost);
        section.set("money-reward", moneyReward);
        section.set("max-concurrent-runs", maxConcurrentRuns);
        section.set("warn-world-size-mb", warnWorldSizeMb);
        ConfigurationSection markerSection = section.getConfigurationSection("markers");
        if (markerSection == null) {
            markerSection = section.createSection("markers");
        }
        markers.writeTo(markerSection);
    }
}
