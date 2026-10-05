# NWNDungeon — 随机副本入口

在世界上随机生成副本入口 —— 一栋**按难度缩放规模的遗迹建筑**（铁 7×7 最小 → 钻石 11×11 最大），
正中是带标记的铁门，门上方与下方各嵌一块难度方块；按下门前台座上的石按钮，把门口一个区块内的玩家一起送进独立的副本世界。

## 核心规则

- **入口是遗迹 + 铁门 + 石按钮**：按钮装在门旁凿制石砖台座上（按一下给门通电 → 触发进本）；只有插件登记过的门才会触发，玩家自己放的门是普通门
- **遗迹按难度递进**：铁 7×7 / 金 9×9 / 钻 11×11，墙高与装饰一起变多；风格 = 残缺石砖 + 苔石 + 断柱 + 灯笼，地面自动垫平/削平不会悬空
- **按下按钮 → 读条进本**：按下后亮起 3 秒进度条（带粒子 + 音效），读条期间跑远（默认 6 格）/ 死亡 / 掉线会取消本次进入；
  一次把门所在区块内的所有玩家一起送进去（单人就是单人，多人就是组队）；`trigger.cast-seconds: 0` 可关掉读条恢复瞬移
- **门被破坏即永久失效**：标记存在区块的持久化数据里（铁门不是方块实体、存不了 NBT），门一被破坏就标记为已损毁，原地补新门也不会再触发
- **难度看门底下的方块**：铁块 / 金块 / 钻石块
- **副本世界**：自动创建虚空世界 `nwndungeon`，按槽位隔离，互不干扰
- **房间制关卡（多波）**：怪物是提前刷出的实体（不是刷怪笼），房间之间用关闭的铁门隔开；
  每个房间可以配多波（配置里的 `waves`）：进本只刷第一波，清完一波隔 3 秒刷下一波（标题 + 音效提示「第 2/4 波」）；
  **必须把这一间所有波次都打完**，门前走廊地板上才会出现石压力板（踩上去开门），房间角落同时出现补给箱（最多 3 种、每种 1~2 个）；
  没打完时通往下一间的铁门由插件强制保持关闭，压力板也不会出现
- **门的朝向**：走廊是东西向的，铁门必须朝东/朝西（与通道同轴），写错朝向会让门板横卡在门框里、玩家能贴着边缘蹭过去
- **Boss 血条**：屏幕上方显示「第 N 间 · 第 x/y 波 · 剩余 M 只」，首领登场后切成「首领 血量/上限」
- **通关结算**：首领房清完弹出结算界面（用时 / 击杀 / 死亡 / 最终奖励 / 评级），界面只读
- **S/A/B 评级**：用时 ≤ 限时一半且零死亡 = S；≤ 3/4 限时且最多死一次 = A；其余 B
- **副本内是冒险模式**：能拉机关、开箱子，不能破坏或放置方块，爆炸一律禁止
- **副本内只能用 `/dungeon`**：进本后其它命令一律被拦下（默认额外放行聊天 / 私聊与登录类命令，
  名单在 `config.yml` 的 `rules.allowed-commands`；有 `nwndungeon.admin` 权限的人不受这条限制）
- **检查点**：磁石平台（带告示牌），踩上去记录、死亡后回到最近记录的那一个；**大厅那块就铺在出生点正下方 —— 进本第一脚即检查点**，其余检查点在偶数房间西侧（避开刷怪点）
- **通关**：首领房的怪物清完后给最终奖励箱，房间中央出现一扇木门，右键即可离开副本
- **死亡不掉落**，限时到点自动送回门口

