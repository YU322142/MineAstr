# AQQBot → MineAstr 功能迁移说明

参考上游：[alazeprt/AQQBot](https://github.com/alazeprt/AQQBot)，审阅基准为 `refactor` 分支提交 `ab7f5693206b8e7ba778c2f9f2b39ab0718c5f1c`。

MineAstr 的实现边界与 AQQBot 不同：AQQBot 是直接运行在 Bukkit/Fabric/Velocity 等 Minecraft 服务端里的 OneBot 客户端；MineAstr 把平台登录、权限和消息发送交给 AstrBot，Minecraft 端通过受认证 WebSocket 提供事件、查询与受控副作用。本发行包配套的 Fabric 1.21.11 Mod 已实现表中标为“已实现”的端到端能力。

## 功能对照

| AQQBot 功能 | MineAstr v0.6.11 | 说明 |
| --- | --- | --- |
| QQ 与游戏双向聊天 | 已实现并扩展 | `relay_sessions` 支持 QQ、Discord 及其他能主动发消息的 AstrBot 平台，可桥接多个会话。 |
| 最大转发长度 | 已实现 | `max_relay_length`，同时受平台和 Minecraft 适配器自身上限约束。 |
| 前缀转发 | 已实现 | `relay_prefix` 控制聊天平台 → 游戏。 |
| 本地文本/正则过滤 | 已实现 | 兼容 `$filter`、`$regex`、`$replaceTo`、`[[space]]` 和 `!CANCEL`。 |
| `$url` 远程词库 | 有意不实现 | 消息处理阶段访问任意 URL 会引入 SSRF、远程配置投毒和可用性风险；请转成本地规则。 |
| `GROUP_NAME` 绑定 | 已实现 | `/mc bind <玩家名>`；玩家名全局唯一，聊天账号由 `platform_id:user_id` 标识。 |
| `VERIFY_CODE` 绑定 | 已实现 | Fabric Mod 在登录拒绝时生成并发送 `binding_code`。 |
| 每账号最大绑定数 | 已实现 | `max_bind_count`。 |
| 绑定/解绑冷却 | 已实现 | 默认沿用 AQQBot 的 60 秒 / 86400 秒。 |
| 未绑定禁止登录 | 已实现 | Fabric 登录查询阶段发送 `player_login_check` 并等待 `event_result`；默认关闭且可配置超时/fail-open。 |
| 绑定后同步白名单 | 已实现 | 支持绑定缓存、可选原版白名单和重连全量对账；副作用由 Mod 配置和审计。 |
| 用户自助解绑 | 已实现 | 多绑定时必须显式指定玩家名。 |
| 管理员绑定/解绑 | 已实现 | `/mc admin_bind`、`/mc admin_unbind`；Discord 用户 mention 可作为目标。 |
| 查询自己的绑定 | 已实现 | `/mc bindings`。 |
| 按玩家查询绑定 | 已实现 | `/mc who`；返回平台和显示名，不返回数据库内部信息。 |
| 在线玩家查询 | 已实现 | `/mc list`，使用既有 `players` 查询。 |
| 服务器状态 | 已实现 | `/mc status`，使用既有 `status` 查询。 |
| TPS/MSPT/CPU | 已实现 | `/mc performance` 使用 Fabric Mod 的 `performance` 查询。 |
| 聊天平台 @游戏玩家 | 已实现 | 识别 `@PlayerName` 并定向通知在线玩家；声音/action bar/title 由 Mod 配置。 |
| 服务器启停通知 | 已实现 | 由 WebSocket hello/断开生成，无需 Mod 新事件。 |
| 玩家进入/离开/死亡通知 | 已实现 | Fabric Mod 上报 `player_join`、`player_leave`、`player_death`。 |
| 远程服务器命令 | 已实现 | `/mc command` 默认关闭；AstrBot 管理员检查后，Mod 仍执行可信用户、精确命令白名单与审计检查。 |
| 文件存储 | 改为 SQLite | SQLite 是 Python 标准库能力，提供事务、唯一约束与并发安全；不再维护 YAML 数据文件。 |
| MySQL 存储 | 未实现 | 当前绑定规模通常很小；如确需多 AstrBot 实例共享绑定，需要另行设计分布式锁、迁移和冲突策略，不能只换连接字符串。 |
| 退群自动解绑 | QQ、Discord 已实现 | QQ 监听 aiocqhttp 的 OneBot `group_decrease`；Discord 监听官方 Pycord 成员离开事件。均可限制目标群/服务器。 |
| 绑定后修改群昵称 | QQ、Discord 已实现 | QQ 调用 `set_group_card`；Discord 保存并恢复原昵称。两端按绑定先后并列显示完整游戏名，超长时保留最早能放下的几项。 |
| OneBot 正向 WS 客户端 | 不需要 | QQ/Discord 连接由 AstrBot 平台适配器统一负责。 |
| AQQBot Java API/Event API | 不兼容 | JVM 插件不能加载到 AstrBot Python 进程；MineAstr 使用 AstrBot 插件事件与 WebSocket 协议。 |
| JavaScript 脚本市场/自定义命令 | 未迁移 | 上游脚本依赖 AQQBot JVM API、Nashorn 和服务端对象，无法安全地直接执行于 AstrBot；应重写为 AstrBot 插件或 LLM 工具。 |
| AQQBot Webhook 管理 API | 未迁移 | AstrBot 已有插件配置、Open API 和平台管理；直接复刻可远程修改配置/执行命令的 WebSocket 会扩大攻击面。 |

## Discord 特有说明

- 普通桥接、绑定和查询使用 AstrBot 统一 API；只有成员离开监听和昵称修改会使用 AstrBot 官方 Discord 适配器暴露的 Pycord 客户端对象。
- Discord 适配器实例 ID 由用户配置，可能不是字面值 `discord`。在频道使用 `/mc bridge_add` 能避免手写错误 ID。
- Discord Guild 管理员不一定等于 AstrBot 管理员。MineAstr 高风险指令只认 AstrBot 的 `event.is_admin()` 或 `bridge_admin_users`。
- 退群监听要求在 Developer Portal 开启 `Server Members Intent`；服务器 Administrator 权限不能代替该开关。
- 昵称同步需要 `Manage Nicknames`，且机器人角色必须高于目标成员；服务器所有者不能被机器人改名。
- AstrBot 开启自动斜杠指令注册后，`/mc` 子指令可以作为 Discord 原生指令使用；未开启时仍可按 AstrBot 的文本唤醒规则使用。

## QQ / OneBot 特有说明

- QQ 使用 AstrBot `aiocqhttp` 平台实例的唯一 ID 组成 `platform_id:QQ号`，不是固定写死为 `qq`。
- `VERIFY_CODE` 对 QQ 与 Discord 使用同一套验证码；绑定成功回复应显示真实玩家名，而不是六位验证码。
- `qq_auto_unbind_on_leave` 监听 OneBot v11 `notice.group_decrease`；建议填写 `qq_group_ids`，避免其他群的离开事件触发全局解绑。
- `qq_auto_group_card` 调用 OneBot `get_group_member_info` 和 `set_group_card`，需要机器人群管理员权限。
- 玩家名规则默认改为 `^\S{1,64}$`，兼容 AQQBot、Floodgate 点号前缀及无空白 Unicode 名称；Mod 端会安全转义白名单命令参数。

## Minecraft Mod 实现状态

配套 `mineastr-fabric-0.6.11.jar` 已在 Minecraft 1.21.11、Fabric API `0.141.4+1.21.11` 上实现 `performance`、`notify_player`、`binding`、重连对账、管理员实时同步、命令二次审批、玩家事件、验证码和异步登录检查。验证码绑定采用服务端认证的纯登录玩家名，不再套用 GROUP_NAME 手填正则，也不会混入登录连接的 IP/端口；白名单同步会按服务器认证模式解析 UUID、清理同名冲突项，并在原版登录校验前按真实身份二次对账。高权限命令工具默认保持关闭，需要按 README 开启。

## 数据迁移

AQQBot 的绑定数据是 `QQ号 -> 玩家名列表`；MineAstr 是 `platform_id:user_id -> 玩家名列表`。自动导入前必须由管理员确定目标 AstrBot QQ 适配器实例 ID，否则仅凭 QQ 数字无法构造稳定 owner key。

当前版本不自动读取 AQQBot 的 YAML/SQLite/MySQL，避免在不知道源实例 ID、字符集和重复绑定处理策略时静默写错数据。小规模迁移可由管理员使用：

```text
/mc admin_bind <astrbot-qq-adapter-id>:<QQ号> <玩家名>
```

批量迁移应另外编写一次性脚本，并在导入前备份 `data/mineastr/bindings.sqlite3`。
