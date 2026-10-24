# NWNDungeon 1.5.1 · 新版配置与MythicMobs接入

本版本同时加入新版配置，推荐先阅读[新版配置手册](CONFIG-V2.md)。以下旧格式片段用于兼容已有配置；新配置将房间写入rooms、首领血量写入boss.attributes.health。

正式版发布于2026-10-24。MythicMobs为可选插件；未安装时原版怪物和NWNDungeon自定义模板继续可用。升级时先备份配置，停服替换JAR并启动；旧服可执行`/nwd migrate`备份转换到新版。

## 如何使用

1. 安装可正常运行的MythicMobs 5.x，在其Mobs目录中定义怪物并用Mythic命令确认能生成。
2. 在NWNDungeon的`config.yml`内置难度，或`dungeons.yml`自建副本中，用`MYTHIC:怪物ID`引用。
3. 更新插件JAR需要停服替换后启动；仅修改副本配置可用`/nwd reload`。Mythic定义的修改用`/mm reload`，再新开一局。

内置难度示例（放入原有`tiers.iron`对应位置）：

```yaml
rooms:
  - waves:
      - 'ZOMBIE:2,MYTHIC:MineGuard:2'
boss:
  type: 'MYTHIC:MineKing'
  waves:
    - 'MYTHIC:MineGuard:3'
  guards: 'MYTHIC:MineGuard:2'
```

自建副本示例（放入原有`dungeons.<副本名>`内，保留世界和标记）：

```yaml
stages:
  - name: '矿洞入口'
    waves:
      - 'SKELETON:2,MYTHIC:MineGuard:2'
boss:
  type: 'MYTHIC:MineKing'
  guards: 'MYTHIC:MineGuard:2'
```

怪物ID的大小写与Mythic定义一致，`MineKing`与`MINEKING`不同。冒号最后一项是数量，例如`MYTHIC:MineGuard:3`表示3只MineGuard。
当前接入生成等级为1；在Mythic中配置该等级的属性及技能。

MythicMobs的Mobs目录示例：

```yaml
MineGuard:
  Type: SKELETON
  Display: '&7矿洞守卫'
  Health: 40
  Equipment:
    - BOW HAND
  Options:
    PreventSunburn: true
    Despawn: false
MineKing:
  Type: HUSK
  Display: '&6矿洞首领'
  Health: 800
  Damage: 8
  Equipment:
    - DIAMOND_SWORD HAND
  Options:
    PreventSunburn: true
    Despawn: false
```

## 血量和属性由谁配置

| 怪物来源 | 属性配置位置 | 副本BossBar |
| --- | --- | --- |
| 原版首领/本插件模板 | 继续使用NWNDungeon的boss.health等配置 | 真实剩余血量 / 本局配置上限 |
| MYTHIC:ID首领 | MythicMobs中的Health、Damage、Skills、Equipment等 | 真实剩余血量 / 实体当前上限 |
| 被Mythic规则接管的原版实体 | 接管后的血量交给Mythic管理 | 自动读取接管后的实际血量 |

神话首领的`boss.health`、`boss.name`即使保留旧值，也不会覆盖Mythic定义；修改神话首领血量请改Mythic的`Health`。
使用`MYTHIC:ID`生成时，NWNDungeon不会额外穿钻石装备、附加强度效果或反复回满血；Mythic技能动态改变血量上限时，副本BossBar跟随实际值。
被Mythic规则接管的普通类型则保留原有生成流程，并在接管后让出血量管理；需要完整采用Mythic定义时请明确使用`MYTHIC:ID`。
神话怪的掉落由Mythic处理，副本通关奖励保持原有规则。Mythic自身开启BossBar时可能同时显示两条，可在其怪物定义中关闭其中一条。

## 生成及关卡边界

- 支持以可战斗原版Mob为载体的Mythic怪，包括常规僵尸、尸壳、骷髅；不支持玩家、盔甲架、展示实体或不能解析为Bukkit战斗Mob的特殊载体。
- 生成前检查落脚点；生成后按实际体型和位置复核。取消生成、未知ID或不安全位置会明确失败，不降级为原版怪、不算击杀。
- 进度跟踪配置中的怪物和护卫。Mythic技能额外召唤物不自动加入清怪名单；跨实体变身不会自动继承原UUID的首领身份。需要清完的怪请写入波次或guards。
- 神话怪失踪先加载并寻找原实体；在已加载区块中持续失踪约5秒后异常结束并退还本局实扣体力，不发通关奖励。不会重建其技能阶段或回退成原版怪。普通原版怪仍使用既有有限补刷。
- Mythic的消失、移除与未经本队有效击杀的首领死亡不会直接判通关。技能设计请保持首领实体在有效击杀前存活，不使用“删除旧实体再换一只”的方式作为通关阶段。

## 验证与诊断

使用真实MythicMobs 5.12.1及本机Purpur 26.1.2隔离服验证；其他5.x版本使用同一公开接口接入，尚不承诺所有版本、扩展和技能包组合均通过实测。
103项离线检查、34项真实Mythic专项通过；安装和不安装MythicMobs时分别通过67项服务端自检。隔离服视距、模拟距离均为3。

保留可选出生血量记录，默认关闭。排查时在NWNDungeon的`config.yml`配置：

```yaml
diagnostics:
  boss-health-trace: true
```

记录位于`plugins/NWNDungeon/diagnostics/boss-health.log`，只记录出生后5秒及有限次数伤害/治疗，约1MB轮转。记录不触发回血或结束副本。

回退时保留配置备份，换回旧版JAR并移除旧版不认识的`MYTHIC:`引用。无需删除世界或重新配置原版难度。

接口依据：[Mythic BukkitAPIHelper](https://mythiccraft.io/javadocs/mythic/io/lumine/mythic/bukkit/BukkitAPIHelper.html)、[Mythic装备配置](https://wiki.mythiccraft.io/mythicmobs/Mobs/Equipment)。