## 指令

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/dungeon spawn [iron\|gold\|diamond]` | `nwndungeon.admin` | 在当前位置生成一个入口（调试用） |
| `/dungeon test [iron\|gold\|diamond]` | `nwndungeon.admin` | 直接在副本世界生成一个测试本（不占玩家）；**打完所有房间后槽位自动释放** |
| `/dungeon leave` | `nwndungeon.use` | 离开副本 |
| `/dungeon list` | `nwndungeon.admin` | 查看槽位占用 |
| `/dungeon locate [难度\|any]` | 所有人 | 找离你最近的自然生成副本入口（距离 / 方位 / 坐标） |
| `/dungeon stamina` | `nwndungeon.use` | 查看自己的体力（当前 / 上限 / 下一点回复 / 各难度消耗） |
| `/dungeon tp <槽位>` | `nwndungeon.admin` | 传送到指定槽位 |
| `/dungeon release <槽位\|all>` | `nwndungeon.admin` | 手动回收槽位（里面的玩家会被送回入口） |
| `/dungeon reload` | `nwndungeon.admin` | 重载配置 |
| `/dungeon help [页码\|子命令]` | 所有人 | 看帮助（文本由命令登记表生成，含用法/参数/权限/示例/易错点） |
| `/nwmdungeon edit` | `nwndungeon.dev` | **打开自建副本编辑器面板**（箱子 GUI，见下） |
| `/nwmdungeon new world create <名字> [void\|flat\|normal]` | `nwndungeon.dev` | 新建一个模板世界并进入编辑态 |
| `/nwmdungeon new world save` | `nwndungeon.dev` | 保存模板（等价于面板里的「保存」） |
| `/nwmdungeon list` | `nwndungeon.dev` | 列出所有自建副本 |
| `/nwmdungeon delete <名字>` | `nwndungeon.dev` | 删除自建副本（模板挪进 `templates/_deleted/`） |
| `/nwmdungeon test <名字>` | `nwndungeon.dev` | 立刻开一局自测 |
| `/nwmdungeon end [名字]` | `nwndungeon.dev` | 结束正在跑的自建副本 |
| `/nwmdungeon spawn <名字>` | `nwndungeon.dev` | 在你面前贴一座自建副本的入口（门与触发点顺手登记好） |
| `/nwmdungeon new entrance create <副本名字>` | `nwndungeon.dev` | 进虚空搭建世界搭一座门楼当入口 |
| `/nwmdungeon new door` / `new trigger` | `nwndungeon.dev` | 搭入口时登记门 / 触发点（**看着那个方块**执行） |
| `/nwmdungeon doctor` | `nwndungeon.dev` | 自检四件事：复制世界 / 标记机制 / 结构读写 / **入口登记（PDC）**（控制台也能跑） |
| `/nwmdungeon reload` | `nwndungeon.dev` | 重载 `dungeons.yml` / `registry.yml` |

## 权限节点

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `nwndungeon.use` | `true`（所有人） | 用副本入口：按下门旁按钮时会被一起带进副本；`/dungeon leave` 也需要它 |
| `nwndungeon.admin` | `op` | 管理指令：`/dungeon spawn`、`test`、`list`、`tp`、`release`、`reload`；**并且在副本里不受「只能用 /dungeon」这条限制** |
| `nwndungeon.dev` | `op` | 自建副本创作工具：`/nwmdungeon …`（面板、模板世界、测试开局）；同样不受副本内命令限制 |

## PlaceholderAPI 占位符

装了 PlaceholderAPI 就自动注册（菜单 / 记分板 / 聊天都能用）：

| 占位符 | 含义 |
| --- | --- |
| `%nwndungeon_stamina%` | 当前体力 |
| `%nwndungeon_stamina_max%` | 体力上限 |
| `%nwndungeon_stamina_next%` | 距离回下一点还有多少秒（已满 = 0） |
| `%nwndungeon_stamina_next_text%` | 好看版：`已满` / `3 分 20 秒` |
| `%nwndungeon_stamina_bar%` | 进度条（■ / □ 各 10 格） |
| `%nwndungeon_stamina_full%` | 是否已满（true / false） |
| `%nwndungeon_cost_iron%` / `_gold` / `_diamond` | 各难度进本消耗的体力 |
| `%nwndungeon_money_iron%` / `_gold` / `_diamond` | 各难度通关发放的金币 |

## 自建副本（`/nwmdungeon`，1.5.0 起）

> 状态：**1.5.0-M6 预览**（已在本地 Purpur 26.1.2 测试服跑通 `/nwd doctor` 四段自检）。从"搭副本 + 搭入口"到"打完收掉"整套是通的：
> 建模板 → 点图标标完标记 → 保存 → 搭一座门楼存成入口 → 贴到主世界 → 按按钮进本 →
> 按关卡分波刷怪 → 清完开门 → 首领确认死亡 → 结算 → 卸载删掉。

跟内置副本（iron/gold/diamond，代码程序化生成房间链）**并存**：内置副本照旧，
自建副本是"你搭一座世界，每次开本复制一份给这队人打"。

### 怎么用

```
/nwmdungeon edit                      # 打开面板：列表页（新建 / 进编辑 / 右键删除）
/nwmdungeon edit → 新建副本 → 聊天里打名字   # 建空世界、把你拉进去、创造+飞行
（在虚空世界里搭副本；面板里点「落点」记下落点）
/nwmdungeon edit → 保存                # 落盘 → 卸载 → 搬成模板（旧模板自动备份）
/nwmdungeon test <名字>                # 复制一份开一局，自己进去跑
/nwmdungeon end                       # 结束这一局（卸载并删掉临时世界）
```

全程**点图标，不用打字**；需要"指某个方块"的动作（关卡门 / 出口门 / 奖励箱）改成
**先用准星看着它、再点对应图标** —— 你放的是什么门就存什么门，命令里不写材质参数。

### 标记都有哪些（M3）

| 标记 | 怎么打 | 说明 |
| --- | --- | --- |
| 落点 | 站好点「落点」 | 没标就用世界出生点 |
| 关卡门 | **看着一扇门**点「关卡门」 | 结束当前关、开启下一关；**关卡数 = 门数 + 1**，最后一关是首领区；未清场时插件不许玩家推开它 |
| 出口门 | **看着一扇门**点「出口门」 | 通关后右键它离开 |
| 首领位 | 站好点「首领位」 | 属性写在 `dungeons.yml` 的 `boss:` |
| 刷怪点 | ◀▶ 选关卡与波次，站好点「刷怪点」 | 刷什么怪写 `stages[].waves`；同一坐标重复标 = 覆盖 |
| 检查点 | 站好点「检查点」 | 记下脚下 3×3 的**方块签名**；方块被拆 → 该检查点失效（补回原样即恢复） |
| 补给箱 / 奖励箱 | **看着箱子**点图标 → 弹出的箱子里放东西 | 关掉界面就写进 `loot.yml`；权重/概率在 yml 里调 |

面板上还有：**标记清单**（逐条列出）、**校验**（挑出没标齐 / 失效的）、**撤销**、**清空**、
**显示标记**（用粒子把标过的位置标出来）、**传送**到落点/首领位。
斜杠版本一应俱全：`new start / checkpoint / spawner <波次> / gate / exit / boss / reward / stage / wave / undo / list`。

`/nwmdungeon doctor` 是自检，**四段都不需要玩家在线**：
① 复制世界这条路（建世界 → 埋金块 → 保存 → 复制成新世界名 → 建世界 → 查金块 → 全删掉）；
② 标记机制（检查点签名"拆一块就失效、补回去就恢复"、门的上下半格取法）；
③ 结构读写保真度（抓取带箱子内容的场地 → 存 .nbt → 读回来 → 贴到别处 → 核对金块/门朝向/箱子内容）；
④ 入口登记（放一扇木门 + 按钮 → 写进区块 PDC → 从门查 → **从开关反查** → 打掉门作废）。
最后还会打印"自然生成抽签池"，告诉你为什么某个入口不生成。

### 入口建筑（自己搭一座门楼）

```
/nwmdungeon new entrance create <副本名字>   # 进虚空搭建世界（脚下有落脚台）
（搭一座门楼：一扇门 + 门旁一个按钮/压力板/拉杆）
/nwmdungeon new door      # 看着那扇门        —— 你放的是什么门就存什么门（木门/铜门/铁门都行）
/nwmdungeon new trigger   # 看着按钮/压力板/拉杆
/nwmdungeon new entrance save                 # 自动框出建筑范围 → 存成结构
/nwmdungeon spawn <副本名字>                  # 在你面前贴一座出来（门与触发器顺手登记好）
```

面板上是同一套（列表页点「新建入口建筑」→ 入口页点「入口门」「触发点」「保存入口」）。
入口结构存在 `templates/entrances/<slug>.nbt` + `.yml`，里面**显式记着"哪一格是门、哪一格是开关"
以及门到底是什么材质** —— 所以不再像老实现那样只认"铁门 + 石头按钮"。

自建入口也能参与自然生成：副本配置里的 `entrance.random-generation: true`（开）+ `weight`（权重）
+ `worlds`（允许在哪些世界）；新区块抽签时，它与内置难度**共用一个权重池**。
关掉 random-generation 就不会自然出现，但 `/nwmdungeon spawn` 仍然能贴。
### 阵容与首领写在哪

`dungeons.yml`（每个副本一段；`markers` 由面板维护，**`stages` / `boss` 是给手写的、插件只读不写**）：

```yaml
dungeons:
  熔岩要塞:
    display: '§6熔岩要塞'
    time-limit-minutes: 20
    stamina-cost: 25
    money-reward: 1000
    max-concurrent-runs: 8
    stages:
      - name: 第一间
        waves: ['ZOMBIE:3', 'ZOMBIE:2,SKELETON:2']   # 每项 = 一波
      - name: 第二间
        waves: ['LAVA_BRUTE:3']                      # 也可以写 mobs.yml 里的模板名
    boss:
      type: WITHER_SKELETON
      name: '§c熔岩领主'
      health: 160
      waves: ['WITHER_SKELETON:2']                   # 首领登场前的小怪波
      guards: 'CREEPER:3'                            # 随首领一起登场的护卫
