# N00bCocoon 新手茧

> 🫘 低价值新人的保护壳 — 一个为 Paper 1.21+ 设计的新手保护插件

## 功能

- **双条件保护**：背包物品价值 < 阈值 **且** 注册时间 < 阈值时生效
- **生物免疫**：敌对生物无法锁定被保护玩家，伤害完全免疫
- **PvP减伤**：被保护玩家受到的PvP伤害降低，单次最高N点
- **认证适配**：等登录插件认证通过后才发通知，兼容 CaelLabAuth / AuthMe / NLogin 等
- **到期通知**：保护到期时全屏Title弹出，离线玩家排队等上线再弹
- **管理命令**：服主可手动给予/撤销保护

## 安装

1. 下载 [Releases](https://github.com/yunyun-3782/cocoon/releases) 中的 `N00bCocoon-x.x.x.jar`
2. 放入服务器 `plugins/` 目录
3. 重启服务器或 `/reload`
4. 编辑 `plugins/N00bCocoon/config.yml` 自定义配置
5. `/cocoon reload` 热重载配置

## 依赖

| 插件 | 必需 | 说明 |
|------|------|------|
| Paper 1.21+ | ✅ | 服务端核心 |
| DeathDrop | ❌ 可选 | 提供物品价值计算，无则使用内置表 |

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/cocoon` | 所有人 | 查看自己保护状态 |
| `/cocoon status` | 所有人 | 同上 |
| `/cocoon check <玩家>` | `n00bcocoon.admin` | 查看他人状态 |
| `/cocoon ungive <玩家>` | `n00bcocoon.admin` | 手动结束保护 |
| `/cocoon give <玩家>` | `n00bcocoon.admin` | 手动恢复保护（重置48h） |
| `/cocoon reload` | `n00bcocoon.admin` | 热重载配置 |

## 配置

```yaml
conditions:
  value-threshold: 5000      # 背包价值低于此值才保护
  time-threshold-hours: 48   # 注册后多少小时内保护

effects:
  mob-immunity: true         # 免疫怪物伤害
  mob-aggro-cancel: true     # 取消怪物仇恨
  pvp-damage-multiplier: 0.5 # PvP伤害倍率
  pvp-max-damage: 10         # PvP单次伤害上限

notifications:
  wait-for-auth: true        # 等认证后才发通知
  auth-timeout-seconds: 15   # 认证检测超时
  expire-title: true         # 到期弹全屏Title
  expire-queue-offline: true # 离线排队等上线弹
```

完整配置见 [config.yml](src/main/resources/config.yml)

## 兼容性

- ✅ CaelLabAuth
- ✅ AuthMe
- ✅ NLogin
- ✅ 其他设置 `xxx.authenticated` 权限的登录插件
- ✅ 无登录插件（超时自动放行）

## 构建

```bash
git clone https://github.com/yunyun-3782/cocoon.git
cd cocoon
gradle build
# jar 在 build/libs/N00bCocoon-x.x.x.jar
```

## 相关插件

| 插件 | 说明 | 链接 |
|------|------|------|
| 🔻 DeathDrop | 死亡按物品价值百分比掉落 | [GitHub](https://github.com/yunyun-3782/DeathDrop) · [插件页](https://minecraft.caellab.com/plugin/deathdrop) |
| ⏰ RealtimeSync | 游戏昼夜与真实时间同步 | [GitHub](https://github.com/yunyun-3782/RealtimeSync) · [插件页](https://minecraft.caellab.com/plugin/realtimesync) |
| 🫘 N00bCocoon | 新手茧 — 低价值新人保护 | [GitHub](https://github.com/yunyun-3782/cocoon) · [插件页](https://minecraft.caellab.com/plugin/cocoon) |

## 许可证

[CaelLab BY-SA Code License](https://caellab.com/license/bysa-code)
