# Changelog

## 0.6.15

- New release line for targeted Minecraft sign translation overlays and normalized duplicate-text handling.
- QQ/Discord AI responses that repeat the source are emitted as the original text without locale or `[原文/Original]` labels.
- Minecraft clients query and display only the sign under the crosshair; translations remain cached in the world save.

## 0.6.14

- Discord now listens for `on_message_edit` on the AstrBot Pycord client. Edited messages in bridged guild channels are re-sent to Minecraft and other bridge targets with an `[Edited]` marker, using the same filters and unified translation result as the original relay.
- Bot-authored edits follow `relay_bot_conversations_to_game`; unrelated guilds, unbridged channels, commands, and unchanged edits are ignored. Listener cleanup now removes both Discord event handlers.
- QQ/Discord translation output now compares normalized source and translated text before adding locale or `[原文/Original]` labels; an AI response that repeats the source is emitted as the original text only.
- Minecraft sign translation now uses targeted world-space floating text: only the sign under the crosshair is queried and displayed, while the original sign text remains unchanged and cached translations persist in the world save.

## 0.6.13

- `/mc approve` 与 `/mc reject` 不带参数时先校验管理员身份并显示带序号的待审批列表；可按序号或完整审批 ID 操作。
- 新增 `mineastr_manage_command_approvals` LLM 工具；每次列出、批准或拒绝都会重新校验当前真实用户的 AstrBot/MineAstr 管理员身份。
- 审批仍只引用 Mod 保存的审批 ID，不会让 Bot 重新提交或改写高权限命令。
- 管理员同步会过滤不符合 Mod 身份语法的条目，并在审批前同步当前管理员的用户 ID 与平台限定 ID，修复单个无效条目导致整批 `invalid_trusted_user` 的问题。

## 0.6.12

- 同一条源消息会合并 Minecraft 客户端与所有 QQ/Discord 接收频道需要的目标语言，只调用一次翻译模型，再按各接收端语言、模板和原文开关分发。
- 翻译模型、提示词和术语表改为统一设置；旧的分平台与 Discord 频道提示词在升级时自动合并。
- 保留每个 QQ/Discord 接收端独立的翻译开关、单语/多语目标列表及 Discord 不限数量频道配置。

## 0.6.11

- WebSocket 鉴权改为 fail-closed：Token 留空或仍为 `change-me` 时拒绝全部连接，并使用 `hmac.compare_digest` 常数时间比较；接收单包增加 2 MiB 硬上限。
- 翻译模型现在返回并缓存源语言；目标语言与原文一致时直接显示原文，不再生成或并列显示重复译文。
- 服务器命令改为两阶段策略：`allowedCommandRules` 内的公开命令所有用户可执行，其他命令只生成待审批 ID，由管理员使用 `/mc approve` 明确批准后才执行 Mod 保存的原始指令；另提供 `/mc reject` 与 `/mc approvals`。
- Bot 管理员名单在连接和每次审批前实时同步，并带单调 revision，避免旧同步请求覆盖新名单。
- 新增不限数量的 Discord 频道配置，可按频道覆盖单语/多语翻译、术语提示词、事件语言、开关与自定义样式，未命中时回退全局 Discord 设置。
- 加固截图并发冷却、验证码缓存和管理员配置正则：同一截图并发请求只能通过一个，验证码缓存满时不再驱逐其他玩家，正则执行具有超时和嵌套量词拦截。

## 0.6.10

- 配套 Fabric Mod 改为按 Minecraft 服务端实际认证模式同步白名单 UUID，不再把离线后端上的正版账号误写成 Mojang UUID。
- Mod 在原版白名单校验前会使用本次登录的真实身份完成二次对账，兼容代理、Floodgate 与混合认证；AstrBot 协议号保持 `1`，旧版 `0.6.9` 插件仍可通信。

## 0.6.9

