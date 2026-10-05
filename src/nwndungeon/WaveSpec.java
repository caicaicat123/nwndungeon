package nwndungeon;

import java.util.ArrayList;
import java.util.List;

/**
 * 阵容字符串的解析：{@code "ZOMBIE:3,SKELETON:2"} → {@code [ZOMBIE×3, SKELETON×2]}。
 *
 * <p>单独抽出来是为了**能离线自检**：这一小段是"M4 关卡运行时"里唯一纯粹、又最容易写错的地方
 * （下标、数量、顺序），而真机验证一次要部署+重启。名字到底是不是原版实体、要不要套
 * {@code mobs.yml} 模板，那是需要服务器注册表的事，留在 {@link DungeonRun} 里做。
 */
public final class WaveSpec {

    /** 一项：名字 + 数量。 */
    public record Item(String name, int count) {
    }

    private WaveSpec() {
    }

    /**
     * 解析一波的阵容。
     *
     * <p>规则（和内置副本的写法保持一致）：
     * <ul>
     *   <li>用逗号分隔多项，每项 {@code 名字:数量}；</li>
     *   <li>名字大小写随意（原版实体按大写查、模板名按小写查）；</li>
     *   <li>没有冒号、数量不是数字、数量 &lt;= 0 的项**丢掉**（不报错，配置写错不该把副本卡住）；</li>
     *   <li>顺序保留（同一波里先刷谁后刷谁是有讲究的）。</li>
     * </ul>
     */
    public static List<Item> parse(String spec) {
        List<Item> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String token : spec.split(",")) {
            String text = token.trim();
            if (text.isEmpty()) {
                continue;
            }
            int colon = text.lastIndexOf(':');
            if (colon <= 0 || colon == text.length() - 1) {
                continue;   // 没有名字或没有数量
            }
            String name = text.substring(0, colon).trim();
            int count;
            try {
                count = Integer.parseInt(text.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (name.isEmpty() || count <= 0) {
                continue;
            }
            out.add(new Item(name, count));
        }
        return out;
    }

    /** 把解析结果摊平成"一只一只"的名字表（长度 = 总数）。 */
    public static List<String> expand(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item item : items) {
            for (int i = 0; i < item.count(); i++) {
                out.add(item.name());
            }
        }
        return out;
    }

    /** 这一波一共几只怪。 */
    public static int total(List<Item> items) {
        int sum = 0;
        for (Item item : items) {
            sum += item.count();
        }
        return sum;
    }
}
