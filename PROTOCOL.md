# MineAstr WebSocket 协议

本文描述 AstrBot 插件 `v0.6.9` 接受的协议。协议号仍为 `1`：新增消息均为可选扩展，旧版 Mod 的 `hello`、`chat`、`ping`、`query` 和 `query_result` 不受影响。

## 连接与认证

Mod 连接：

```text
GET ws://<astrbot-host>:8765/ws
Authorization: Bearer <token>
```

连接后必须先发送：

```json
{
  "type": "hello",
  "protocol": 1,
  "server_id": "survival",
  "server_name": "Survival Server",
  "mod_version": "0.6.9"
}
```

服务端只信任该连接在 `hello` 中登记的 `server_id` / `server_name`。后续 `chat` 或 `event` 中伪造的同名字段会被覆盖。未发送 `hello` 就提交聊天、事件或查询结果会被拒绝。

`server_id` 在同一个 AstrBot 实例中应保持唯一、稳定，长度不要超过 64 字符。

所有 `player_name` 字段必须只包含 Minecraft/Floodgate 认证得到的原始玩家名，不得附加远端 IP、端口或其他日志上下文。旧 Mod 曾发送形如 `玩家名 (/地址:端口)` 的显示值；插件 0.6.6 会仅为兼容迁移而清理这种旧值。

## 既有消息

### Mod → AstrBot 聊天

```json
{
  "type": "chat",
  "message_id": "uuid",
  "player_uuid": "minecraft-player-uuid",
  "player_name": "Steve",
  "content": "@AstrBot 现在有几个人？"
}
```

### AstrBot → Mod 聊天

```json
{
  "type": "chat",
  "message_id": "uuid",
  "sender_name": "discord/Alice",
  "content": "大家好",
  "translations": {
    "en_us": "Hello everyone",
    "ja_jp": "みなさん、こんにちは"
  },
  "show_original": true
}
```

`translations` 与 `show_original` 均为 v0.6.7 可选扩展。Mod 应按每位在线玩家的 `clientInformation().language()` 选择精确 locale，找不到时可回退到同语言族；仍找不到、译文无效或玩家关闭翻译时显示 `content`。安装同版客户端 Mod 的玩家可通过单独的 C2S 偏好包覆盖 `show_original` 并关闭译文；不要修改旧版客户端能力包的 codec，以免协议不匹配导致断线。

AstrBot 插件只发送纯文本，不把译文解析为命令或 JSON 组件。目标语言数量、文本长度和模型等待时间都必须受限；翻译模型失败时不得丢弃原文。

### 查询

AstrBot 发出 `type=query`，Mod 必须复制 `message_id` 并返回 `type=query_result`。查询结果只能由收到该请求的同一个 WebSocket 连接完成；其他连接伪造相同 `message_id` 会被忽略。

```json
{
  "type": "query",
  "message_id": "uuid",
  "query": "players",
  "time_ms": 1785196800000
}
```

```json
{
  "type": "query_result",
  "message_id": "uuid",
  "query": "players",
  "ok": true,
  "data": {
    "count": 2,
    "players": ["Steve", "Alex"]
  }
}
```

## v0.6.6 查询扩展

### `performance`

无额外请求参数。推荐响应字段：

```json
{
  "type": "query_result",
  "message_id": "uuid",
  "query": "performance",
  "ok": true,
  "data": {
    "tps": 20.0,
    "mspt": 12.4,
    "cpu_percent": 18.6,
    "memory_used_mb": 2048
  }
}
```

无法获取某项时可以省略，不要返回伪造值。TPS/MSPT 可直接由服务端 tick 统计得到，不强制依赖 spark。

### `notify_player`

```json
{
  "type": "query",
  "message_id": "uuid",
  "query": "notify_player",
  "player_name": "Steve",
  "sender_name": "Alice",
  "sender_id": "123456789",
  "sender_platform": "my-discord",
  "message": "@Steve 回基地"
}
```

Mod 应只允许通知在线的准确玩家名，并在服务端配置中决定是否播放声音、显示 action bar/title。不要把 `message` 当作命令或 JSON 组件直接执行。

### `binding`

```json
{
  "type": "query",
  "message_id": "uuid",
  "query": "binding",
  "action": "bind",
  "player_name": "Steve",
  "owner_key": "my-discord:123456789",
  "owner_display": "Alice"
}
```

`action` 支持 `bind` / `unbind` / `reset`。`reset` 不带玩家身份，用于 Mod 每次重连后先清空绑定缓存，再由 AstrBot 逐条发送当前 SQLite 中的全部绑定；启用白名单同步时也会移除旧缓存对应的白名单条目。AstrBot SQLite 数据库仍是聊天平台绑定的事实来源。Mod 应按服务器认证模式解析 `NameAndId`，直接更新和保存原版白名单，并仅在读回状态与目标一致时返回 `ok=true`。成功响应的 `data` 会包含 `player_uuid`、`whitelist_changed` 与 `whitelist_verified`。

## v0.6.9 管理员可信名单扩展

### `trusted_users`

AstrBot 插件可在 Mod 建立连接后发送当前 Bot 管理员列表：

```json
{
  "type": "query",
  "message_id": "uuid",
  "query": "trusted_users",
  "action": "replace",
  "users": ["123456789", "default:123456789", "discord:987654321"]
}
```

