package nwndungeon;

import java.util.Locale;

/**
 * 副本名 → 世界名（维度键）的命名规则。
 *
 * <p><b>为什么必须这样</b>（M0 实测定论，见 {@code internal/v1.5.0-M0-结论.md}）：
 * 这个版本的存档把**所有维度装在一个存档里**，插件建出来的世界就是
 * {@code world/dimensions/minecraft/<世界名>/}，而世界名会被当成**维度键**写进
 * {@code paper-world.yml}（{@code World: minecraft:<世界名>}）。维度键要过资源位置校验，
 * 只允许 {@code [a-z0-9._-]}，所以：
 * <ul>
 *   <li>中文名、大写、空格都不能当世界名；</li>
 *   <li>配置与聊天里显示中文副本名，磁盘上用 ASCII 的 {@code slug}，
 *       两者由 {@code registry.yml} 对应起来。</li>
 * </ul>
 */
public final class Names {

    /** 模板世界（编辑中/存档里那份）的世界名前缀。 */
    public static final String TEMPLATE_PREFIX = "nwndtpl_";
    /** 每局临时世界的世界名前缀（启动清扫靠它认残留）。 */
    public static final String INSTANCE_PREFIX = "nwndinst_";
    /** 入口建筑的搭建世界前缀（搭完存成结构就删掉）。 */
    public static final String ENTRANCE_PREFIX = "nwndent_";
    /** slug 最长留多少个字符（世界名总长还有上限，别贴到边界）。 */
    private static final int MAX_SLUG = 24;

    private Names() {
    }

    /**
     * 名字 → slug：只保留小写字母/数字与 {@code _ - .}，空格变下划线，其它字符直接丢掉；
     * 如果全丢光了（例如名字是纯中文），返回 {@code d<哈希>} 保证非空且稳定。
     */
    public static String slugOf(String name) {
        String text = name == null ? "" : name.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (keep) {
                builder.append(c);
            } else if (c == ' ' || c == '\t') {
                builder.append('_');
            }
        }
        String slug = builder.toString();
        // 资源位置不能以 . _ - 开头（也不能为空）
        int start = 0;
        while (start < slug.length() && (slug.charAt(start) == '.' || slug.charAt(start) == '_'
                || slug.charAt(start) == '-')) {
            start++;
        }
        slug = slug.substring(start);
        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG);
        }
        while (slug.endsWith(".") || slug.endsWith("_") || slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        if (slug.isEmpty()) {
            slug = "d" + Integer.toHexString(name == null ? 0 : name.hashCode());
        }
        return slug;
    }

    /** 世界名是否合法（维度键的字符集 + 长度）。 */
    public static boolean isValidWorldName(String name) {
        if (name == null || name.isEmpty() || name.length() > 48) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static String templateWorld(String slug) {
        return TEMPLATE_PREFIX + slug;
    }

    public static String instanceWorld(String slug, int index) {
        return INSTANCE_PREFIX + slug + "_" + index;
    }

    /** 入口建筑的搭建世界名。 */
    public static String entranceWorld(String slug) {
        return ENTRANCE_PREFIX + slug;
    }

    /** 是不是插件自己的临时世界（启动清扫只认这几个前缀，不碰别人的世界）。 */
    public static boolean isOwnWorld(String worldName) {
        return worldName != null
                && (worldName.startsWith(TEMPLATE_PREFIX) || worldName.startsWith(INSTANCE_PREFIX)
                || worldName.startsWith(ENTRANCE_PREFIX));
    }

    /** 从 {@code nwndinst_<slug>_<n>} 里取回 slug（取不到返回 null）。 */
    public static String slugOfInstanceWorld(String worldName) {
        if (worldName == null || !worldName.startsWith(INSTANCE_PREFIX)) {
            return null;
        }
        String rest = worldName.substring(INSTANCE_PREFIX.length());
        int cut = rest.lastIndexOf('_');
        return cut <= 0 ? null : rest.substring(0, cut);
    }
}
