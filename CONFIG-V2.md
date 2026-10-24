# 1.5.1 新版配置手册

## 文件放在哪里

```text
plugins/NWNDungeon/
  config.yml          # 全局入口生成、性能、体力与副本规则
  dungeons/
    iron.yml          # 一个文件定义一个副本
    gold.yml
    diamond.yml
    mine.yml          # 自行添加的副本
  mobs.yml            # 可选：本插件的自定义怪物模板
  registry.yml        # 编辑器维护的地图名称映射，请保留
```

新安装直接生成新版默认文件。已有服停服替换JAR并启动后，执行`/nwd migrate`：先备份旧配置，再转换到`dungeons/`并重载。
备份位于`config-backups/<时间>/`。旧文件不删除；同ID新版文件优先。重复转换不会覆盖已有新版文件。
手工修改新版后用`/nwd reload`；已开始或准备中的局保留开局的关卡和首领设置，奖励按既有结算读取规则处理。

`config.yml`中的`generation.chance: 400`仍表示每个符合条件的新区块有1/400概率尝试生成入口。
抽中了生成机会，再按各可用入口的`entrance.weight`抽选。权重10、30、60时，权重10占10/100=10%；它不是固定10%。

## 可直接使用的程序副本样例

保存为`dungeons/mine.yml`。`kind: generated`使用程序生成地图；阵容可写原版类型或`MYTHIC:怪物ID`。

```yaml
schema-version: 2
id: mine                       # 唯一副本ID；修改ID会被视为另一个副本
kind: generated                # generated=程序地图；template=编辑器自建地图
name: '&6废弃矿洞'              # 副本显示名
icon: IRON_BLOCK

entrance:
  random-generation: true
  worlds: [world]              # 留空列表表示服从全局世界名单
  block: IRON_BLOCK            # 程序入口的难度标记方块
  weight: 10                  # 10 / 所有可用入口权重之和；不是固定10%

settings:
  time-limit-minutes: 20
  stamina-cost: 25
  money-reward: 1000

rooms:
  - id: 1                     # 从1开始，按顺序连续编号
    name: '&e矿洞入口'          # 玩家进入这一关时，显示在屏幕上的Title
    waves:
      - mobs:                 # 第一波
          ZOMBIE: 3
          SKELETON: 2
      - mobs:                 # 第二波
          HUSK: 2
  - id: 2
    name: '&c矿洞深处'
    boss: true                # 只有最后一间房写true
    waves:                    # 首领登场前的小怪波；直接出首领可写 waves: []
      - mobs:
          ZOMBIE: 2

boss:
  type: HUSK
  name: '&6矿洞监工'
  guards:                     # 前置波清完后，与首领同时出现
    ZOMBIE: 2

  attributes:                 # 首领属性单独一栏
    health: 160               # 生命值点数；20点=10颗心；支持小数
    damage: 8                 # 基础攻击力，还会受武器与效果影响
    movement-speed: 0.23
    armor: 6
    legacy-strength: false    # 迁移旧版时为true，保留原来的强度/抗性效果

  equipment:                  # 首领装备与附魔单独一栏
    mainhand:
      item: IRON_AXE
      enchants:
        sharpness: 2
    helmet:
      item: IRON_HELMET
  equipment-drop-chance: 0.0  # 装备掉落概率；0=不掉

rewards:
  supply:                     # 关卡补给池；空列表[]表示不放物品
    - item: BREAD
      weight: 3
      min: 1
      max: 3
  completion:                 # 通关奖励池
    - item: DIAMOND
      weight: 1
      min: 1
      max: 2
      chance: 0.3             # 30%概率进入本次候选池，之后仍参与weight抽取
```

一波最多256只，数量必须是正整数。名字、怪物ID可用引号包裹；Mythic的ID大小写必须一致，例如`'MYTHIC:MineGuard': 2`。
空波次、错误编号、重复副本ID、错误血量或未知附魔会明确报错，不能偷偷变成默认配置。

装备部位可用`mainhand`、`offhand`、`helmet`、`chestplate`、`leggings`、`boots`。
未写的装备保留生成时的装备，因此骷髅仍拿弓；需要空手则明确填写`item: AIR`。
除样例外，属性还支持`armor-toughness`和`knockback-resistance`。

奖励保留原有共享箱子及抽取规则，本次配置升级不会改成每人独立奖励。未在副本文件填写的奖励栏目，可继续读取旧`loot.yml`作为兼容来源。

## 自建地图与Mythic

自建地图使用`kind: template`。建议先用编辑器新建和保存地图：编辑器已直接生成新版文件，并维护其中的`world`、`entrance`和`markers`信息。
在该文件补上`rooms`与`boss`即可；`rooms`数量要与关卡门划分出的实际关数一致。不要用程序副本样例覆盖整份自建地图文件，否则会丢掉标记。
自建地图的阵容还可引用`mobs.yml`中的模板名。分享配置时，配套的模板地图和名称映射也需要一起保留。

神话首领示例：

```yaml
boss:
  type: 'MYTHIC:MineKing'
  guards:
    'MYTHIC:MineGuard': 2
```

神话怪的血量、装备、技能在MythicMobs中配置，副本中的`boss.attributes`和`boss.equipment`不覆盖它。
BossBar读取真实血量与动态上限。兼容范围和技能限制见[Mythic说明](RELEASE-1.5.1.md)。

## 禁用、编辑与回退

- 顶层添加`enabled: false`可禁用这一副本，并阻止同ID旧配置重新生效。直接删除新文件则可能重新读到仍保留的旧配置。
- 编辑器保存会检查外部编辑；发现配置已被手工修改时，拒绝覆盖，请先重载。关卡和首领分区保持手写内容。
- 编辑器奖励箱会直接保存到新版副本文件的rewards中；旧副本继续写loot.yml，关闭空箱子则清空对应奖励池。
- 删除自建副本会把对应文件标记为禁用，地图仍走原有回收站，不直接删除地图数据。
- 回退旧JAR前，恢复备份的旧配置；新版文件可另存。1.5.0不认识`MYTHIC:`引用，回退时也要移除这些引用。