```

关数 = 关卡门数 + 1（最后那关是首领区）：第 i 关用 `stages[i]` 的阵容，
刷怪点用面板里"第 i 关"下标的那一批。**没配 `waves` 的一关会按刷怪点数量刷默认僵尸**，
面板的「校验」会提醒你。奖励内容在 `loot.yml` 的 `dungeons.<副本名>.supply-chest / reward-chest`
（由奖励箱界面写入，权重/概率在那里调）。

### 文件与目录

```
plugins/NWNDungeon/
  dungeons.yml          # 每个副本一段：显示名 / 生成器 / 时限 / 体力 / 金币 / 并发上限 / 标记
  registry.yml          # 副本名 ↔ 世界名（slug）对照表（中文名不能当世界名，见下）
  templates/
    worlds/<slug>/      # 模板 = 一个【维度文件夹】的副本（不是"整个世界"）
    _backup/            # 每次保存前的上一版
    _deleted/           # 删掉的模板挪到这里（不硬删）
    _recovered/         # 崩溃/重启时没保存完的编辑世界挪到这里（不硬删）
  pending-delete.txt    # 删不掉的临时世界，下次启动再试
服务器根目录/
  world/dimensions/minecraft/nwndtpl_<slug>/       # 编辑中的模板世界（保存时搬回 templates/）
  world/dimensions/minecraft/nwndinst_<slug>_<n>/  # 每局临时世界（结束就卸载并删除）
