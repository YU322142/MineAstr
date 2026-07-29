# MineAstr

[![AI Assisted](https://img.shields.io/badge/AI-OpenAI%20Codex%20Assisted-10A37F?style=for-the-badge&logo=openai&logoColor=white)](#ai-制作声明)

> [!IMPORTANT]
> **AI 制作声明：MineAstr 采用生成式 AI 参与架构设计、编码、UI、文档与测试。** AI 生成或修改的内容由项目维护者审阅、验证并承担最终维护责任。

MineAstr 将 Minecraft 1.21.11 Fabric 服务器接入 AstrBot、QQ/OneBot 与 Discord，提供 AQQBot 兼容的账号绑定、跨平台聊天、退群解绑、群名片/昵称同步、通知、状态查询、受控命令、LLM 工具和按客户端语言显示的可选游戏内翻译。

## 项目分支

- [`astrbot-plugin`](https://github.com/YU322142/MineAstr/tree/astrbot-plugin)：AstrBot 插件端；分支根目录可直接作为插件项目。
- [`minecraft-mod`](https://github.com/YU322142/MineAstr/tree/minecraft-mod)：Minecraft Fabric Mod；目标为 Minecraft `1.21.11`、Fabric API `0.141.4+1.21.11`、Java `21`。

`main` 是项目索引；两个可构建工程分别保留在以上分支，以兼容 AstrBot 从仓库分支安装插件的目录要求。

## 当前版本

- AstrBot 插件：`0.6.12`
- MineAstr Fabric Mod：`0.6.11`
- Minecraft：`1.21.11`
- Fabric API：`0.141.4+1.21.11`

成品请从 [GitHub Releases](https://github.com/YU322142/MineAstr/releases) 下载：

- `astrbot_plugin_mineastr-v0.6.12.zip`
- `mineastr-fabric-0.6.11.jar`

v0.6.12 将翻译改为统一生成后分发：同一条源消息先合并游戏客户端及所有 QQ/Discord 接收频道需要的目标语言，只调用一次模型，再分别套用游戏模板、平台标签、语言选择与原文开关。翻译模型和术语表使用统一设置，旧的分平台/频道提示词会自动合并。该更新只需替换 AstrBot 插件，继续兼容 Fabric Mod 0.6.11。

## 连接方式

Minecraft Mod 主动连接 AstrBot 插件注册的 `minecraft` 平台适配器：

```text
Minecraft MineAstr Mod -> ws://astrbot.example.com:8765/ws -> AstrBot
```

跨机器部署时，AstrBot 监听地址应设为 `0.0.0.0`，Mod 填写 AstrBot 主机的可达地址。Token 必须由部署者自行生成并在两端保持一致；留空或保持 `change-me` 时 AstrBot 插件会安全拒绝全部连接。

仓库不包含真实服务器 IP、Token、QQ 号、Discord 用户 ID 或运行时绑定数据库。示例仅使用 `127.0.0.1`、`0.0.0.0`、`change-me` 和虚构账号；运行时配置、日志与数据库均已加入 `.gitignore`。

## 绑定与白名单

`0.6.10` 会按服务器在线/离线认证模式解析玩家 UUID，清理同名但 UUID 不同的旧项，直接写入并保存原版白名单，再读回核验；原版开始 UUID 白名单检查前还会按本次连接真正使用的身份二次对账。登录检查只使用纯玩家名，不会把玩家 IP/端口写入绑定；插件启动时会自动迁移旧版错误记录并重新同步白名单。启动器使用正版账号并不代表 `online-mode=false` 的后端会使用 Mojang UUID。

本版为 QQ/OneBot 与 Discord 分别提供逐行通知语言列表、总开关、服务器连接/断开、玩家进入/离开/死亡开关和按语言自定义样式；只写一行是单语，写多行会按顺序并列发送。死亡事件使用 Minecraft 结构化伤害类型生成中文/英文原因，不再出现“玩家名 因 玩家名 died”。未绑定登录、验证码与游戏内定向提醒支持客户端中英文；安装同版客户端 Mod 时会跟随客户端语言。

QQ/Discord 消息和 AstrBot 回复可选使用 AstrBot 文本模型检测原文语言并生成多个 locale 的译文，Minecraft 服务端会按每位玩家的客户端语言分别显示。目标语言与原文一致时直接显示原文，不会重复翻译。安装同版客户端 Mod 的玩家可在 F8 界面开关译文和原文；翻译默认关闭，失败或没有匹配语言时始终回退原文。

服主使用唯一的统一翻译提示词/术语表为专有名词指定固定译法。Discord 可按任意数量的频道选择目标语言、事件开关和多语言样式；游戏端及 QQ/Discord 每个接收会话仍可独立决定语言和是否保留原文，但同一条源消息合计只调用一次模型后分发。

AstrBot 插件元数据的安装/更新源为本 Fork 的 [`astrbot-plugin`](https://github.com/YU322142/MineAstr/tree/astrbot-plugin) 分支，不会回到上游仓库或下载索引用的 `main` 分支。

需要“检查玩家已绑定、同步原版白名单、AstrBot 断线或超时仍放行”时：

- AstrBot 插件：`binding_enabled=true`、`need_bind_to_login=true`、`sync_binding_to_server=true`、`binding_sync_required=true`
- Fabric Mod：`enableBindingSync=true`、`bindingSyncWhitelist=true`、`loginBindingCheckEnabled=true`、`loginCheckFailOpen=true`

完整安装和权限说明请查看两个工程分支的 README。

需要使用服务器命令时开启 Mod `enableCommandTool=true`。`allowedCommandRules` 是所有人可立即执行的公开命令白名单，不应加入 `op *` 等管理命令；白名单外命令只生成待审批 ID。插件 `sync_command_admins_to_server=true` 与 Mod `syncTrustedCommandUsers=true` 会在审批前实时同步管理员，再由管理员使用 `/mc approve <审批 ID>` 执行 Mod 保存的原始命令。

## 构建验证

- AstrBot 插件：70 个自动化测试通过，覆盖 fail-closed/常数时间鉴权、配置迁移、AstrBot Schema 类型兼容、QQ/Discord 自动化、游戏与平台共用单次翻译后分发、源语言去重、自定义术语表、命令审批、管理员实时同步、并发冷却和游戏内翻译协议。
- Fabric Mod：Gradle `clean build` 通过。
- 实机协议联调：Minecraft 1.21.11 + Fabric API 0.141.4，已验证 Mixin 加载、离线后端收到正版客户端 UUID 时改用服务端真实离线 UUID、`whitelist_verified=true`，并实际通过原版白名单登录校验；既有解绑、管理员同步、可信命令和正常关服流程保持有效。

## 许可与来源

本项目按 `AGPL-3.0-or-later` 开源，详见 `LICENSE`。本移植基于 [Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr)，AQQBot 功能语义参考 [alazeprt/AQQBot](https://github.com/alazeprt/AQQBot)（LGPL-2.1）；详细第三方说明位于插件分支的 `THIRD_PARTY_NOTICES.md`。

## AI 制作声明

本项目使用 OpenAI Codex 等生成式 AI 能力参与 Java/Python 代码、LLM tools、客户端 UI、安全审查、README 与构建测试。AI 输出仍需经过人工审阅和实际测试。

英文声明：*This project was created with assistance from generative AI, including OpenAI Codex. AI-assisted changes remain subject to human review, testing, and maintainer responsibility.*
