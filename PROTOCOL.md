# MineAstr WebSocket 协议

v0.6.30 扩展可选的聊天媒体字段：`chat` 消息可带 `media` 数组。旧版 Mod 会忽略该字段；新版 Mod 只向已声明 ChatImage 能力且允许接收的客户端发送图片，不会把本地路径回显到聊天。

本文描述 AstrBot 插件 `v0.6.30` 接受的协议。协议号仍为 `1`：新增消息均为可选扩展，旧版 Mod 的 `hello`、`chat`、`ping`、`query` 和 `query_result` 不受影响。外置翻译术语库仅在 AstrBot 端读取，不增加协议字段。

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
  "mod_version": "0.6.30",
  "chat_capabilities": ["native_chat_translation"]
}
```

服务端只信任该连接在 `hello` 中登记的 `server_id` / `server_name`。后续 `chat` 或 `event` 中伪造的同名字段会被覆盖。未发送 `hello` 就提交聊天、事件或查询结果会被拒绝。

`server_id` 在同一个 AstrBot 实例中应保持唯一、稳定，长度不要超过 64 字符。插件会为每条连接分配内部 `connection_id`，原生聊天翻译回包按连接路由，即使错误地配置了重复 `server_id` 也不会把译文发到另一台服务器。

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

### Minecraft 原生聊天 → 按玩家语言翻译回同一服务器

当 `hello.chat_capabilities` 含 `native_chat_translation` 且插件同时开启
`bridge_enabled`、`game_translation_enabled` 时，AstrBot 会发送：

```json
{
  "type": "native_chat_policy",
  "enabled": true,
  "timeout_ms": 25000
}
```

新 Mod 只有收到 `enabled=true` 后才会在原版聊天广播前排队；旧 Mod 会忽略这个可选消息并继续原版聊天。被接管的消息仍使用 `type=chat`，并增加：

```json
{
  "native_chat": true,
  "native_chat_id": "uuid",
  "target_languages": ["zh_cn", "en_us"],
  "native_original_content": "完整的已装饰正文"
}
```

插件只对 `target_languages` 与 `game_translation_languages` 的交集调用 AstrBot 文本模型，
并将结果定向回发到产生请求的连接：

```json
{
  "type": "native_chat_translate_result",
  "message_id": "uuid",
  "translations": {"zh_cn": "大家好", "en_us": "Hello"},
  "show_original": true
}
```

译文与原文规范化后相同时不发送重复译文。翻译失败、策略关闭、连接断开或超时都会按发送顺序只显示一次原文；结果按发送序号释放，避免 AI 回包乱序。Mod 在提交时快照接收者，晚加入的玩家不会看到旧消息；F8 关闭翻译的玩家仍可看到原文，但不会贡献目标语言。

为实现逐玩家不同正文，Mod 使用未签名的聊天消息重新发送，并尽量保留 ChatType、发送者 UUID、聊天可见性和提交时的接收者快照；这条路径不保留 Mojang Secure Chat 的签名/举报关联，也不能替代原版服务器文本过滤、反刷屏计数和其他在最终广播阶段运行的聊天 Mod。Mod 自带有界的原生聊天速率与排队保护；需要严格审核/举报语义时应关闭 `game_translation_enabled`，让消息完全走原版流程。

### AstrBot → Mod 聊天

```json
{
  "type": "chat",
  "message_id": "uuid",
  "sender_name": "Alice",
  "content": "大家好",
  "translations": {
    "en_us": "Hello everyone",
    "ja_jp": "みなさん、こんにちは"
  },
  "show_original": true
}
```

`translations` 与 `show_original` 均为 v0.6.7 可选扩展。v0.6.11 AstrBot 翻译器先检测原文语言，不会为与源语言相同的目标 locale 写入重复译文；对应玩家自然回退显示 `content`。Mod 应按每位在线玩家的 `clientInformation().language()` 选择精确 locale，找不到时可回退到同语言族；仍找不到、译文无效或玩家关闭翻译时显示 `content`。安装同版客户端 Mod 的玩家可通过单独的 C2S 偏好包覆盖 `show_original` 并关闭译文；不要修改旧版客户端能力包的 codec，以免协议不匹配导致断线。

AstrBot 插件只发送纯文本，不把译文解析为命令或 JSON 组件。目标语言数量、文本长度和模型等待时间都必须受限；翻译模型失败时不得丢弃原文。

#### 可选图片媒体

`chat.media` 是协议号 1 下的可选扩展。每项 `type` 必须为 `image`，并可使用以下两种安全形式：

```json
{
  "type": "image",
  "url": "https://example.invalid/image.png",
  "name": "image.png"
}
```

公共 `http(s)` 地址只允许带主机名的 URL。机器人本地文件或 base64 图片必须先由插件校验真实图片签名、大小和 `sha256`，再使用：

```json
{
  "type": "image",
  "data_base64": "iVBORw0KGgo...",
  "mime_type": "image/png",
  "size": 12345,
  "sha256": "<64 lowercase hex>",
  "name": "image.png"
}
```

服务端不会转发本地路径；客户端只有在安装 ChatImage 并在 F8 开启 Bot 图片接收时才会显示图片。图片分片完成后由客户端写入 MineAstr 缓存，并以 ChatImage CICode 渲染；缺少 ChatImage、校验失败或玩家关闭接收时只保留文字，不显示 URL 或文件路径。

### Minecraft 告示牌翻译

服务端 Mod 可在玩家交互告示牌时请求 AstrBot 使用同一套游戏内消息翻译配置：

```json
{
  "type": "sign_translate_request",
  "message_id": "uuid",
  "sign_id": "minecraft:overworld/1,64,2/front",
  "source_fingerprint": "sha256-or-stable-fingerprint",
  "text": "Welcome\nTo the server"
}
```

AstrBot 返回按 locale 聚合的纯文本译文；`show_original`、目标语言、模型、超时和统一术语提示词均沿用游戏内消息设置：

```json
{
  "type": "sign_translate_result",
  "message_id": "uuid",
  "ok": true,
  "sign_id": "minecraft:overworld/1,64,2/front",
  "source_fingerprint": "sha256-or-stable-fingerprint",
  "source_language": "en_us",
  "translations": {
    "zh_cn": "欢迎\n来到服务器"
  },
  "show_original": true
}
```

Mod 必须把 `source_fingerprint` 与译文一起持久化到 Minecraft 世界存档。告示牌原文改变后，旧缓存不得复用；翻译失败或翻译前后规范化文本一致时显示原文。
如果告示牌同时包含中文和英文，AstrBot 会让模型判断两部分是否表达相近含义。命中时不再生成普通译文；插件缓存该判断，并显式返回 `"already_bilingual": true` 和空的 `"translations": {}`。Mod 应把它持久化为 `skipTranslation`，准星再次指向时不请求模型，也不显示翻译浮层。原生 Minecraft 玩家聊天也复用同一判断与文本级缓存，命中时只广播原文；图片翻译不受此规则影响。

```json
{
  "type": "sign_translate_result",
  "message_id": "uuid",
  "ok": true,
  "sign_id": "minecraft:overworld/1,64,2/front",
  "source_fingerprint": "sha256-or-stable-fingerprint",
  "source_language": "multilingual",
  "translations": {},
  "already_bilingual": true,
  "show_original": false
}
```

安装了同版 MineAstr 客户端 Mod 时，客户端只在准星指向某一面告示牌时发送可选的
`mineastr:sign_translation_query` C2S 包（位置、正反面和原文指纹）。服务端先查世界缓存，
再按上面的 WebSocket 流程请求 AstrBot，最后通过 `mineastr:sign_translation_result` S2C
包返回 locale 译文。客户端保持原始告示牌文字不变，仅在告示牌旁绘制世界空间浮选译文；
准星移开后立即隐藏，不会向聊天栏发送进入服务器时的批量翻译消息。未安装客户端 Mod
的玩家仍使用旧的系统消息回退。

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

`action` 支持 `bind` / `unbind` / `reset`。`reset` 不带玩家身份，用于 Mod 每次重连后先清空绑定缓存，再由 AstrBot 逐条发送当前 SQLite 中的全部绑定；启用白名单同步时也会移除旧缓存对应的白名单条目。AstrBot SQLite 数据库仍是聊天平台绑定的事实来源。Mod 应按服务器认证模式解析 `NameAndId`，直接更新和保存原版白名单，并仅在读回状态与目标一致时返回 `ok=true`。成功响应的 `data` 会包含 `player_uuid`、`identity_source`、`whitelist_changed` 与 `whitelist_verified`。`identity_source` 可为 `offline_mode`、`authenticated_profile`、`observed_login` 或 `synced_binding`；0.6.10 Mod 还会在原版白名单检查前使用本次连接的真实 `NameAndId` 修正代理/Floodgate 身份。

## v0.6.11 命令申请与审批扩展

首次提交使用 `action=request`，并携带真实请求者身份：

```json
{
  "type": "query",
  "message_id": "uuid",
  "query": "command",
  "action": "request",
  "command": "op Steve",
  "requester_id": "123456789",
  "requester_name": "Alice",
  "requester_platform": "default"
}
```

命中 `allowedCommandRules` 时 Mod 立即执行并返回 `data.status=executed`；该列表是所有请求者可用的公开命令白名单。未命中时不得执行，而是把规范化后的精确命令、原请求者、创建时间和过期时间保存在 Mod 内存中，返回 `data.status=approval_required`、随机 `approval_id` 与有效期。

管理员使用单独请求批准或拒绝：

```json
{
  "type": "query",
  "message_id": "uuid",
  "query": "command",
  "action": "approve",
  "approval_id": "approval-uuid",
  "requester_id": "987654321",
  "requester_platform": "discord"
}
```

`approve` / `reject` / `list` 的当前请求者必须命中 Mod 静态 `trustedCommandUsers` 或 AstrBot 同步管理员。批准请求不接受新的 `command`：Mod 必须原子取出并执行申请阶段保存的命令，确保 AI 或审批消息无法篡改。`list` 返回 `status=pending_list` 和 `approvals` 数组。待审批项有数量和时效上限，在处理、过期或 WebSocket 断线后删除。

## v0.6.9 管理员可信名单扩展

### `trusted_users`

AstrBot 插件可在 Mod 建立连接后发送当前 Bot 管理员列表：

```json
{
  "type": "query",
  "message_id": "uuid",
  "query": "trusted_users",
  "action": "replace",
  "revision": 42,
  "users": ["123456789", "default:123456789", "discord:987654321"]
}
```

该查询仅在 AstrBot 插件 `sync_command_admins_to_server=true` 且 Mod `syncTrustedCommandUsers=true` 时使用。`replace` 只替换本次 WebSocket 连接同步的内存集合；不得覆盖或保存 Mod 的静态 `trustedCommandUsers`。`revision` 单调递增，Mod 必须拒绝小于已应用 revision 的旧同步。连接关闭或认证失败后必须清空同步集合。同步管理员只获得白名单外命令的审批资格；公开白名单仍由 `allowedCommandRules` 独立决定。

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

`message_key` 是可选客户端翻译键。0.6.7 Mod 会用 `Component.translatableWithFallback` 断开连接：安装同版客户端 Mod 时跟随玩家客户端语言；未安装时显示 `message` 回退文本。用户在 AstrBot 中自定义登录拒绝模板后，插件只发送自定义 `message`，不会用预设翻译覆盖它；自定义消息中的 `{code}` 会由 Mod 替换为本次验证码，并不会再重复追加默认验证码提示。验证码使用 `disconnect.mineastr.login.binding_code` 以同样方式本地化。

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
- `command`、`binding`、`notify_player` 都必须由 Mod 再次做开关、身份、参数和审计检查；白名单外命令必须经过服务端保存原文的二阶段审批。
- 不要因为 AstrBot 侧已经判断管理员，就在 Mod 侧允许任意命令。
- 截图继续受客户端同意、大小、格式、冷却和超时限制。
- 所有文本进入 Minecraft 命令、JSON 组件或日志前都要按目标上下文转义；聊天文本不能当作命令执行。