```

### 几个必须知道的点

- **世界名必须是小写 ASCII**：这个版本的世界名会被当成维度键（`paper-world.yml` 里写
  `World: minecraft:<名字>`），只允许 `[a-z0-9._-]`。所以中文副本名只用于显示，
  磁盘上由插件生成 slug 并记在 `registry.yml`。
- **模板平时不是"世界"**，只是 `plugins/` 下的一份文件夹（不占内存、不 tick）；
  只有编辑与开本时才复制进 `world/dimensions/minecraft/` 真的加载。
- **一局的代价很小**：复制的是**一个维度文件夹**（几十个文件、通常不到 1 MB）。
  真正要管的是**同时加载几个世界**（内存与 tick）→ 看 `max-concurrent-runs`。
- **一次只能一个人编辑同一个副本**；不同副本可以各改各的。

LuckPerms 用法：

```
/lp group default permission set nwndungeon.use true
/lp group helper  permission set nwndungeon.admin true
/lp group builder permission set nwndungeon.dev true      # 能搭自建副本的人
```

## 老编辑器（`/dungeon edit`）已退役

1.5.0 起 `/dungeon edit`、`/dungeon template` 会提示改用 `/nwmdungeon edit`。
`Template` / `TemplateEditor` 的代码暂时留着（M5 做入口建筑时要抽里面的结构读写工具）。

## 掉落表（`loot.yml`）

`plugins/NWNDungeon/loot.yml` 按副本分段写**补给箱**与**最终奖励箱**，改完 `/dungeon reload` 生效：

| 键 | 说明 |
| --- | --- |
| `item` | 物品（原版 ID） |
| `weight` / `min` / `max` / `chance` | 权重 / 数量区间 / 本次是否进候选池（0~1） |
| `enchants: { SHARPNESS: 3 }` | 写死附魔，等级不夹（写 6 就是 6） |
| `random-enchants: 2` 或 `[1, 3]` | 随机附魔条数；附魔名与等级从该段的 `enchant-pool` / `enchant-levels` 取，等级按原版上限夹 |

**附魔书不会再出空书**：两个附魔键都没写、物品又是附魔书时，插件会自动补随机附魔
（空附魔书在原版里是废纸，铁砧上也用不了）。附魔名写错只会在日志里警告一次并跳过。

## 编译

```powershell
# 用 PowerShell 7（pwsh）跑；Windows PowerShell 5.1 会把 UTF-8 脚本按 ANSI 读，中文注释会导致语法报错
pwsh -File build.ps1
```

- 依赖：把 `paper-api.jar` 放进项目根目录的 `lib\`（构建时会把 `lib\*.jar` 全部加进 classpath）；没放就退回本机约定路径 `simpfun-ops\opsbridge\lib`
- 需要 JDK 21：脚本里的 `$jdk` 指向 `C:\Program Files\Java\jdk-21\bin`，换机器改这一行
- 产物：`dist\nwndungeon-<版本号>.jar`，版本号取自 `plugin.yml`

## 安装

1. 直接下载 `dist/` 里最新的 jar（或按上面自己编译）
2. 丢进服务器的 `plugins/` 目录，重启服务器
3. 首次启动会生成 `plugins/NWNDungeon/config.yml`；改配置用 `/dungeon reload` 重载，**换 jar 必须重启**

需要 Paper / Purpur 1.21+（`plugin.yml` 里 `api-version: '1.21'`）。

## 许可

MIT License，见 [LICENSE](LICENSE)。

## 许可证

MIT License —— 可以自由使用、修改、二次开发甚至商用，保留版权声明即可（详见 [LICENSE](LICENSE)）。

版权所有 (c) 2026 新世界网络（New World Network）。