该查询仅在 AstrBot 插件 `sync_command_admins_to_server=true` 且 Mod `syncTrustedCommandUsers=true` 时使用。`replace` 只替换本次 WebSocket 连接同步的内存集合；不得覆盖或保存 Mod 的静态 `trustedCommandUsers`。连接关闭或认证失败后必须清空同步集合。命令执行仍须同时满足 `enableCommandTool=true` 和 `allowedCommandRules`，因此同步管理员不会隐式允许 `op` 或任意命令。

## Mod → AstrBot 事件扩展

通用结构：

```json
{
  "type": "event",
  "event": "player_join",
  "message_id": "uuid",
  "time_ms": 1785196800000,
  "player_uuid": "minecraft-player-uuid",
  "player_name": "Steve"
}
```

支持的 `event`：

| 名称 | 必需字段 | 用途 |
| --- | --- | --- |
| `player_join` | `player_name`、建议 `player_uuid` | 向桥接会话发送进入通知 |
| `player_leave` | `player_name`、建议 `player_uuid` | 向桥接会话发送离开通知 |
| `player_death` | `player_name`、`death_message`；建议 `death_type` | 发送死亡通知；可选 `attacker`、`direct_entity`、`weapon` 用于本地化原因 |
| `binding_code` | `player_name`、`code` | `VERIFY_CODE` 绑定；验证码由 Mod 在登录尝试时生成 |
| `player_login_check` | `message_id`、`player_name` | 登录前检查玩家名是否已经绑定 |

WebSocket 成功 `hello` 和断开会由 AstrBot 自动转成 `server_start` / `server_stop` 通知，Mod 不需要重复上报。

1.21.11 Mod 的结构化死亡事件示例：

```json
{
  "type": "event",
  "event": "player_death",
  "player_uuid": "minecraft-player-uuid",
  "player_name": "Steve",
  "death_message": "Steve was slain by Zombie",
  "reason": "Steve was slain by Zombie",
  "death_type": "mob",
  "attacker": "Zombie",
  "direct_entity": "Zombie",
  "weapon": "Iron Sword"
}
```

`death_type` 使用 Minecraft `DamageSource.getMsgId()` 的稳定消息 ID。AstrBot 优先用它生成中文/英文原因；旧 Mod 只有完整 `death_message` 时，插件仍会移除开头重复的玩家名并兼容常见英文死亡句式。

### 登录检查响应

Mod 在异步登录校验阶段发送：

```json
{
  "type": "event",
  "event": "player_login_check",
  "message_id": "login-attempt-uuid",
  "player_uuid": "minecraft-player-uuid",
  "player_name": "Steve"
}
```

AstrBot 返回：

```json
{
  "type": "event_result",
  "event": "player_login_check",
  "message_id": "login-attempt-uuid",
  "ok": true,
  "allowed": false,
  "message": "[MC] 该游戏账号尚未在聊天平台绑定，请先使用 /mc bind <游戏名>。",
  "message_key": "disconnect.mineastr.login.not_bound",
  "owner_key": ""
}
```

`message_key` 是可选客户端翻译键。0.6.7 Mod 会用 `Component.translatableWithFallback` 断开连接：安装同版客户端 Mod 时跟随玩家客户端语言；未安装时显示 `message` 回退文本。用户在 AstrBot 中自定义登录拒绝模板后，插件只发送自定义 `message`，不会用预设翻译覆盖它。验证码使用 `disconnect.mineastr.login.binding_code` 以同样方式本地化。

实现要求：

- 不要阻塞 Minecraft 主线程等待网络；在平台允许的异步登录事件/阶段发起，并设置短超时。
- AstrBot 连接不可用或超时时的 fail-open / fail-closed 策略必须由 Mod 服务端配置明确决定。建议默认 fail-open，避免 AstrBot 故障锁死服务器，并向控制台输出醒目告警。
- `need_bind_to_login=false` 时 AstrBot 返回 `allowed=true`。
- `VERIFY_CODE` 模式下，未绑定玩家的登录流程应先生成 `binding_code` 事件，再按配置拒绝本次登录。
- 玩家名比较在 AstrBot 侧不区分大小写；Mod 侧应使用服务端解析出的真实玩家名，不能信任客户端自报字符串。

## 错误响应

协议或输入错误：

```json
{
  "type": "error",
  "message": "不支持的服务器事件：unknown"
}
```

查询业务失败仍应使用 `query_result` 并带上 `ok=false`、稳定的 `error` 代码/说明：

```json
{
  "type": "query_result",
  "message_id": "uuid",
  "query": "notify_player",
  "ok": false,
  "error": "player_not_online"
}
```

## 安全边界

- WebSocket Token 必须使用随机长字符串；跨机器部署优先通过 TLS 反向代理或受信内网，不要把明文 WS 直接暴露到公网。
- `command`、`binding`、`notify_player` 都必须由 Mod 再次做开关、请求者、参数白名单和审计检查。
- 不要因为 AstrBot 侧已经判断管理员，就在 Mod 侧允许任意命令。
- 截图继续受客户端同意、大小、格式、冷却和超时限制。
- 所有文本进入 Minecraft 命令、JSON 组件或日志前都要按目标上下文转义；聊天文本不能当作命令执行。
