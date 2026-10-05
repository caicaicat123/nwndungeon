package nwndungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个自建副本里的全部**标记**（存在 {@code dungeons.yml} 的 {@code markers} 段里）。
 *
 * <p>分段规则（计划书 §7.1）：<b>以「关卡门」为界，标记顺序就是推进顺序</b>
 * <pre>
 * 落点 → [第 0 关的刷怪点] → 关卡门 0 → [第 1 关的刷怪点] → 关卡门 1 → … → 首领区 → 出口门
 * </pre>
 * 所以 {@code gates.get(i)} 是"第 i 关结尾那扇门"，第 {@code gates.size()} 关就是首领区。
 * 关卡号一律 **0 基**存盘、显示时 +1。
 *
 * <p>坐标是**模板世界里的绝对坐标**。实例世界是模板的逐字节拷贝，坐标完全通用 ——
 * 这也是"一局一个独立世界"比"往共享世界里贴结构"省事的地方（不需要任何原点换算）。
 */
public final class Markers {

    /** 一个刷怪点：位置 + 属于第几关第几波（wave 从 1 开始）。 */
    public static final class Spawner {
        public int[] pos = new int[3];
        public int stage;
        public int wave = 1;
        public long at;

        public Spawner() {
        }

        public Spawner(int[] pos, int stage, int wave, long at) {
            this.pos = pos;
            this.stage = stage;
            this.wave = wave;
            this.at = at;
        }
    }

    /** 一个检查点：位置 + 脚下 3×3 的方块签名（被打掉就失效）。 */
    public static final class Checkpoint {
        public int[] pos = new int[3];
        public List<String> signature = new ArrayList<>();
        public long at;

        public Checkpoint() {
        }

        public Checkpoint(int[] pos, List<String> signature, long at) {
            this.pos = pos;
            this.signature = signature;
            this.at = at;
        }
    }

    /** 一个箱子标记：位置 + 类型（supply = 每关补给箱 / reward = 通关奖励箱）+ 属于第几关。 */
    public static final class Chest {
        public int[] pos = new int[3];
        public String kind = "supply";
        public int stage;
        public long at;

        public Chest() {
        }

        public Chest(int[] pos, String kind, int stage, long at) {
            this.pos = pos;
            this.kind = kind;
            this.stage = stage;
            this.at = at;
        }

        public boolean isReward() {
            return "reward".equalsIgnoreCase(kind);
        }
    }

    /** 进本落点；null = 用模板世界的出生点。 */
    public int[] start;
    /** 首领登场位置。 */
    public int[] boss;
    /** 通关后的出口门（就是"你放的那扇门"）。 */
    public int[] exitDoor;
    /** 出口门的材质名（记下来给校验/提示用；门本身在模板世界里，不用插件摆）。 */
    public String exitDoorMaterial;
    /** 关卡门，按顺序；第 i 个是"第 i 关结尾的门"。 */
    public final List<int[]> gates = new ArrayList<>();
    public final List<Spawner> spawners = new ArrayList<>();
    public final List<Checkpoint> checkpoints = new ArrayList<>();
    public final List<Chest> chests = new ArrayList<>();

    // ---------------------------------------------------------------- 索引

    /** 一共有几关（有关卡门就有"门后面那一关"，所以 = 门数 + 1，最少 1 关）。 */
    public int stageCount() {
        return Math.max(1, gates.size() + 1);
    }

    public boolean isBossStage(int stage) {
        return stage >= gates.size();
    }

    /** 第 stage 关（wave 波）的刷怪点。wave <= 0 表示"这一关所有波"。 */
    public List<Spawner> spawnersAt(int stage, int wave) {
        List<Spawner> out = new ArrayList<>();
        for (Spawner spawner : spawners) {
            if (spawner.stage == stage && (wave <= 0 || spawner.wave == wave)) {
                out.add(spawner);
            }
        }
        return out;
    }

