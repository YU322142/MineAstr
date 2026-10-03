# MineAstr 0.7.27

0.7.28 新增现有模型选择与翻译回退、独立目标并行发送、正确的聊天图片透明混合及有界 GIF 播放。

[English](README.en.md) · [配置参考](docs/CONFIGURATION.zh-CN.md) · [更新日志](CHANGELOG.zh-CN.md) · [Changelog](CHANGELOG.md) · [外部翻译 API](EXTERNAL_TRANSLATION_API.md)

MineAstr 是面向 Minecraft 1.21.1 / NeoForge 的 AstrBot 桥接模组。它把聊天、事件和受控查询送到 AstrBot，并根据每位玩家的客户端语言显示翻译结果。

## Fork 与项目地址

- 当前社区 Fork：[YU322142/MineAstr](https://github.com/YU322142/MineAstr)，NeoForge 1.21.1 开发分支为 `minecraft-neoforge-1.21.1`。
- 原始上游：[Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr)。
- 沉浸画框联动 Fork：[YU322142/ImmersivePaintings](https://github.com/YU322142/ImmersivePaintings)，对应分支为 `1.21.1-neoforge`。

0.7.27 与 Immersive Paintings 0.7.15 的联动由上述两个社区 Fork 共同维护，并非两个上游项目的官方联动。相关问题请提交到对应 Fork。

| 项目 | 要求 |
| --- | --- |
| MineAstr | `0.7.27` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.219` 或更高 |
| Java | `21` |
| 运行侧 | 客户端与服务端 |

## 它负责什么

- 桥接聊天、加入、离开和结构化死亡事件。
- 按客户端语言显示翻译后的聊天内容。
- 为告示牌、实体和外部图片提供准星目标译文 HUD。
- 向 AstrBot 提供服务器、玩家、背包、附近实体和区域特征查询。
- 在玩家明确允许时提供低清晰度截图。
- 与 [ChatImage](https://github.com/kitUIN/ChatImage) 可选联动，在聊天中显示 Bot 图片，并允许 Bot 端和每个客户端分别关闭图片传递。
- 提供账号绑定、可选白名单同步和严格受控的命令工具。

MineAstr 不负责替换模组文件、同步客户端目录或保存画作图片；这些分别属于 MCSync 和对应内容模组的职责。

## 部署拓扑

```text
Minecraft 客户端
  └─ MineAstr：语言、HUD、准星目标、截图授权
        ⇅ NeoForge 自定义网络
Minecraft 服务端
  └─ MineAstr：事件、查询、权限、WebSocket 桥接
        ⇅ WebSocket
AstrBot
```

客户端不是普通聊天桥接的硬前提，但按玩家语言显示译文、准星目标 HUD、截图和沉浸画框图片翻译都要求客户端安装同版本 MineAstr。

## 安装

1. 把 `mineastr-neoforge-1.21.1-0.7.27.jar` 放入服务端 `mods/`。
2. 把同一个 JAR 放入参与翻译功能的客户端 `mods/`。
3. 首次启动后编辑服务端 `config/mineastr-common.toml`。
4. 在 AstrBot 的 Minecraft 适配器中设置相同的 WebSocket 路径和 Token。
5. 重启服务端，并用 `/mineastr status` 检查连接状态。

最小配置：

```toml
enabled = true
websocketUrl = "ws://127.0.0.1:8765/ws"
token = "CHANGE_ME_LOCAL_ONLY"
serverId = "minecraft"
```

真实地址和 Token 只应保存在运行环境，不要提交到公开仓库。完整字段见 [配置参考](docs/CONFIGURATION.zh-CN.md)，可复制的示例位于 [`examples/`](examples/)。

## 翻译显示行为

0.7.27 将告示牌、实体和沉浸画框统一为“当前准星目标”生命周期：

- 只在目标仍然有效时显示译文。
- 移开准星、打开界面、隐藏 HUD、切换世界或目标失效时立即清理。
- 目标 HUD 默认只显示译文，不在游戏画面旁重复原文。
- 优先复用 Create 风格悬浮文本渲染；Create 不可用时使用安全后备渲染。

普通聊天的“是否同时显示原文”是独立设置，不影响目标 HUD。

## Bot 图片与 ChatImage 联动

- 推荐服务端和客户端安装 MineAstr `0.7.27`；新版客户端自带内联缩略图，ChatImage 可选。
- AstrBot 插件的 `bridge_settings.relay_images_to_game` 是 Bot 端总开关。
- 客户端按 F8 后可单独关闭“接收图片消息”；偏好独立于 ChatImage 保存，实际图片发送要求客户端具备 MineAstr 内联图片或 ChatImage 能力。兼容旧 `acceptBotImages=false` 设置。
- Bot 本机临时图片会在限定大小内安全内联；公网 HTTP(S) 图片由 MineAstr 的有界后台队列获取。Bot 本机路径和图片 URL 都不会作为普通聊天正文显示。
- 内联图片经服务端限额、格式与 SHA-256 校验后分块下发，客户端写入 `cache/mineastr/chat-images/`，缓存默认保留 7 天。

启用原生聊天翻译后，MineAstr 会用未签名消息重发译文，因此不保留完整的 Secure Chat 举报链路。如果服务器需要原版签名和过滤语义，应在 AstrBot 策略中关闭原生聊天翻译。

## 与沉浸画框联动

图片翻译要求：

- 推荐客户端与服务端安装 MineAstr `0.7.27`；登录绑定修复需要服务端同步更新。
- 客户端与服务端均安装 Immersive Paintings `0.7.15+1.21.1`。
- AstrBot 桥接已连接并支持图片翻译。
- 客户端开启游戏翻译和悬浮翻译。

沉浸画框从自身缓存取得完整图片，压缩后调用 MineAstr 的公共图片翻译 API。MineAstr 负责请求和 HUD，画作原图仍由 Immersive Paintings 管理。

## 常用命令

0.7.27 的 F8 设置支持滚动、撤销、恢复默认、独立图片接收偏好及快捷键开关；窗口较小时底部按钮仍保持可见。所有界面文字与单位通过语言键提供，多语言可添加或覆盖 `assets/mineastr/lang/<locale>.json`，详见 [配置参考](docs/CONFIGURATION.zh-CN.md)。`/mineastr-images on|off` 可直接更改当前玩家的服务端图片偏好。

| 命令 | 用途 |
| --- | --- |
| `/mineastr status` | 查看连接状态 |
| `/mineastr reconnect` | 立即重连 AstrBot |
| `/mineastr sign-translation status` | 查看当前告示牌缓存状态 |
| `/mineastr sign-translation set <locale> <translation>` | 写入人工译文 |
| `/mineastr sign-translation clear [locale]` | 清除当前告示牌缓存 |
| `/mineastr sign-translation clear-all` | 清空当前世界告示牌缓存，要求权限等级 4 |

## 安全默认值

- 部署前必须替换生成的 `change-me` Token。
- `enableCommandTool = false`：命令工具默认关闭。
- 截图策略默认 `ASK`：每次请求由玩家确认。
- 单人世界桥接默认关闭；启用后只影响本地集成服务器。
- 绑定同步、白名单同步和登录前绑定检查应按实际需求分别启用。

## 快速排错

| 现象 | 优先检查 |
| --- | --- |
| 日志显示“已被配置禁用” | 活动 `mineastr-common.toml` 的 `enabled` |
| 一直未连接 | `websocketUrl`、AstrBot 监听地址、防火墙和 Token |
| 告示牌正常、画作不翻译 | Immersive Paintings 0.7.15，客户端是否同样安装 MineAstr 0.7.27 |
| Bot 图片只显示为 `[图片]` | 客户端 MineAstr 是否更新、F8 图片接收是否开启、Bot 端图片转发是否开启 |
| 移开准星仍显示 | 客户端是否混装旧 MineAstr 或旧画框 JAR |
| 图片请求没有结果 | AstrBot 图片能力和客户端完整图缓存 |
| 单人世界不连接 | `localWorldServerEnabled` 是否启用 |
| 服务端能连接但客户端无 HUD | 客户端版本、浮选开关和双方网络兼容性 |

排错时先确认 `mods/` 中每个 modId 只有一个 JAR，且客户端、服务端版本配对。

## 构建

```powershell
.\gradlew.bat clean test build --no-daemon
```

产物位于 `build/libs/`。

## 许可证与声明

本 NeoForge 1.21.1 分支采用 `AGPL-3.0-or-later`，详见 [LICENSE](LICENSE)、[AUTHORS.md](AUTHORS.md) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

项目使用生成式 AI 辅助设计、编码、审查、测试和文档整理；所有发布内容仍由维护者负责审核与验证。

## 游戏内聊天布局（0.7.27）

左列是 MC、Discord 或 QQ 平台图标和昵称，右列是消息正文；机器人回复使用提问者所在平台的图标。图片放在正文下方，保持比例并限制为小缩略图。原版输入、历史、滚动及正文链接样式继续生效。F8 的图片接收开关仍有效，新版不再要求安装 ChatImage。

## ModernUI 字体兼容

昵称使用粗体。正文通过 Minecraft 的 Font、StringSplitter 和 Component 样式 API 排版，沿用 ModernUI 3.13.0.1 已接管的 TrueType/OpenType、黑体/字体回退、抗锯齿与 Unicode 渲染；不覆盖玩家的字体设置。按逻辑文字和样式分段换行，保留粗体、颜色、链接、悬停、双向文字及 Emoji，避免把视觉顺序文字再次重排。ModernFix 5.27.20 继续负责性能优化和兼容性修复。

[ModernUI 官方说明](https://github.com/BloCamLimb/ModernUI-MC) · [ModernFix 1.21.1 补丁说明](https://github.com/embeddedt/ModernFix/wiki/1.21.1-Summary-of-Patches)

- MC 内的用户名称统一优先使用已绑定的 Minecraft 游戏名，没有绑定时使用 QQ / Discord 用户名；平台图标仍表示消息来源。普通消息、模板、引用、编辑、撤回、@ 玩家提醒与广播同步此规则；多账号时使用最早绑定的游戏名，解绑后自动切换。昵称缓存最多 512 项、30 秒到期，绑定/解绑/迁移立即失效，登录鉴权仍读取实时绑定。

0.7.27 保留整个聊天队列的连续缓动、ModernUI 滚动插值、高分辨率平台纹理和图片；F8 可以调整图片比例、聊天最大高度、动画开关、滚动/入场时长及轻移距离。详见 [本次发布说明](RELEASE_NOTES.md)。

玩家主题色支持单色、双色与三色慢速渐变（4–20 秒），仅用于昵称和消息正文，平台图标及图片保留原色。F8 提供 RGB 滑块、十六进制输入和实时预览；启用颜色须相对 #303030 达到 4.5:1 对比度，过暗颜色不可保存且不会自动提亮。服务器按 UUID 验证修改者并随世界存档保存主题，离线玩家主题也会同步。渐变色表预计算，不改动 ModernUI 字体、Unicode 排版或原有点击/悬停事件。

同一人的 QQ / Discord 消息转发到 MC 后，通过已绑定的 MC 游戏名使用同一个主题和渐变，来源图标仍为 QQ / Discord；未绑定或未设置主题时沿用默认显示。

MC 原生聊天在新版两端立即显示原文，后台译文就绪后原位更新。聊天图片支持悬停预览与左键点击查看，滚轮缩放、左键拖动、双击重置和 Esc 返回；图片与聊天共同滚动、淡入淡出。需要客户端和 MC 服务端均更新至 0.7.27。