- QQ/OneBot 与 Discord 的通知语言改为逐行列表：一行是单语，多行会按配置顺序把同一事件以多种语言并列发送。
- 两个平台分别新增简体中文和英文的五类自定义多行样式；每种语言可独立留空使用预设，也可使用原有事件占位符。
- 新增全局及 QQ/Discord 分平台翻译附加提示词/术语表；QQ 与 Discord 的聊天消息也可分别选择单语或多语自动翻译、是否显示原文。
- 桥接会话中玩家 @机器人时，玩家消息和 AstrBot 最终纯文本回复可同时转发进 Minecraft；Reply/At 空中间消息不会进入游戏。
- 新增可选的命令管理员同步：把 MineAstr 管理员和 AstrBot 全局 `admins_id` 在 Mod 重连时同步到服务端内存可信名单；服务端静态名单、命令规则和总开关保持独立。

## 0.6.8

- 修复两个隐藏的旧版 QQ/Discord 通知兼容字段误用 AstrBot 不支持的 `dict` 配置类型，导致插件重载失败的问题。
- 兼容字段改为完整的 `object/items` Schema，并增加递归配置类型测试，确保安装包中的所有字段均可被 AstrBot 4.x 解析。

## 0.6.7

- AstrBot 配置页改为六个可折叠分组；管理员、群号、Guild、会话和过滤规则统一使用多行输入框。
- 首次升级会把旧版平铺配置无损迁移到分组结构，隐藏的兼容字段确保 AstrBot 4.23.6 不会在插件初始化前删除旧值。
- 管理员/远程指令区域醒目标明 AstrBot 与 Mod 两层安全开关，并给出 `trustedCommandUsers`、`allowedCommandRules` 的对应关系。
- 服务器连接/断开、玩家进入/离开/死亡各自拥有独立开关，预设通知支持简体中文和英文；QQ/OneBot 与 Discord 配置区分别提供独立的平台 ID、总开关、语言、单项开关和模板。
- 死亡通知会去掉原因中重复的玩家名，并使用 1.21.11 Mod 上报的 `death_type`、攻击者和武器生成可读的本地化原因。
- 所有预设事件和未绑定登录提示统一使用 `[MC]` 前缀；用户自定义过的模板不会被语言切换覆盖。
- 登录拒绝响应新增客户端翻译键，配套 Fabric 客户端 Mod 可按玩家客户端语言显示未绑定提示和验证码。
- 新增可选的游戏内聊天自动翻译：AstrBot 模型可为多个 Minecraft locale 生成译文，Fabric Mod 按每位玩家的客户端语言分别显示；翻译失败安全回退原文。
- 安装同版客户端 Mod 的玩家可在 F8 界面独立开关译文和原文显示；未安装客户端 Mod 时使用服务端配置的原文显示默认值。

## 0.6.6

- 修复 Fabric 登录校验把 `玩家名 (/[IP]:端口)` 当作游戏名的问题。
- 自动清洗旧 Mod 上报的 IPv4/IPv6 地址后缀，并迁移 SQLite 中已经错误保存的绑定。
- 插件重载后会对当前已连接的 Minecraft 服务器立即重新对账，使修正后的纯游戏名和原版白名单同步生效。

## 0.6.5

- 补齐 QQ/OneBot 与 Discord 的退群自动解绑、群名片/昵称同步和多游戏账号顺序显示。
- 验证码绑定直接采用 Minecraft 服务端上报的登录名，不再误套手工玩家名正则。
- 增加登录绑定校验审计日志，能直接确认数据库命中和放行结果。
- 配套 Fabric Mod 改为按在线/离线认证模式解析 UUID，直接写入、保存并核验原版白名单；同步失败不再假报成功。
- 修复 Windows ZIP 缺少首个目录条目时 AstrBot 4.23.6 上传安装报 `NotADirectoryError`；新增跨平台打包脚本和 CI 校验。
- WebSocket 客户端连接日志不再输出远端 IP 地址。
- 固定支持 Minecraft 1.21.11、Fabric API 0.141.4+1.21.11 和 Java 21。

## 0.6.4 及更早

早期版本完成 AstrBot Minecraft 平台适配器、AQQBot 兼容绑定流程、跨平台聊天、通知、状态查询、受控命令与 LLM 工具的初步移植。