    /** 第 stage 关一共有几波。 */
    public int waveCount(int stage) {
        int max = 0;
        for (Spawner spawner : spawners) {
            if (spawner.stage == stage) {
                max = Math.max(max, spawner.wave);
            }
        }
        return max;
    }

    public Checkpoint checkpointAt(int[] pos) {
        for (Checkpoint checkpoint : checkpoints) {
            if (same(checkpoint.pos, pos)) {
                return checkpoint;
            }
        }
        return null;
    }

    public Chest chestAt(int[] pos, String kind) {
        for (Chest chest : chests) {
            if (same(chest.pos, pos) && chest.kind.equalsIgnoreCase(kind)) {
                return chest;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 打点

    public void setStart(int[] pos) {
        start = pos;
    }

    public void addSpawner(int[] pos, int stage, int wave) {
        removeSpawner(pos);
        spawners.add(new Spawner(pos, stage, wave, System.currentTimeMillis()));
    }

    public boolean removeSpawner(int[] pos) {
        return spawners.removeIf(spawner -> same(spawner.pos, pos));
    }

    public void addCheckpoint(int[] pos, List<String> signature) {
        checkpoints.removeIf(checkpoint -> same(checkpoint.pos, pos));
        checkpoints.add(new Checkpoint(pos, signature, System.currentTimeMillis()));
    }

    public void addChest(int[] pos, String kind, int stage) {
        chests.removeIf(chest -> same(chest.pos, pos) && chest.kind.equalsIgnoreCase(kind));
        chests.add(new Chest(pos, kind, stage, System.currentTimeMillis()));
    }

    /**
     * 删掉这个位置上的箱子标记（补给箱 / 奖励箱都算）。
     *
     * <p>用途：管理员在编辑世界里**把箱子打掉**，那个标记就该跟着消失 ——
     * 不然开本时插件还会照着坐标放一个新箱子出来，而管理员明明已经把那儿清掉了。
     *
     * @return 删掉了几个（0 = 那儿本来没有箱子标记）
     */
    public int removeChestAt(int[] pos) {
        int before = chests.size();
        chests.removeIf(chest -> same(chest.pos, pos));
        return before - chests.size();
    }

    public void addGate(int[] pos) {
        for (int[] gate : gates) {
            if (same(gate, pos)) {
                return;
            }
        }
        gates.add(pos.clone());
    }

    /** 这道门是不是已经登记成关卡门了。 */
    public boolean isGate(int[] pos) {
        for (int[] gate : gates) {
            if (same(gate, pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 删掉一道关卡门（按坐标）。
     *
     * <p>关键在**关卡重排**：关卡数 = 门数 + 1，删掉第 i 道门以后，
     * "被这道门关住的第 i 关"和"它后面那一关"就合成一关了 ——
     * 所以第 i 关之后的刷怪点 / 补给箱都要往前挪一位，否则它们会挂到错的关卡上。
     *
     * @return true = 确实删掉了一道
     */
    public boolean removeGate(int[] pos) {
        for (int i = 0; i < gates.size(); i++) {
            if (same(gates.get(i), pos)) {
                gates.remove(i);
                for (Spawner spawner : spawners) {
                    if (spawner.stage > i) {
                        spawner.stage--;
                    }
                }
                for (Chest chest : chests) {
                    if (chest.stage > i) {
                        chest.stage--;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** 删掉第 index 道关卡门（0 基）。越界返回 false。 */
    public boolean removeGateByIndex(int index) {
        if (index < 0 || index >= gates.size()) {
            return false;
        }
        return removeGate(gates.get(index));
    }

    /** 清掉出口门标记（出口门不是必需的，没标就用首领位的默认木门兜底）。 */
    public void clearExitDoor() {
        exitDoor = null;
        exitDoorMaterial = null;
    }

    /** 撤销"最近打的那个标记"；返回撤销了什么（没得撤返回 null）。 */
    public String undoLast() {
        long best = -1;
        String what = null;
        Spawner lastSpawner = null;
        Checkpoint lastCheckpoint = null;
        Chest lastChest = null;
        for (Spawner spawner : spawners) {
            if (spawner.at > best) {
                best = spawner.at;
                lastSpawner = spawner;
                what = "刷怪点（第 " + (spawner.stage + 1) + " 关第 " + spawner.wave + " 波）";
            }
        }
        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint.at > best) {
                best = checkpoint.at;
                lastSpawner = null;
                lastChest = null;
                lastCheckpoint = checkpoint;
                what = "检查点";
            }
        }
        for (Chest chest : chests) {
            if (chest.at > best) {
                best = chest.at;
                lastSpawner = null;
                lastCheckpoint = null;
                lastChest = chest;
                what = chest.isReward() ? "奖励箱" : "补给箱";
            }
        }
        if (lastSpawner != null) {
            spawners.remove(lastSpawner);
        } else if (lastCheckpoint != null) {
            checkpoints.remove(lastCheckpoint);
        } else if (lastChest != null) {
            chests.remove(lastChest);
        }
        return what;
    }

    public void clearAll() {
        start = null;
        boss = null;
        exitDoor = null;
        exitDoorMaterial = null;
        gates.clear();
        spawners.clear();
        checkpoints.clear();
        chests.clear();
    }

    public int total() {
        int count = spawners.size() + checkpoints.size() + chests.size() + gates.size();
        if (start != null) {
            count++;
        }
        if (boss != null) {
            count++;
        }
        if (exitDoor != null) {
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- 方块签名

    /** 脚下 3×3 的方块签名（9 个材质名，行优先）。 */
    public static List<String> signatureOf(World world, int[] pos) {
        List<String> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = world.getBlockAt(pos[0] + dx, pos[1] - 1, pos[2] + dz);
                out.add(block.getType().name());
            }
        }
        return out;
    }

    /** 现在的方块和记下来的签名还对得上吗。 */
    public static boolean signatureMatches(World world, Checkpoint checkpoint) {
        if (checkpoint.signature == null || checkpoint.signature.size() < 9) {
            return true;   // 老数据没记签名，不拦
        }
        List<String> now = signatureOf(world, checkpoint.pos);
        for (int i = 0; i < 9; i++) {
            String expected = checkpoint.signature.get(i);
            if (expected != null && !expected.equalsIgnoreCase("ANY") && !expected.equals(now.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 启动副本前的自检（编辑面板的「校验」和 M4 开本前都会用）。
     *
     * @return 人类可读的问题列表；空 = 没问题
     */
    public List<String> validate(World world) {
        List<String> problems = new ArrayList<>();
        if (start == null) {
            problems.add("没标落点（会用世界出生点）");
        }
        if (total() == 0) {
            problems.add("一个标记都没有，这一局进去只能瞎逛");
        }
        if (spawners.isEmpty()) {
            problems.add("没标任何刷怪点 —— 开本后不会有怪");
        }
        if (boss == null && gates.isEmpty()) {
            problems.add("没标首领位、也没标关卡门：整座副本会算成一关（首领区）");
        }
        if (exitDoor == null) {
            problems.add("没标出口门：通关后玩家可能出不去（会退回放在首领位的默认木门）");
        }
        if (world != null) {
            // 关卡门：门还在不在
            for (int i = 0; i < gates.size(); i++) {
                int[] gate = gates.get(i);
                Material material = world.getBlockAt(gate[0], gate[1], gate[2]).getType();
                if (!(world.getBlockAt(gate[0], gate[1], gate[2]).getBlockData() instanceof Door)) {
                    problems.add("第 " + (i + 1) + " 关的关卡门不在了（那里现在是 " + material + "）");
                }
            }
            if (exitDoor != null) {
                Material material = world.getBlockAt(exitDoor[0], exitDoor[1], exitDoor[2]).getType();
                if (!(world.getBlockAt(exitDoor[0], exitDoor[1], exitDoor[2]).getBlockData() instanceof Door)) {
                    problems.add("出口门不在了（那里现在是 " + material + "）");
                }
            }
            // 检查点签名
            for (int i = 0; i < checkpoints.size(); i++) {
                Checkpoint checkpoint = checkpoints.get(i);
                if (!signatureMatches(world, checkpoint)) {
                    problems.add("第 " + (i + 1) + " 个检查点脚下的方块被改过 → 已经失效（重新标一次就会恢复）"
                            + " @ " + fmt(checkpoint.pos));
                }
            }
            // 每关的刷怪点分布
            for (int stage = 0; stage < stageCount(); stage++) {
                if (spawnersAt(stage, 0).isEmpty()) {
                    problems.add("第 " + (stage + 1) + " 关没有刷怪点（这一关会直接算清场）");
                }
            }
        }
        return problems;
    }

    /**
     * 提醒（不是问题）：例如"没写 {@code stages[].waves}，开本后会刷默认僵尸"。
     *
     * <p>和 {@link #validate} 分开是为了让 {@code validate().isEmpty()} 能当"标记都齐了"来用。
     */
    public List<String> hints() {
        List<String> hints = new ArrayList<>();
        if (wavesConfigured == null) {
            hints.add("dungeons.yml 里没写 stages[].waves，开本后会按刷怪点数量刷默认僵尸");
            return hints;
        }
        for (int stage = 0; stage < stageCount(); stage++) {
            if (stage >= wavesConfigured.length || wavesConfigured[stage] <= 0) {
                hints.add("第 " + (stage + 1) + " 关没配 stages[].waves（会刷默认僵尸）");
            }
        }
        return hints;
    }

    /** {@code dungeons.yml} 的 {@code stages} 读进来时顺手记下的"每关几波"，只给校验提示用。 */
    private int[] wavesConfigured;

    /** 由 {@code DungeonDef} 在读配置时写入（{@code null} = 压根没写 stages）。 */
    public void setWaveCounts(int[] counts) {
        this.wavesConfigured = counts;
    }

    /**
     * 给校验用的"每关配了几波"。null = 压根没写 stages（返回的数组长度 = 关卡数）。
     */
    public int[] getWaveCounts() {
        return wavesConfigured;
    }

    // ---------------------------------------------------------------- 存取

    public void readFrom(ConfigurationSection section) {
        clearAll();
        if (section == null) {
            return;
        }
        start = readPos(section.get("start"));
        boss = readPos(section.get("boss"));
        ConfigurationSection exit = section.getConfigurationSection("exit-door");
        if (exit != null) {
            exitDoor = readPos(exit.get("pos"));
            exitDoorMaterial = exit.getString("material");
        } else {
            exitDoor = readPos(section.get("exit-door"));
        }
        for (Object raw : section.getList("gates", List.of())) {
            int[] pos = readPos(raw);
            if (pos != null) {
                gates.add(pos);
            }
        }
        for (java.util.Map<?, ?> raw : section.getMapList("spawners")) {
            Spawner spawner = new Spawner();
            int[] pos = readPos(raw.get("pos"));
            if (pos == null) {
                continue;
            }
            spawner.pos = pos;
            spawner.stage = number(raw.get("stage"), 0);
            spawner.wave = Math.max(1, number(raw.get("wave"), 1));
            spawner.at = raw.get("at") instanceof Number at ? at.longValue() : 0L;
            spawners.add(spawner);
        }
        for (java.util.Map<?, ?> raw : section.getMapList("checkpoints")) {
            int[] pos = readPos(raw.get("pos"));
            if (pos == null) {
                continue;
            }
            List<String> signature = new ArrayList<>();
            if (raw.get("sig") instanceof List<?> list) {
                for (Object entry : list) {
                    signature.add(entry == null ? "ANY" : String.valueOf(entry));
                }
            }
            checkpoints.add(new Checkpoint(pos, signature,
                    raw.get("at") instanceof Number at ? at.longValue() : 0L));
        }
        for (java.util.Map<?, ?> raw : section.getMapList("chests")) {
            int[] pos = readPos(raw.get("pos"));
            if (pos == null) {
                continue;
            }
            String kind = raw.get("kind") == null ? "supply" : String.valueOf(raw.get("kind"));
            chests.add(new Chest(pos, kind, number(raw.get("stage"), 0),
                    raw.get("at") instanceof Number at ? at.longValue() : 0L));
        }
    }

    public void writeTo(ConfigurationSection section) {
        section.set("start", start == null ? null : toList(start));
        section.set("boss", boss == null ? null : toList(boss));
        if (exitDoor == null) {
            section.set("exit-door", null);
        } else {
            section.set("exit-door.pos", toList(exitDoor));
            section.set("exit-door.material", exitDoorMaterial);
        }
        List<List<Integer>> gateOut = new ArrayList<>();
        for (int[] gate : gates) {
            gateOut.add(toList(gate));
        }
        section.set("gates", gateOut);
        List<java.util.Map<String, Object>> spawnerOut = new ArrayList<>();
        for (Spawner spawner : spawners) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("pos", toList(spawner.pos));
            map.put("stage", spawner.stage);
            map.put("wave", spawner.wave);
            map.put("at", spawner.at);
            spawnerOut.add(map);
        }
        section.set("spawners", spawnerOut);
        List<java.util.Map<String, Object>> checkpointOut = new ArrayList<>();
        for (Checkpoint checkpoint : checkpoints) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("pos", toList(checkpoint.pos));
            map.put("sig", checkpoint.signature);
            map.put("at", checkpoint.at);
            checkpointOut.add(map);
        }
        section.set("checkpoints", checkpointOut);
        List<java.util.Map<String, Object>> chestOut = new ArrayList<>();
        for (Chest chest : chests) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("pos", toList(chest.pos));
            map.put("kind", chest.kind);
            map.put("stage", chest.stage);
            map.put("at", chest.at);
            chestOut.add(map);
        }
        section.set("chests", chestOut);
    }

    // ---------------------------------------------------------------- 小工具

    public static int[] of(Location location) {
        return new int[]{location.getBlockX(), location.getBlockY(), location.getBlockZ()};
    }

    /** 看着一个门方块时，取下半个（门的朝向等原样留在世界里，我们只记坐标）。 */
    public static Location doorLower(Block block) {
        Location location = block.getLocation();
        if (block.getBlockData() instanceof Door door && door.getHalf() == Bisected.Half.TOP) {
            location = location.clone().subtract(0, 1, 0);
        }
        return location;
    }

    public static boolean same(int[] a, int[] b) {
        return a != null && b != null && a.length >= 3 && b.length >= 3
                && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    public static String fmt(int[] pos) {
        return pos == null ? "未标" : pos[0] + "," + pos[1] + "," + pos[2];
    }

    private static List<Integer> toList(int[] pos) {
        return List.of(pos[0], pos[1], pos[2]);
    }

    private static int[] readPos(Object raw) {
        List<?> list = null;
        if (raw instanceof List<?> value) {
            list = value;
        } else if (raw instanceof java.util.Map<?, ?> map && map.get("pos") instanceof List<?> value) {
            list = value;
        }
        if (list == null || list.size() < 3) {
            return null;
        }
        try {
            return new int[]{
                    ((Number) list.get(0)).intValue(),
                    ((Number) list.get(1)).intValue(),
                    ((Number) list.get(2)).intValue()};
        } catch (Exception e) {
            return null;
        }
    }

    private static int number(Object raw, int fallback) {
        return raw instanceof Number value ? value.intValue() : fallback;
    }

    /** 材质名（存签名用；避开 Material#isAir 那种要碰注册表的调用）。 */
    public static String materialName(Material material) {
        return material == null ? "ANY" : material.name().toUpperCase(Locale.ROOT);
    }
}
