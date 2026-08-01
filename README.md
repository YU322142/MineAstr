# MineAstr AstrBot 插件

[![AI Assisted](https://img.shields.io/badge/AI-OpenAI%20Codex%20Assisted-10A37F?style=for-the-badge&logo=openai&logoColor=white)](#ai-制作声明)
[![Plugin tests](https://github.com/YU322142/MineAstr/actions/workflows/test.yml/badge.svg?branch=astrbot-plugin)](https://github.com/YU322142/MineAstr/actions/workflows/test.yml)

> [!IMPORTANT]
> **AI 制作声明：本插件采用生成式 AI 参与协议设计、编码、文档编写与测试。** AI 生成或修改的内容由项目维护者审阅、验证并承担最终维护责任。

MineAstr 为 AstrBot 提供一个 `minecraft` 平台适配器。插件启动后会监听 WebSocket，等待 MineAstr Fabric 1.21.11 Mod 主动连接。配套 Mod 固定使用 Fabric API `0.141.4+1.21.11`。

Minecraft 玩家聊天会被转换为 AstrBot 中的同一个群聊会话：

- 平台：`minecraft`
- 群组/会话 ID：`minecraft`
- 发送者：Minecraft 玩家 UUID 和玩家名

AstrBot 对该会话的文本回复会回传给所有已连接的 Minecraft 服务器，并在游戏内广播为：

```text
[AstrBot] 回复内容
```

在模型支持工具调用时，AstrBot 还能主动查询服务器/玩家状态、背包、附近实体和区域建筑特征，按服务端安全策略执行受控命令，并向允许截图的玩家请求低清晰度截图。

从 `v0.6.2` 起，插件在 AstrBot 侧实现了 [AQQBot](https://github.com/alazeprt/AQQBot) 的主要群服互联工作流；`v0.6.3` 又补齐了 AstrBot `aiocqhttp` 的 QQ 退群事件、群名片操作及 AQQBot 宽松玩家名规则：

- 跨平台账号绑定，绑定键使用 `platform_id:user_id`，QQ、Discord 等平台不会发生 ID 冲突；
- Minecraft 与一个或多个 AstrBot 群聊/Discord 频道双向转发；
- 服务器状态、在线玩家、TPS/MSPT/CPU 查询指令；
- 玩家进入、离开、死亡与服务器连接状态通知；
- 聊天平台使用 `@PlayerName` 提醒游戏内玩家；
- 受 Minecraft 端二次鉴权的远程命令；
- AQQBot 本地 `$filter` / `$regex` 替换和 `!CANCEL` 取消规则；
- `GROUP_NAME` 与 `VERIFY_CODE` 两种绑定流程，以及可选的未绑定登录拦截；
- QQ/OneBot 退群自动解绑和可选群名片修改；
- Discord 成员退服自动解绑、绑定后自动改昵称、解绑后恢复原昵称；
- Minecraft Mod 重连后对全部绑定自动对账，清除断线期间遗留的缓存。
- 可选使用 AstrBot 文本模型把 QQ/Discord 消息和 AstrBot 回复翻译成每位玩家的客户端语言，并允许客户端选择是否同时显示原文。

账号绑定保存在 `data/mineastr/bindings.sqlite3`。完整的功能差异和需要 Minecraft Mod 配合的项目见 [AQQBOT_MIGRATION.md](AQQBOT_MIGRATION.md)，扩展协议见 [PROTOCOL.md](PROTOCOL.md)。

## 安装包兼容性

在 AstrBot WebUI 上传发布页提供的 `astrbot_plugin_mineastr-v0.6.14.zip` 即可安装。ZIP 的首条必须是顶层目录 `astrbot_plugin_mineastr/`；AstrBot 4.23.6 的旧版上传解压器依赖这个顺序。v0.6.8 起已修复旧版 AstrBot 无法解析隐藏 `dict` 配置类型而导致重载失败的问题。

插件元数据中的安装/更新源固定为 Fork 分支 `https://github.com/YU322142/MineAstr/tree/astrbot-plugin`，不会再让 AstrBot 回到原项目或下载仅用于项目导航的 `main` 分支。

从源码自行打包时请使用 `python scripts/package_plugin.py`。该脚本会先写目录条目，并统一使用 `/` 路径分隔符；不要直接使用会省略目录条目的 Windows 压缩工具。

## QQ / OneBot v11 支持

QQ 复用 AstrBot 的 `aiocqhttp` 平台适配器，不需要 MineAstr 再登录一个 OneBot 客户端。QQ 群中的 `/mc bind`、验证码绑定、解绑、查询、桥接及管理员指令使用该适配器的唯一 ID 保存账号；`v0.6.3` 还直接监听 OneBot `notice.group_decrease`，并可调用 `set_group_card`。

1. 在 AstrBot 中启用 `aiocqhttp`，确认日志显示 OneBot v11 适配器已连接。
2. 把 `verify_method` 设为 `VERIFY_CODE` 后，玩家登录取得验证码，再在 QQ 群发送 `/mc bind <验证码>`。
3. 建议明确填写 `qq_group_ids`；留空会把该 QQ 实例收到的所有群退群事件都视为自动解绑来源。
4. `qq_auto_unbind_on_leave` 与 `qq_auto_group_card` 默认开启；群名片修改要求机器人是群管理员或群主。
5. 成功绑定提示必须显示真实玩家名；若显示六位数字，说明仍在 `GROUP_NAME` 模式。

## Discord 支持

Discord 不需要本插件自行登录 Discord；它复用 AstrBot 官方 Discord 平台适配器，因此权限、代理、分片和斜杠指令仍由 AstrBot 维护。

1. 在 AstrBot WebUI 新增 Discord 适配器，填写 Bot Token，并开启“自动将插件指令注册为 Discord 斜杠指令”。
2. 在 Discord Developer Portal 的 **Bot → Privileged Gateway Intents** 中启用 `Message Content Intent` 和 `Server Members Intent`。服务器管理员权限不能代替此后台开关。
3. 给机器人授予 `Send Messages`、`Read Message History`、`Embed Links` 和 `Manage Nicknames`。使用 Administrator 也可以，但机器人角色仍必须排在目标成员最高角色上方；Discord 服务器所有者的昵称无法由机器人修改。
4. 在 MineAstr 插件配置的 `bridge_admin_users` 中填入你的 AstrBot Discord 适配器 ID 和 Discord 用户 ID，例如 `my-discord:123456789012345678`。如果该用户已经是 AstrBot 管理员，可省略这一步。
5. 在需要互联的 Discord 频道执行 `/mc bridge_add`。插件会保存该频道准确的 unified message origin，无需手写频道 ID。
6. 执行 `/mc status` 和 `/mc list` 验证查询，再发送一条普通消息验证 Discord → Minecraft 转发。
7. 把 `discord_guild_ids` 填为本服务器 Guild ID（建议生产环境明确填写），执行 `/mc discord_status` 检查成员 Intent、退群监听和服务器可见性。

普通桥接消息会进入 Minecraft，但不会额外触发 LLM。v0.6.9 起默认开启 `relay_bot_conversations_to_game`：QQ/Discord 玩家 `@机器人` 的原消息会进入游戏并继续触发 AstrBot，最终纯文本回复也会进入游戏；工具调用前只有 Reply/At 的空中间消息不会转发。斜杠指令仍由 `relay_commands` 单独控制。

Discord 编辑消息会由 AstrBot 的 Pycord 客户端直接监听。编辑后的正文会按照原桥接会话重新执行过滤、统一翻译并发送到 Minecraft 和其他目标会话，正文前带 `[Edited]` 标记；Minecraft 聊天协议无法修改已经显示的旧消息，因此不会尝试覆盖历史聊天行。机器人自己发送的编辑消息是否同步仍由 `relay_bot_conversations_to_game` 控制。

### MineAstr 指令

| 指令 | 用途 | 权限 |
| --- | --- | --- |
| `/mc status [server_id]` | 查询服务器状态 | 所有人 |
| `/mc list [server_id]` | 查询在线玩家 | 所有人 |
| `/mc performance [server_id]` | 查询 TPS、MSPT、CPU、内存 | 所有人；需新版 Mod |
| `/mc bind <玩家名或验证码>` | 绑定当前聊天账号 | 所有人 |
| `/mc unbind [玩家名]` | 解绑当前聊天账号 | 所有人 |
| `/mc bindings` | 查看自己的绑定 | 所有人 |
| `/mc who <玩家名>` | 查询玩家绑定的平台与显示名 | 所有人 |
| `/mc discord_status` | 检查 Discord 自动解绑、昵称、Intent 与监听状态 | MineAstr 管理员 |
| `/mc command <命令>` | 公开白名单命令立即执行；其他命令创建待审批申请 | 默认关闭；所有人可提交 |
| `/mc approve` | 校验当前用户为管理员后显示待审批编号列表 | MineAstr / AstrBot 管理员 |
| `/mc approve <序号或审批 ID>` | 批准并执行 Mod 保存的白名单外原始命令 | MineAstr / AstrBot 管理员 |
| `/mc reject [序号或审批 ID]` | 查看列表或拒绝待审批命令 | MineAstr / AstrBot 管理员 |
| `/mc approvals` | 查看各已连接服务器的待审批命令 | MineAstr / AstrBot 管理员 |
| `/mc say <消息>` | 主动广播到 Minecraft | MineAstr 管理员 |
| `/mc bridge_add` / `bridge_remove` | 添加/移除当前 QQ 群或 Discord 频道 | MineAstr 管理员 |
| `/mc bridge_list` | 查看桥接会话 | MineAstr 管理员 |
| `/mc admin_bind <用户> <玩家>` | 为他人绑定；Discord 可直接使用用户 mention | MineAstr 管理员 |
| `/mc admin_unbind <用户> <玩家>` | 为他人解绑 | MineAstr 管理员 |

## 最简单配置

如果 AstrBot 和 Minecraft 服务器都在同一台电脑上，只需要改一项：

1. 在 AstrBot WebUI 中启用 `minecraft` 平台适配器。
2. 把 `token` 从 `change-me` 改成一个你自己写的随机字符串，例如 `mineastr-2026-xxxx`。
3. 打开 Minecraft 侧生成的 `config/mineastr-common.json`，把里面的 `token` 改成同一个字符串。
4. 重启 AstrBot 的 `minecraft` 平台适配器和 Minecraft 服务器。

默认连接地址是：

```text
ws://127.0.0.1:8765/ws
Authorization: Bearer <你的 token>
```

## 跨机器部署

如果 AstrBot 和 Minecraft 服务器不在同一台机器上：

1. 在 AstrBot 的“消息平台 → Minecraft 群聊桥接”中把 `host` 设为 `0.0.0.0`。不要填写公网 IP；监听地址必须是本机接口，公网 IP 只用于 Mod 连接。
2. Minecraft Mod 侧 `websocketUrl` 中的 `127.0.0.1` 改成 AstrBot 机器的 IP 或域名。
3. 确认 AstrBot 机器防火墙放行 `port` 对应端口，默认是 `8765`。

示例：

```text
AstrBot 侧：
host = 0.0.0.0
port = 8765
path = /ws

Minecraft Mod 侧：
websocketUrl = "ws://astrbot.example.com:8765/ws"
```

## 安装

1. 将本分支仓库目录复制或软链接到 AstrBot 的插件目录，并确保目录名为 `astrbot_plugin_mineastr`。
2. 如果 AstrBot 没有自动安装依赖，请在 AstrBot 环境中运行：

```bash
pip install -r requirements.txt
```

3. 在 AstrBot WebUI 中启用 `minecraft` 平台适配器。
4. 将 AstrBot 侧的 `token`、监听地址、端口和路径与 Minecraft Mod 的 common 配置保持一致。

## Minecraft 平台适配器配置项

下面这些参数在 AstrBot WebUI 的“消息平台 → Minecraft 群聊桥接”中修改，不在 `astrbot_plugin_mineastr` 插件配置页修改：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | `127.0.0.1` | WebSocket 监听地址。单机保持默认；跨机器或 Docker 填 `0.0.0.0`，不要填公网 IP。 |
| `port` | `8765` | WebSocket 监听端口。被占用时可以换成其他未使用端口，并同步修改 Mod 的 `websocketUrl`。 |
| `path` | `/ws` | WebSocket 路径。一般保持默认；修改后也要同步修改 Mod 的 `websocketUrl`。 |
| `token` | `change-me` | Mod 连接时使用的 Bearer Token。两端必须完全一致，建议改成随机字符串。 |
| `group_id` | `minecraft` | AstrBot 中用于承载所有 Minecraft 聊天的虚拟群组 ID。一般不需要修改。 |
| `group_name` | `Minecraft` | AstrBot 中显示的群组名称，只影响识别和展示。 |
| `bot_id` | `astrbot` | 虚拟 Minecraft 平台中的机器人账号 ID。一般不需要修改。 |
| `bot_display_name` | `AstrBot` | 回复广播到游戏内时显示在方括号里的名称。 |
| `mention_aliases` | `AstrBot,Aria,astrbot` | Minecraft 聊天开头 `@这些名字` 时会被转换为 AstrBot 唤醒消息。多个别名用英文逗号分隔；不在列表中的玩家互相 @ 不会唤醒机器人。 |
| `max_message_length` | `1000` | 转发到 AstrBot 的单条玩家消息最大长度，超出部分会被截断。 |
| `outbound_max_message_length` | `2000` | AstrBot 回复广播回 Minecraft 前允许的最大长度，超出部分会被截断，避免刷屏或客户端显示异常。 |
| `websocket_max_message_bytes` | `2097152` | 插件接收 MineAstr Mod WebSocket 消息的单包大小上限，截图查询结果也会经过这里。 |
| `screenshot_cooldown_seconds` | `10` | 同一目标玩家的截图请求冷却时间，防止模型连续触发截图弹窗。 |
| `screenshot_timeout_seconds` | `30` | 等待 Minecraft 客户端返回截图的最长时间，超时后直接把失败原因返回给模型。 |

### AQQBot、QQ 与 Discord 桥接配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `bridge_enabled` | `true` | 启用跨平台群服互联。 |
| `relay_sessions` | 空 | 每行一个 AstrBot unified message origin；推荐通过 `/mc bridge_add` 维护。 |
| `relay_prefix` | 空 | 非空时，只有以前缀开头的聊天平台消息才进入游戏。 |
| `chat_to_game_template` | `{message}` | 聊天平台到游戏的正文模板；发送者标签由适配器另外携带。 |
| `game_to_chat_template` | `[MC/{server}] {player}: {message}` | Minecraft 到 QQ/Discord 的模板。 |
| `chat_to_game_filters` / `game_to_chat_filters` | 空 | AQQBot 本地过滤规则，每行一条。 |
| `game_translation_enabled` | `false` | 使用 AstrBot 文本模型生成游戏内多语言译文；会增加模型调用、延迟和费用，失败时回退原文。 |
| `game_translation_provider_id` | 空 | 留空使用当前会话文本模型，也可指定低成本翻译 Provider。 |
| `game_translation_languages` | `zh_cn\nen_us` | 每行一个目标 Minecraft locale，最多 8 种，例如 `ja_jp`。 |
| `game_translation_show_original` | `true` | 未安装同版客户端 Mod 时，译文下方是否默认附带原文。 |
| `translation_custom_instructions` | 空 | 统一翻译提示词/术语表；游戏内及所有 QQ/Discord 接收会话共同使用，同一条源消息合计只调用一次模型。 |
| `relay_bot_conversations_to_game` | `true` | 把桥接会话中玩家 @机器人的消息及 AstrBot 最终纯文本回复同步到 MC。 |
| `game_translation_timeout_seconds` | `20` | 翻译超时；超时直接发送原文，不阻塞后续聊天。 |
| `binding_enabled` | `true` | 启用跨平台账号绑定。 |
| `binding_database` | `data/mineastr/bindings.sqlite3` | SQLite 绑定数据库；修改后重载插件。 |
| `verify_method` | `GROUP_NAME` | `VERIFY_CODE` 需要新版 Mod 上报验证码。 |
| `player_name_regex` | `^\S{1,64}$` | 与 AQQBot 兼容，允许 Floodgate 点号前缀和无空白 Unicode 名称。旧版默认规则会自动迁移。 |
| `need_bind_to_login` | `false` | 开启后由新版 Mod 发起登录前绑定检查。 |
| `sync_binding_to_server` | `false` | 把绑定/解绑同步给新版 Mod。 |
| `qq_auto_unbind_on_leave` | `true` | QQ 成员触发 OneBot `group_decrease` 后自动解绑并同步 Minecraft。 |
| `qq_auto_group_card` | `true` | 绑定成功后修改当前 QQ 群名片，需要群管理员权限。 |
| `qq_group_ids` | 空 | 每行一个允许自动操作的 QQ 群号；生产环境建议明确填写。 |
| `qq_group_card_template` | `{players}` | 按绑定先后并列显示完整游戏名，超出 QQ 长度时只保留最早能放下的几项。 |
| `discord_auto_unbind_on_leave` | `true` | Discord 成员离开目标服务器时自动删除该平台账号的全部绑定。 |
| `discord_auto_nickname` | `true` | 绑定后按模板修改 Discord 服务器昵称。 |
| `discord_restore_nickname_on_unbind` | `true` | 最后一个绑定解除后恢复首次改名前保存的原昵称。 |
| `discord_guild_ids` | 空 | 每行一个允许自动操作的 Guild ID；空表示当前 Discord 实例加入的全部服务器。 |
| `discord_nickname_template` | `{players}` | 按绑定先后并列显示完整游戏名，超出 32 字符时只保留最早能放下的几项。 |
| `remote_command_enabled` | `false` | 开启命令申请入口；Mod 公开白名单内立即执行，其他命令必须二次审批。 |
| `bridge_admin_users` | 空 | 每行一个 `platform_id:user_id`；也接受单独 user ID。 |
| `sync_command_admins_to_server` | `true` | 在连接及每次审批前把 MineAstr 管理员与 AstrBot 全局 `admins_id` 实时同步到 Mod 内存管理员名单；Mod 侧也需开启对应开关。 |
| `notifications_enabled` | `true` | 启用服务器与玩家事件通知。 |
| `notification_language` | `zh_CN` | 预设通知语言，可选 `zh_CN` / `en_US`；自定义模板保持原文。 |
| `notify_*_enabled` | `true` | 服务器连接/断开、玩家进入/离开/死亡各自的独立通知开关。 |
| `qq_notification_settings` | QQ 独立设置 | QQ/OneBot 的平台 ID、聊天译文目标语言、是否显示原文、逐行通知语言、事件开关和每种语言的通知样式。 |
| `discord_notification_settings` | Discord 独立设置 | Discord 的平台 ID、聊天译文目标语言、是否显示原文、逐行通知语言、事件开关和每种语言的通知样式。 |
| `discord_channel_settings` | 空列表 | 可添加任意数量的 Discord 频道配置；频道只选择需要接收的译文语言、原文显示和通知样式，翻译提示词使用统一设置。 |

配置页按群服互联、绑定、QQ、Discord、管理员/远程指令和通知分成六个可折叠区域；账号、群号、会话和过滤规则均使用多行输入框。升级时旧版平铺配置会自动迁移一次，不会重置现有设置。v0.6.9 起，QQ 与 Discord 的“通知语言”都是逐行列表：只写一行就是单语，写 `zh_CN` 和 `en_US` 两行就会按该顺序同时发送；“分语言自定义样式”可为每种语言单独填写多行模板，留空则使用内置预设。旧版通用模板非空时为兼容旧配置，只发送该模板一次。事件模板支持 `{server}`、`{server_id}`、`{player}`、`{binding}` 等占位符，死亡事件另支持 `{reason}`、`{death_type}`、`{attacker}`、`{direct_entity}` 和 `{weapon}`。平台适配器的 `host`、`port`、`path`、`token` 仍应在 AstrBot 的 `minecraft` 平台配置页修改。

未绑定登录提示由“未绑定登录拒绝消息开关”控制。登录校验发生在 Minecraft 玩家尚未绑定任何聊天平台时，因此这条提示无法判断应使用 QQ 还是 Discord 的平台配置：插件会附带客户端翻译键，安装同版 MineAstr 客户端 Mod 时由客户端按自身语言显示；未安装客户端 Mod 时回退到 AstrBot 的全局语言和模板。

“启用游戏内消息自动翻译”处理的是聊天正文：QQ、Discord 和 AstrBot 回复进入游戏前会检测原文语言，并只生成与原文不同的目标语言译文；Mod 再按每位在线玩家的客户端 locale 分别选择。目标语言与原文一致时直接显示原文，不会重复显示同文译文。玩家安装 v0.6.7 客户端 Mod 后可在 F8 设置中关闭译文或关闭原文；没有匹配译文、模型失败或超时时始终显示原文。翻译提示把聊天正文当作不可信数据，不执行其中的指令，但仍建议为此功能使用独立、低成本的 Provider。

“统一翻译提示词/术语表”会作为服主可信规则加入系统提示词，例如每行写 `Motiquies 固定译为 动静交映`。对于同一条源消息，插件会先收集游戏客户端与所有 QQ/Discord 接收会话需要的目标语言，只调用一次模型翻译纯正文，再分别套用游戏模板、平台发送者标签、语言顺序和原文开关。QQ、Discord 和 Discord 频道仍可分别开关聊天翻译、选择一个或多个目标 locale，但不再各自调用模型或维护不同术语表。升级时旧的分平台/频道提示词会自动合并到统一设置。翻译失败时本批次全部回退原文。

## 机器人可调用工具

插件会注册九个 AstrBot LLM 工具。只要当前模型提供商支持 function calling / tools，并且 AstrBot 中没有禁用这些工具，机器人在对话中可以主动查询 Minecraft 数据后再回答。

| 工具 | 用途 |
| --- | --- |
| `mineastr_get_server_status` | 查询 Minecraft 服务器连接状态、服务器名称、MC 版本、Mod 版本、在线人数和运行时长。 |
| `mineastr_get_online_players` | 查询当前在线玩家数量和玩家列表。 |
| `mineastr_get_player_state` | 查询指定玩家的生命、饥饿、位置、维度、游戏模式、经验和状态效果。 |
| `mineastr_get_player_inventory` | 查询快捷栏、背包、护甲、副手和可选末影箱的安全摘要。 |
| `mineastr_get_nearby_entities` | 查询玩家附近实体的种类、数量、距离和生命摘要。 |
| `mineastr_analyze_region` | 分析已加载区域的方块材料、建筑部件、表面高度和粗略三维形状。 |
| `mineastr_run_server_command` | 提交真实请求者明确要求的精确命令；公开白名单内执行，其他命令仅返回待审批 ID。 |
| `mineastr_manage_command_approvals` | 为当前真实管理员列出、批准或拒绝待审批命令；每次调用都重新检查管理员身份。 |
| `mineastr_request_screenshot` | 请求指定玩家客户端发送低清晰度截图，并把截图保存到 AstrBot 工作目录。 |

使用示例：

- 玩家在 Minecraft 中问：“现在服务器有几个人？”
- AstrBot 收到这条群聊消息。
- 模型判断需要实时数据，调用 `mineastr_get_online_players`。
- 工具向 MineAstr Mod 发起查询，Mod 返回在线玩家列表。
- 模型根据工具结果回复玩家。

其他示例：

- “我背包里还有多少火把？”会调用 `mineastr_get_player_inventory`。
- “附近有什么怪？”会调用 `mineastr_get_nearby_entities`。
- “分析一下这栋房子的材料和结构”会调用 `mineastr_analyze_region`；区域工具只扫描已加载区块，不读取箱子内容、告示牌文字或方块实体 NBT。
- “帮我执行 `/time query daytime`”可以调用 `mineastr_run_server_command`；若命中 Mod 的 `allowedCommandRules` 会立即执行，否则只创建申请。管理员可发送 `/mc approve` 查看列表，再发送 `/mc approve <序号>`，也可明确要求机器人调用 `mineastr_manage_command_approvals` 审批。

截图示例：

- 玩家在 Minecraft 中问：“能看看我现在画面吗？”
- 或者玩家对机器人说：“我的建筑建好啦”“帮我看看这个建筑”“我这里好像不对”“这边怎么样”。
- AstrBot 默认把截图目标设为当前发言玩家。
- 如果该玩家安装了 MineAstr 客户端 Mod，客户端会按 `config/mineastr-client.json` 中的 `screenshotMode` 处理，也可在游戏中按 `F8` 打开设置。
- 默认 `ASK` 模式下，玩家点击“发送截图”后，工具会把图片保存到 `data/mineastr/screenshots/`，并把文件路径、尺寸、玩家名和时间返回给模型。
- 如果当前 AstrBot 工具链支持 MCP 图片结果，插件还会把截图作为图片内容返回给支持视觉理解的模型；不支持时仍返回文本摘要和文件路径。
- 插件会对同一目标玩家的截图请求做 10 秒冷却；冷却期内再次调用会直接返回“截图请求过于频繁”。
- 截图请求全程使用异步 `await` 等待 Minecraft 返回结果，默认最多等待 30 秒；超时会返回“请求截图超时，客户端未响应”。

注意事项：

- 需要使用支持工具调用的模型或提供商，否则模型只能按普通聊天回答，无法主动查询实时数据。
- 如果 AstrBot WebUI 中有工具开关，请确认需要使用的 `mineastr_*` 工具处于启用状态。
- 如果 AstrBot WebUI 中有工具开关，请确认 `mineastr_request_screenshot` 也处于启用状态。
- 配套成品要求 Minecraft 1.21.11、Fabric API `0.141.4+1.21.11` 和 Java 21；更新插件时也应替换配套 Mod JAR。
- 接入多个 Minecraft 服务器时，工具可以传入 `server_id` 查询指定服务器；只有一个服务器时无需填写。
- 截图功能需要目标玩家安装客户端 Mod；只安装服务端 Mod 时基础聊天和查询可用，但截图不可用。
- 命令工具的最终权限完全由 Minecraft Mod 的 `mineastr-common.json` 决定。默认 `enableCommandTool = false`。`allowedCommandRules` 是任何人可用的公开命令列表，不要把管理命令或单独的 `"*"` 放进去；白名单外命令由 `/mc approve` 审批。
- AQQBot 兼容层的账号数据库在 AstrBot 侧；如果同时运行原 AQQBot，两套绑定数据不会自动合并，也不应同时负责登录白名单。
- Fabric 0.6.11 Mod 支持 `performance`、玩家通知、验证码、登录拦截、按真实身份对账的白名单同步、管理员实时同步，以及公开命令白名单与白名单外指令二次审批；更老的 Mod 不支持完整审批扩展。
- 本插件不会获取 `$url` 远程过滤词库，避免让聊天消息触发服务端任意 URL 请求；请把词库转换为本地 `$filter` / `$regex` 规则。
- Discord 自动化直接挂接 AstrBot 官方 Pycord 客户端。管理员权限仍受 Discord 角色层级限制；多服务器部署应填写 `discord_guild_ids`，否则离开任一可见服务器都会触发该 Discord 平台账号的全局解绑。

## AI 制作声明

MineAstr AstrBot 插件在开发过程中使用了 OpenAI Codex 等生成式 AI 能力，涉及 Python 代码、LLM tools、WebSocket 协议、安全检查、配置说明与测试流程。

AI 输出不代表天然正确或安全。提交到仓库的内容仍需由维护者人工审阅，并经过语法、协议兼容性和权限边界测试。

英文声明：*This plugin was created with assistance from generative AI, including OpenAI Codex. AI-assisted changes remain subject to human review, testing, and maintainer responsibility.*

## 故障排查

- Mod 日志提示 `401` 或连接后立即断开：检查两端 `token` 是否完全一致；AstrBot 侧留空或保持 `change-me` 会安全拒绝全部连接。
- Mod 一直显示 `未连接`：确认 AstrBot 插件已加载，`minecraft` 平台适配器已启用，端口没有被防火墙或其他程序占用。
- AstrBot 收到消息但没有回复：这是 AstrBot 群聊规则、唤醒词或权限设置决定的，需要检查 AstrBot 的回复策略。Minecraft 里如果你是用 `@Aria` 之类的方式叫它，请确认 `mention_aliases` 包含 `Aria`。
- Discord 普通消息没有进入 Minecraft：先在该频道执行 `/mc bridge_add`，再用 `/mc bridge_list` 检查保存的会话；同时确认发送者具有 MineAstr 管理权限、`bridge_enabled=true` 且 Minecraft WebSocket 已连接。
- Discord 中 `/mc` 没有斜杠提示：在 AstrBot Discord 适配器中开启自动注册插件指令，重启/重载适配器；即使未注册为原生斜杠指令，按 AstrBot 唤醒规则发送文本 `/mc help` 仍可测试。
- `/mc bind` 立即提示冷却：默认绑定冷却为 60 秒、解绑冷却为 86400 秒，与 AQQBot 默认值一致；测试阶段可临时调低相应配置。
- `VERIFY_CODE` 一直提示验证码不存在：Minecraft 端必须实现 `binding_code` 事件；只更新 AstrBot 插件不会凭空产生验证码。
- 验证码被识别但提示玩家名不符合规则：升级插件和 Fabric Mod 到 `0.6.7`。验证码模式会直接采用 Minecraft 服务端认证的登录名，不再使用只适合手填名称的正则；旧版会误拒带空格或平台字符的名称。
- 游戏名后出现 `(/[IPv6]:端口)`：这是 0.6.5 Mod 误用了 Fabric 的登录日志显示名。0.6.6 Mod 会读取纯 `requestedUsername`，插件也会自动迁移已经错误保存的绑定并重新对账，无需手工修改 SQLite。
- 死亡通知显示成“玩家名 因 玩家名 died”：同时升级插件和 Fabric Mod 到 `0.6.7`。新版 Mod 会上报结构化伤害类型，插件会移除重复玩家名并生成中文/英文死亡原因。
- 绑定成功但原版白名单仍拒绝：升级插件和 Fabric Mod 到 `0.6.7`，并检查 Mod 日志是否出现 `whitelist_verified=true`。新版会按在线/离线认证模式解析 UUID、写入后核验；失败时绑定会按 `binding_sync_required` 设置回滚或明确提示，不再假报成功。
- QQ 退群没有解绑：确认平台类型是 `aiocqhttp`、`qq_auto_unbind_on_leave=true`，群号在 `qq_group_ids` 中，并在启动日志查找“已为 QQ/OneBot 平台注册退群自动解绑监听”。
- QQ 绑定后群名片未改变：开启 `qq_auto_group_card`，并确保 OneBot 机器人是该群管理员或群主。
- `need_bind_to_login` 开启后仍能登录：配套 Fabric Mod 的 `loginBindingCheckEnabled` 也必须开启，并确认 Mod 已连接 AstrBot；`loginCheckFailOpen=true` 时断线/超时会放行。
- 机器人不会主动查询服务器数据：确认当前模型支持工具调用，并确认 MineAstr 的 LLM 工具没有被禁用。
- 命令工具返回禁用：检查 Mod 侧 `enableCommandTool`。公开查询应加入 `allowedCommandRules`；`op` 等高权限命令不应加入公开规则。Bot 管理员先使用 `/mc approve` 查看列表，再按序号批准。若日志出现 `invalid_trusted_user`，请升级至 v0.6.13；插件会过滤 Mod 不接受的管理员身份格式，并在审批前同步当前真实管理员身份。仍提示不可信时，确认插件 `sync_command_admins_to_server` 与 Mod `syncTrustedCommandUsers` 均开启，或把该管理员写入静态 `trustedCommandUsers`。
- `/mc discord_status` 显示退群监听未注册：确认 AstrBot Discord 适配器已经在线并重载 MineAstr；若适配器因 4014 断开，请在 Developer Portal 开启 `Server Members Intent`。
- 绑定成功但昵称未改变：确认机器人拥有 `Manage Nicknames`，且机器人角色高于目标成员；机器人不能修改服务器所有者或同级/更高角色成员。
- 截图工具返回未安装客户端 Mod：目标玩家需要在自己的 Fabric 1.21.11 客户端 `mods` 目录安装 MineAstr 和 Fabric API 0.141.4。
- 截图工具返回拒绝或禁用：目标玩家需要在客户端弹窗中同意，或把 `mineastr-client.json` 的 `screenshotMode` 改为 `"ASK"` / `"AUTO"`。

插件级 `_conf_schema.json` 仅用于展示和发现配置。实际生效的 WebSocket 参数以 AstrBot WebUI 中 `minecraft` 平台适配器的配置为准。
