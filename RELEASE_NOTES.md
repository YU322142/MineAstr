# MineAstr 0.7.21 - 自 0.6.29 以来的累计变化

本次发布统一 Minecraft NeoForge 1.21.1 模组与 AstrBot 插件版本，两个安装包在同一个 Release 中提供。以下按 `v0.6.29` / `astrbot-v0.6.29` 标签汇总此后的累计变化。

### 图片消息联动（0.6.30 引入）

- 新增可选 ChatImage 客户端联动，将 Bot 图片显示在 Minecraft 聊天中；ChatImage 独立安装，不随 MineAstr 打包。
- 支持公网 HTTP(S) 图片、base64 和 AstrBot 本地图片组件。普通桥接、Bot 的 MessageEventResult 与平台 send_by_session 共用图片处理策略。
- 本地图片先验证真实文件格式、大小和 SHA-256，再以有界内联数据发送；公网地址受到协议及长度限制，不将 file:// 或本机临时路径显示为聊天正文。
- 服务端按客户端协议能力、ChatImage 可用状态和玩家接收偏好定向发送，以 24 KiB 分块传输；客户端再次验证、重组、缓存并通过 CICode 显示，缓存默认保留 7 天。
- AstrBot 配置新增 `relay_images_to_game`、`game_image_inline_max_bytes` 和 `game_image_max_items`，分别控制图片总开关、内联大小和单条数量。图片关闭不影响文字桥接。

### 最新 ZIP 的客户端设置与玩家命令

- 保留最新 MC ZIP 源码中的滚动 F8 设置界面、14 行设置项、固定底部按钮、撤销和恢复默认功能。
- 新增独立保存的 `receiveImageMessages` 和 `openConfigKeyEnabled` 偏好，以及 `/mineastr-images on|off` 玩家命令。
- 开关按钮直接显示开启/关闭，枚举按钮直接显示选项；避免控件自动拼接前缀影响界面文字。
- 保留 ZIP 的模组说明、作者署名等元数据调整，以及其已有的图片占位和临时路径清理。
- 保留 ZIP 的异步发送队列、后台截图编码、聊天按序派发与超时调度算法及新增日志记录；12 个关键方法与原 ZIP 内容核对一致。

### 连接与客户端功能修复

- WebSocket Upgrade 增加 10 秒握手超时；原先仅有 TCP 超时，HTTP Upgrade 不返回时可能永久停留在 connecting。
- 同步建连异常统一交给异步失败回调，清理 connecting 状态并继续定时重连；保留原有连接代次检查，防止过期连接覆盖新连接。
- 恢复 F8 按键注册，并消耗禁用期间的按键事件，避免再次开启后误弹窗和重复打开设置。
- 设置面板随窗口高度调整，保存/取消/撤销按钮保持可见；越界行控件不覆盖固定按钮区域。
- 兼容旧 `acceptBotImages=false` 偏好，F8 保存时同步两个图片开关；实际传图要求 ChatImage 能力，纯图片消息为不能接收图片的玩家提供不含路径的文字占位。
- Bot 正文和译文保留换行；译文也清理图片占位中的本地路径，避免引用与正文挤在一行。

### Bot 回复同步及引用翻译修复

- Bot 回复按现有路由同步到 QQ、Discord 等目标平台，和 Minecraft 共享正文翻译请求。
- Minecraft 来源的 Bot 回复也可同步到其他平台，避免再次回传到游戏形成回环；Minecraft 适配器不可用时仍可发送到其他平台。
- 引用文字与回复正文分别翻译，再按目标语言组合；即使正文已经是目标语言，引用仍可翻译。
- 支持只有触发消息 ID 的 Reply 组件，关联原消息补齐引用文字与发送者；引用链中的图片路径不作为引用文字。
- 保留已配置的路由、原文显示偏好、媒体传递及防回环规则。运行日志增加同步目标数、正文/引用语言信息，不记录聊天正文、密码或 token。

### 多语言与文档

- F8 和本地服务端子页面的标题、说明、标签、按钮、选项、单位及连接提示使用可覆盖的语言键，补齐遗漏的恢复默认按钮翻译。
- 百分比与 KiB 单位移至语言 JSON；枚举键使用 Locale.ROOT，避免系统语言改变键名。
- 中英文语言键一致，可通过新增 `assets/mineastr/lang/<locale>.json` 或资源包覆盖添加语言。
- 同步配置示例和中英文配置、开发、更新日志、README、Fork/联动来源及 IDEA 导入说明，保留许可证和第三方声明。
- 模组运行版本、Gradle 构建版本，以及插件注册、metadata 与安装包版本统一。

### 支持范围

当前维护范围为 NeoForge 1.21.1 模组与 AstrBot 插件。旧 `minecraft-mod` Fabric 分支已停止支持，保留 0.6.28 历史源码和记录，不再提供更新、修复或兼容性保证。本 Release 不提供 Fabric 二进制，NeoForge JAR 不能安装到 Fabric。

### 安装与生产环境

Minecraft 模组适用于 NeoForge 1.21.1。游戏内图片显示需要客户端额外安装 ChatImage；客户端 MineAstr 可选，但 F8、截图和浮选等能力需要客户端安装。AstrBot 插件安装包保留安装器所需的首条目录记录。

### 验证与源码

- 模组：Java 21 完整 `test build` 成功，18 项测试通过；真实回环 WebSocket 测试覆盖不响应的 Upgrade 超时、后续成功连接及失败日志脱敏。
- 插件：146 项 Python 单元测试通过，涵盖跨平台 Bot 回复、游戏不回环、缺失游戏适配器及不同源语言的正文/引用组合。
- 中英文语言键和设置页静态翻译键检查通过；没有启动真实游戏客户端验证 GUI 的视觉效果。
- 安装包、模组源码 JAR、完整模组源码 ZIP 和 SHA256SUMS 校验文件一并提供。
- [模组源码](https://github.com/YU322142/MineAstr/tree/v0.7.21) · [AstrBot 插件源码](https://github.com/YU322142/MineAstr/tree/astrbot-v0.7.21)

调查阶段通过模组自带 reconnect 恢复桥接，不重启服务。取得维护授权后，保存世界并仅重启目标 Minecraft 容器与 AstrBot 服务，实际加载 0.7.21；启动、版本识别、WebSocket 连接和翻译策略同步已核验，旧版文件留有备份。
