# MineAstr NeoForge Mod

[![AI Assisted](https://img.shields.io/badge/AI-OpenAI%20Codex%20Assisted-10A37F?style=for-the-badge&logo=openai&logoColor=white)](#ai-制作声明)

> [!IMPORTANT]
> **AI 制作声明：本项目采用生成式 AI 参与设计、编码、UI 改进、文档编写与测试。** AI 生成或修改的内容由项目维护者审阅、验证并承担最终维护责任。

![MineAstr 封面](cover.png)

MineAstr `0.6.28` 是一个 Minecraft 1.21.1 / NeoForge 21.1.219 双端 Mod，用于把 Minecraft 聊天与事件桥接到 AstrBot，并按每位玩家的客户端语言显示 AI 译文。启用原生聊天翻译时会使用未签名消息重发，若需要完整 Secure Chat/原版反刷屏语义，请关闭该选项。

从 AstrBot 侧启用 MineAstr LLM 工具后，机器人还可以主动查询服务器状态、玩家状态、背包、附近实体和区域建筑特征，在严格鉴权后执行受控服务器命令，并在玩家客户端允许时请求低清晰度截图。

## 功能简介

- 把 Minecraft 里的普通聊天识别为 AstrBot 的同一个群聊。
- 可选拦截 Minecraft 玩家原生聊天，由 AstrBot 一次翻译后按每位在线玩家的客户端 locale 分别显示；超时、断线或无匹配译文时按原顺序回退原文。
- 原生聊天翻译会改用未签名消息重发，不保留 Secure Chat 举报关联，也不经过原版服务器文本过滤和完整反刷屏链路；需要这些语义时关闭 AstrBot 的 `game_translation_enabled`。
- AstrBot 触发回复后按玩家 locale 广播译文；译文与原文相同时只显示原文。
- 转发玩家加入、离开和结构化死亡事件，并接收聊天图片链接和定向玩家提醒。
- AstrBot 可以通过工具主动查询服务器状态、在线玩家、生命/位置、背包、附近实体和区域建筑特征。
- 支持账号绑定同步、可选原版白名单同步、登录前绑定检查和一次性验证码。
- 服务器命令工具默认关闭；公开规则可直接执行，其他命令必须由静态或 AstrBot 同步管理员审批。
- 只翻译准星指向的普通、墙上或悬挂告示牌；译文显示在独立 HUD 层，不依赖方块实体渲染器。
- 告示牌译文按维度、坐标、正反面和原文指纹保存在世界存档，支持双语同义跳过和人工纠正。
- 提供外部图片翻译与统一显示 API，供沉浸画框调用 AstrBot 多模态模型。
- 截图是可选客户端能力，默认会先询问玩家；玩家也可以改成自动发送或永不发送。
- 按 `F8` 可调整游戏翻译、原文显示、告示牌/画框浮选、距离、缩放和截图设置。
- 单人世界的集成服务器桥接默认关闭，可在“本地服务端”页面通过 Switch 开关启用并配置地址、Token 和服务器标识。
- 服务端单独安装时，未安装客户端 Mod 的玩家仍可加入服务器，聊天和查询功能照常可用。

## 界面与运行环境确认

- **有配置界面**：安装在客户端后，可按 `F8` 或从 NeoForge Mod 列表打开 MineAstr 设置。
- **服务端无 GUI 可运行**：独立 NeoForge 服务端只读取 `config/mineastr-common.toml`，不需要也不会打开图形界面；Gradle 的 `runServer` 任务也已按 `--nogui` 配置。
- **服务端不强制客户端安装**：MineAstr 的客户端网络能力是可选的。没有安装客户端 Mod 的玩家可以进入服务器，但 AstrBot 对这些玩家请求截图时会返回“不支持截图”。
- **单人模式可选启用**：客户端安装 Mod 后，可在 MineAstr 配置页进入“本地服务端”，打开默认关闭的 Switch。开启后，单人世界的集成服务器才会连接 AstrBot；独立服务器不受此选项影响。

NeoForge 的 Mod 列表只读取 `logoFile` 作为图标，并没有单独的“封面图”元数据字段。本仓库提供 `cover.png` 作为发布页/README 封面，同时也把同一张图打包到资源目录 `assets/mineastr/textures/gui/cover.png`。

## 构建

请安装 JDK 21，然后运行：

```powershell
.\gradlew.bat build
```

构建产物位于 `build/libs/`。

## 安装与运行

独立服务器使用时，把构建出的 jar 放到 NeoForge 服务端的 `mods` 目录即可提供聊天桥接、状态查询和在线玩家查询。客户端没有安装 MineAstr 也可以加入服务器。

如果希望使用截图工具，目标玩家的客户端也需要安装同一个 MineAstr jar。截图是附加功能，不影响未安装客户端 Mod 的玩家进入服务器。

单人本地世界也可以使用：把 jar 放到 NeoForge 1.21.1 客户端的 `mods` 目录，在 Mod 列表打开 MineAstr 配置页，进入“本地服务端”并开启 Switch。该功能默认为关闭状态。

## 配置文件位置

首次启动游戏或服务器后，NeoForge 会生成 common 配置文件：

- 独立服务端：`服务端目录/config/mineastr-common.toml`
- 单人本地世界：`.minecraft/config/mineastr-common.toml`

客户端安装 MineAstr 后还会生成翻译、浮选和截图配置：

- 客户端：`.minecraft/config/mineastr-client.toml`

也可以在游戏主菜单的 Mod 列表中打开 MineAstr 的自定义配置界面修改截图选项。如果没有看到配置文件，请先确认服务端或客户端至少启动过一次，并且 MineAstr jar 已经放在正确的 `mods` 目录。

## 最简单配置

如果 AstrBot 和 Minecraft 在同一台电脑上，只需要改 `token`：

1. 在 AstrBot WebUI 的 `minecraft` 平台适配器里，把 `token` 改成一个随机字符串。
2. 打开 `mineastr-common.toml`。
3. 找到 `token = "change-me"`，把引号里的内容改成同一个随机字符串。
4. 保存文件，然后重启 Minecraft 服务器或客户端。

示例：

```toml
token = "mineastr-2026-xxxx"
```

## 完整配置示例

下面是 `mineastr-common.toml` 的完整示例。TOML 配置中，`#` 开头的是说明文字；真正生效的是 `=` 后面的值。字符串必须保留英文双引号。

仓库中也提供了同样内容的示例文件：[examples/mineastr-common.toml](examples/mineastr-common.toml)。

```toml
# 是否启用 MineAstr 并连接到 AstrBot。
# 不想转发聊天时改成 false。
enabled = true

# AstrBot minecraft 平台适配器的 WebSocket 地址。
# 本机运行 AstrBot 时通常保持 ws://127.0.0.1:8765/ws。
# AstrBot 在另一台机器时，把 127.0.0.1 改成那台机器的 IP 或域名。
websocketUrl = "ws://127.0.0.1:8765/ws"

# AstrBot 插件校验的 Bearer Token。
# 这里的值必须与 AstrBot minecraft 平台适配器中的 token 完全一致。
# 建议把 change-me 改成较长随机字符串，并同时填到 AstrBot 侧。
token = "change-me"

# 发送给 AstrBot 的稳定服务器 ID。
# 只有接入多个 Minecraft 服务器时才需要改；单服通常保持 minecraft。
serverId = "minecraft"

# 发送给 AstrBot 的服务器显示名称。
# 用于日志和识别，可以写成你的服务器名称。
serverName = "Minecraft 服务器"

# AstrBot 消息广播到 Minecraft 时显示的名称。
# 游戏内会显示为 [名称] 回复内容。
botDisplayName = "AstrBot"

# WebSocket 断开后的重连间隔，单位为秒。
# 网络不稳定时可以适当调大。
reconnectSeconds = 5

# 转发到 AstrBot 的单条玩家聊天最大长度。
# 超过这个长度的消息会被截断。
maxMessageLength = 1000

# 玩家实时状态、背包、附近实体和区域建筑特征工具。
enablePlayerStateTool = true
enableInventoryTool = true
enableNearbyEntitiesTool = true
enableRegionTool = true
regionMaxBlocks = 32768

# 高风险命令工具默认关闭。
enableCommandTool = false
syncTrustedCommandUsers = true
trustedCommandUsers = []
allowedCommandRules = ["list", "seed", "time query day", "time query daytime", "time query gametime"]
commandPermissionLevel = 4
commandMaxLength = 256
commandApprovalTimeoutSeconds = 300
commandMaxPendingApprovals = 128

# 定向玩家提醒。
enablePlayerNotifications = true
notifyActionBar = true
notifyTitle = false
notifySound = true
notificationMaxLength = 512

# 绑定同步、白名单同步与登录检查。
enableBindingSync = false
bindingSyncWhitelist = false
loginBindingCheckEnabled = false
loginCheckTimeoutSeconds = 5
loginCheckFailOpen = true
generateBindingCodeOnReject = true
verifyCodeLength = 6
loginCodeMessage = "\n绑定验证码：{code}\n请在 QQ/Discord 使用 /mc bind {code}"
```

## 客户端翻译、浮选与截图配置

这些配置只在安装了客户端 Mod 的玩家电脑上生效。截图策略默认是 `ASK`，也就是 AstrBot 请求截图时先弹出确认窗口，玩家同意后才发送。

仓库中提供了示例文件：[examples/mineastr-client.toml](examples/mineastr-client.toml)。

```toml
# 是否让本地单人世界的集成服务器连接 AstrBot；默认关闭。
localWorldServerEnabled = false

# 游戏内译文与原文显示。
gameTranslationsEnabled = true
showOriginalTranslatedMessages = true

# 告示牌和外部画框浮选。
signTranslationsEnabled = true
signTranslationMaxDistance = 8
signTranslationScale = 1.0

# AstrBot 请求截图时客户端如何处理。
# ASK：弹出确认界面，玩家同意后发送；AUTO：自动发送；DISABLED：始终拒绝发送。
screenshotMode = "ASK"

# 发送给 AstrBot 的截图最大宽度。
screenshotMaxWidth = 240

# 发送给 AstrBot 的截图最大高度。
screenshotMaxHeight = 135

# 截图 JPEG 质量，范围 0.10 到 0.95。
screenshotJpegQuality = 0.35

# 单张截图编码后的最大字节数。
screenshotMaxBytes = 131072
```

告示牌翻译只在准星命中、没有打开界面且距离未超过设置值时触发。客户端直接复用 Minecraft 已有的命中结果，不会每 tick 再做一次射线检测；右键告示牌保留为未安装客户端协议时的兼容性触发。翻译缓存位于世界 `data/mineastr_sign_translations.dat`；改动告示牌原文后旧指纹自动失效。

## 跨机器部署

如果 AstrBot 和 Minecraft 服务器不在同一台机器上：

1. AstrBot 侧 `host` 建议填 `0.0.0.0`，`port` 和 `path` 可以保持默认。
2. Minecraft Mod 侧把 `websocketUrl` 改成 AstrBot 机器的 IP 或域名。
3. 确认 AstrBot 机器的防火墙放行对应端口，默认是 `8765`。

示例：

```toml
websocketUrl = "ws://192.168.1.20:8765/ws"
```

## 配置格式注意事项

- `enabled` 只能写 `true` 或 `false`，不要加引号。
- `localWorldServerEnabled` 只能写 `true` 或 `false`，默认是 `false`。
- `reconnectSeconds` 和 `maxMessageLength` 是数字，不要加引号。
- `websocketUrl`、`token`、`serverId`、`serverName`、`botDisplayName` 是字符串，必须保留英文双引号。
- `screenshotMode` 是字符串，只能写 `"ASK"`、`"AUTO"` 或 `"DISABLED"`。
- `screenshotJpegQuality` 是小数，不要加引号。
- 不要删除 `=`，不要把中文说明文字写到 `=` 后面。
- `websocketUrl` 的格式是 `ws://地址:端口/路径`，例如 `ws://127.0.0.1:8765/ws`。

## 命令

- `/mineastr status`：查看连接状态。
- `/mineastr reconnect`：主动断开当前连接并立即重连。
- `/mineastr sign-translation status`：查看准星所指 8 格内告示牌的缓存状态。
- `/mineastr sign-translation set <locale> <translation>`：保存人工译文。
- `/mineastr sign-translation clear [locale]`：清除目标告示牌全部或指定语言缓存。
- `/mineastr sign-translation clear-all`：清空当前世界所有告示牌缓存，需要权限等级 4。

除 `clear-all` 外，这些命令需要权限等级 2。

外部客户端 Mod 的图片翻译和浮选调用方式见 [EXTERNAL_TRANSLATION_API.md](EXTERNAL_TRANSLATION_API.md)。

## AstrBot 主动查询

Mod 支持 AstrBot 发来的 `query` 协议消息：

- `status`：返回服务器名称、Minecraft 版本、MineAstr Mod 版本、在线人数、最大人数、运行时长、世界出生点和在线玩家名。
- `players`：返回在线玩家数量、最大人数、在线玩家列表，以及每名玩家是否支持截图。
- `player_state`：返回指定在线玩家的生命、饥饿、护甲、位置、维度、游戏模式、经验和状态效果。
- `inventory`：返回指定玩家的快捷栏、背包、护甲、副手和可选末影箱摘要，不返回完整 NBT。
- `nearby_entities`：返回玩家附近实体的种类计数、距离、位置和生命摘要。
- `region_features`：分析已加载区域的方块调色板、门窗/楼梯/照明/容器/红石等部件、表面高度和粗略三维占用模型；不会强制加载新区块，也不读取容器内容、告示牌文字或方块实体 NBT。
- `performance`：返回 TPS、MSPT、CPU、进程内存和在线人数摘要。
- `notify_player`：向指定在线玩家发送本地化 actionbar、标题和可选提示音。
- `binding`：绑定、解绑、重置或查询 Mod 内存绑定，并可同步原版白名单。
- `trusted_users`：用 revision 实时替换 AstrBot 动态命令管理员名单。
- `command`：执行受控服务器命令。默认关闭；必须同时通过可信用户和命令规则检查，并记录请求者与命令审计日志。
- `screenshot`：向指定玩家客户端请求低清晰度截图。玩家未安装客户端 Mod、拒绝截图、禁用截图或超时时会返回失败原因。

这些查询由 AstrBot 插件中的 LLM 工具触发。实际使用时，玩家可以直接问“我背包里还有多少火把”“附近有什么怪”“分析一下这栋房子的材料和结构”或“能看看我现在画面吗”，AstrBot 会在模型支持工具调用时主动查询 Mod，然后再组织回复。

## 受控服务器命令

命令工具采用拒绝优先设计，默认不可用。启用时至少完成下面四项：

1. 把 `enableCommandTool` 改成 `true`。
2. 确认两端 `token` 已从默认的 `change-me` 改成安全随机字符串；弱 token 下命令工具会拒绝执行。
3. 在 `trustedCommandUsers` 填入可信人员的 Minecraft UUID、玩家名或 AstrBot 用户 ID；推荐 UUID。
4. 在 `allowedCommandRules` 配置允许规则。普通条目只允许完全相同的命令；`"say *"` 允许 `say` 及其参数；单独 `"*"` 会允许所有命令，风险极高。

例如只允许玩家 Alice 查询时间和执行带参数的 `say`：

```toml
enableCommandTool = true
trustedCommandUsers = ["Alice", "00000000-0000-0000-0000-000000000000"]
allowedCommandRules = ["time query daytime", "say *"]
```

所有通过工具执行的命令都会以 WARN 级别记录请求者和命令。LLM 无法从工具参数自行指定请求者身份；AstrBot 插件会从真实事件上下文附带身份，Mod 再做最终鉴权。

## AI 制作声明

MineAstr 在开发过程中使用了 OpenAI Codex 等生成式 AI 能力，参与范围包括：

- Java、Python 与配置代码的生成、修改和重构。
- 客户端配置界面、截图授权界面及交互文案设计。
- 崩溃风险、线程安全、网络边界与命令权限的辅助审查。
- README、双语文本、配置示例和故障排查文档编写。
- Gradle 构建、客户端/服务端启动及 WebSocket 协议测试流程。

AI 输出不代表天然正确或安全。所有合并到仓库的内容均应由维护者人工审阅，并通过适当的编译、运行和安全测试后再用于生产服务器。

英文声明：*This project was created with assistance from generative AI, including OpenAI Codex. AI-assisted changes remain subject to human review, testing, and maintainer responsibility.*

## 故障排查

- 状态一直是 `未连接`：确认 AstrBot 已启动，`minecraft` 平台适配器已启用，`websocketUrl` 指向正确地址。
- 日志中出现 `401` 或认证失败：检查两端 `token` 是否完全一致。
- 玩家聊天没有进入 AstrBot：确认 `enabled = true`，并检查服务器日志中是否有连接失败或 JSON 错误。
- 游戏里没有看到回复：AstrBot 是否回复由 AstrBot 自身的群聊规则、唤醒词和权限决定。
- AstrBot 不会查询在线玩家：确认 AstrBot 当前模型支持工具调用，并且插件与 Mod 都已经更新到支持查询协议的版本。
- AstrBot 请求截图失败：确认目标玩家客户端安装了 MineAstr，并且 `screenshotMode` 不是 `"DISABLED"`。默认 `"ASK"` 模式下，玩家需要在弹窗里点击“发送截图”。
- 单人世界没有连接 AstrBot：打开 Mod 列表中的 MineAstr 配置页，进入“本地服务端”，确认 Switch 已开启且 WebSocket 地址和 Token 正确。

## License and source

This NeoForge 1.21.1 port is released under `AGPL-3.0-or-later`; see
[LICENSE](LICENSE) and [AUTHORS.md](AUTHORS.md). The complete migration history
is kept on the `minecraft-neoforge-1.21.1` branch. Rendering and interaction
references are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
