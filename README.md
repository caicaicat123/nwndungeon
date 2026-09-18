# NWNDungeon — 随机副本入口

在世界上随机生成副本入口（带标记的铁门 + 石按钮），按下按钮后把门口一个区块内的玩家一起传送到独立的副本世界。

## 核心规则

- **入口是铁门 + 石按钮**，只有插件登记过的门才会触发；玩家自己放的门是普通门
- **按钮触发 → 读条进本**：按下按钮后亮起 3 秒进度条（带粒子 + 音效），读条期间跑远（默认 6 格）/ 死亡 / 掉线会取消本次进入；
  一次把门所在区块内的所有玩家一起送进去（单人就是单人，多人就是组队）；`trigger.cast-seconds: 0` 可关掉读条恢复瞬移
- **门被破坏即永久失效**：标记存在区块的持久化数据里（铁门不是方块实体、存不了 NBT），门一被破坏就标记为已损毁，原地补新门也不会再触发
- **难度看门底下的方块**：铁块 / 金块 / 钻石块
- **副本世界**：自动创建虚空世界 `nwndungeon`，按槽位隔离，互不干扰
- **房间制关卡（多波）**：怪物是提前刷出的实体（不是刷怪笼），房间之间用关闭的铁门隔开；
  每个房间可以配多波（配置里的 `waves`）：进本只刷第一波，清完一波隔 3 秒刷下一波（标题 + 音效提示「第 2/4 波」）；
  **必须把这一间所有波次都打完**，铁门旁才会出现石按钮（按一下开门），房间角落同时出现补给箱（最多 3 种、每种 1~2 个）；
  没打完时通往下一间的铁门由插件强制保持关闭，按钮也不会出现
- **门的朝向**：走廊是东西向的，铁门必须朝东/朝西（与通道同轴），写错朝向会让门板横卡在门框里、玩家能贴着边缘蹭过去
- **Boss 血条**：屏幕上方显示「第 N 间 · 第 x/y 波 · 剩余 M 只」，首领登场后切成「首领 血量/上限」
- **通关结算**：首领房清完弹出结算界面（用时 / 击杀 / 死亡 / 最终奖励 / 评级），界面只读
- **S/A/B 评级**：用时 ≤ 限时一半且零死亡 = S；≤ 3/4 限时且最多死一次 = A；其余 B
- **副本内是冒险模式**：能拉机关、开箱子，不能破坏或放置方块，爆炸一律禁止
- **检查点**：大厅和每隔一间的房间有检查点平台（磁石 + 告示牌），踩上去记录，死亡后回到最近记录的那一个
- **通关**：首领房的怪物清完后给最终奖励箱，房间中央出现一扇木门，右键即可离开副本
- **死亡不掉落**，限时到点自动送回门口

## 指令

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/dungeon spawn [iron\|gold\|diamond]` | `nwndungeon.admin` | 在当前位置生成一个入口（调试用） |
| `/dungeon test [iron\|gold\|diamond]` | `nwndungeon.admin` | 直接在副本世界生成一个测试本（不占玩家）；**打完所有房间后槽位自动释放** |
| `/dungeon leave` | `nwndungeon.use` | 离开副本 |
| `/dungeon list` | `nwndungeon.admin` | 查看槽位占用 |
| `/dungeon tp <槽位>` | `nwndungeon.admin` | 传送到指定槽位 |
| `/dungeon release <槽位\|all>` | `nwndungeon.admin` | 手动回收槽位（里面的玩家会被送回入口） |
| `/dungeon reload` | `nwndungeon.admin` | 重载配置 |

## 权限节点

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `nwndungeon.use` | `true`（所有人） | 用副本入口：按下门旁按钮时会被一起带进副本；`/dungeon leave` 也需要它 |
| `nwndungeon.admin` | `op` | 管理指令：`/dungeon spawn`、`test`、`list`、`tp`、`reload` |

LuckPerms 用法：

```
/lp group default permission set nwndungeon.use true
/lp group helper  permission set nwndungeon.admin true
```

## 编译

```powershell
powershell -File build.ps1
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
