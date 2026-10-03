# MineAstr 开发交接 / Developer Handoff

0.7.29 修复大 GIF 下载、QQ/Discord 动图提取及慢平台事件阻塞 WebSocket 心跳的问题。

本文按文件记录当前 0.7.27 代码的职责、协议边界和后续 TODO。源码行为以 NeoForge 1.21.1 分支为准。

## 文件职责

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/mineastr/MineAstr.java` | Mod 入口、事件注册、版本与公共生命周期。 |
| `src/main/java/com/mineastr/MineAstrBridge.java` | WebSocket 桥接核心：聊天、翻译、绑定、查询、截图及 Bot 图片安全校验/定向发送。 |
| `src/main/java/com/mineastr/MineAstrNetwork.java` | NeoForge 自定义 payload 注册、服务端/客户端分发和连接能力判断。 |
| `src/main/java/com/mineastr/MineAstrPayloads.java` | 所有 payload 的结构、长度上限和编解码；修改字段时必须同步协议文档。 |
| `src/main/java/com/mineastr/MineAstrClient.java` | 客户端事件、F8 配置入口、语言能力上报和 ChatImage 能力握手。 |
| `src/main/java/com/mineastr/MineAstrClientConfig.java` | 客户端翻译、浮选、截图隐私和 Bot 图片接收偏好。 |
| `src/main/java/com/mineastr/MineAstrConfig.java` | 服务端 WebSocket、绑定、翻译和 Bot 图片总开关。 |
| `src/main/java/com/mineastr/MineAstrConfigScreen.java` | 可滚动 F8 设置、撤销/恢复默认、独立图片偏好和可替换语言键。 |
| `src/main/java/com/mineastr/MineAstrBotImageClient.java` | Bot 图片分片重组、SHA-256 校验、缓存清理和 ChatImage CICode 显示。 |
| `src/main/resources/assets/mineastr/lang/zh_cn.json` | 中文界面和提示文本。 |
| `src/main/resources/assets/mineastr/lang/en_us.json` | English 等价界面和提示文本。 |
| `src/main/templates/META-INF/neoforge.mods.toml` | Mod 元数据及 ChatImage 的可选客户端依赖声明。 |
| `src/test/java/com/mineastr/MineAstrConnectionTest.java` | 真实回环 WebSocket 握手超时/后续连接、失败日志脱敏和占位清理回归测试。 |
| `build.gradle` | NeoForge 构建、测试和发布产物配置。 |
| `gradle.properties` | Minecraft、NeoForge 和 MineAstr 版本；本次为 `0.7.27`。 |
| `README.md` / `README.en.md` | 面向使用者的中文/英文安装与配置说明。 |
| `docs/CONFIGURATION.zh-CN.md` / `docs/CONFIGURATION.en.md` | 配置项级参考。 |
| `CHANGELOG.zh-CN.md` / `CHANGELOG.md` | 双语版本变更记录。 |
| `THIRD_PARTY_NOTICES.md` | 第三方依赖、ChatImage 联动说明；仓库不打包 ChatImage。 |

## 图片联动边界

- AstrBot 只向 Minecraft 发送公共 `http(s)` 图片地址，或经过格式、大小和 SHA-256 校验的内联图片。
- 本地路径、`file://` 路径和失败的图片数据不会进入游戏聊天。
- 服务端只向上报图片渲染能力且在 F8 开启接收的客户端定向发送图片。
- 新版客户端无需 ChatImage，旧版客户端没有图片能力时仅显示文字或无路径的 `[图片]` 标记。

`MineAstrClientStartupTest.java` 验证配置未加载时的加载画面 Tick、外部接口与偏好发送，以及配置加载/卸载后的设置切换。

## TODO

0.7.27 完整 `test build` 与 59 项测试通过。`connectWebSocket` 为 Upgrade 添加 10 秒超时并对同步/异步失败使用同一安全日志消息；不应把网络等待搬到主线程。ZIP 中的 10 个异步方法逐字保持一致；聊天请求和按序派发仅添加即时预览、批次预算，原机制保留，已有运行日志保留。本次版本变化见 [发布说明](RELEASE_NOTES.md)。

- [x] 真实 NeoForge + ModernFix + ChatImage 客户端验证 PNG 缩略图、缩放和资源重载。
- [ ] 覆盖更多图片格式、缓存过期和网络超时的实机用例；不支持的格式保留本地化失败提示。
- [ ] 增加协议版本协商，允许未来图片传输字段平滑扩展。
- [ ] 为高延迟连接增加发送队列指标，避免大量图片占用主线程日志。
- [ ] 为服务端管理员增加图片大小/数量运行时监控。
- [ ] 每次改动 payload 后同步更新 `PROTOCOL.md` 与中英文变更日志。

## English summary

The file table above is the source-of-truth handoff map. `MineAstrBridge.java` owns server-side validation and routing; `MineAstrBotImageClient.java` owns client-side reassembly, hashing, cache retention and ChatImage rendering; `MineAstrPayloads.java` is the protocol schema. Bot images are optional, capability-gated and path-free. The TODO list above is intentionally kept small and actionable.

## 0.7.27 聊天维护

`MineAstrChatMixin` 仅重排 ChatComponent 显示副本并绘制预留图片区域；不替换整个聊天组件。`MineAstrChatLayout` 保留正文样式并缓存行标记；`MineAstrChatImages` 用单线程有界队列下载/解码/缩放，客户端线程上传纹理，最多保留 128 张纹理。原分片重组上限 32，队列上限 32，单图片 1400 KiB / 3200 万像素。关闭连接后旧回调由 generation 检查拒绝；字体重载重新布局并保留动态缩略图。真实 ModernFix 5.27.20 与 ChatImage 1.4.7 客户端已做渲染/重载验证。

新 Release 只比较上一正式发布版本；历史只放 CHANGELOG。

## ModernUI 字体兼容

昵称使用粗体。正文通过 Minecraft 的 Font、StringSplitter 和 Component 样式 API 排版，沿用 ModernUI 3.13.0.1 已接管的 TrueType/OpenType、黑体/字体回退、抗锯齿与 Unicode 渲染；不覆盖玩家的字体设置。按逻辑文字和样式分段换行，保留粗体、颜色、链接、悬停、双向文字及 Emoji，避免把视觉顺序文字再次重排。ModernFix 5.27.20 继续负责性能优化和兼容性修复。

[ModernUI 官方说明](https://github.com/BloCamLimb/ModernUI-MC) · [ModernFix 1.21.1 补丁说明](https://github.com/embeddedt/ModernFix/wiki/1.21.1-Summary-of-Patches)

0.7.27 保留整个聊天队列的连续缓动、ModernUI 滚动插值、高分辨率平台纹理和图片；F8 可以调整图片比例、聊天最大高度、动画开关、滚动/入场时长及轻移距离。详见 [本次发布说明](RELEASE_NOTES.md)。

聊天动画由 MineAstrChatMotion（滚轮目标连续重定向）和 MineAstrChatInsertionMotion（整队位移解析阻尼，保留速度）计算；渲染只使用统一帧采样的浮点坐标。Mixin 不修改原始消息、记录或签名。图片上传队列用两个许可限流，位图转换在后台执行；GPU 上传完成或失败、过期回调均释放许可。高分辨率图标不通过字体图集，字形仅保留同宽透明占位和悬停事件；纹理初次加载和重载后生成 Mipmaps，恢复原来的 GL 纹理绑定。原 ZIP 异步方法不得替换为同步逻辑。

玩家主题色支持单色、双色与三色慢速渐变（4–20 秒），仅用于昵称和消息正文，平台图标及图片保留原色。F8 提供 RGB 滑块、十六进制输入和实时预览；启用颜色须相对 #303030 达到 4.5:1 对比度，过暗颜色不可保存且不会自动提亮。服务器按 UUID 验证修改者并随世界存档保存主题，离线玩家主题也会同步。渐变色表预计算，不改动 ModernUI 字体、Unicode 排版或原有点击/悬停事件。

同一人的 QQ / Discord 消息转发到 MC 后，通过已绑定的 MC 游戏名使用同一个主题和渐变，来源图标仍为 QQ / Discord；未绑定或未设置主题时沿用默认显示。

聊天文字绘制使用 MixinExtras `WrapOperation` 并调用 `original.call`，以保留其他模组既有渲染。不要改回互斥的 `Redirect` 或通过 `require=0` 跳过冲突：Showcase Item 1.21.1-1.1.0 使用同一 drawString 调用。回归环境包含 Showcase Item、ModernUI、ModernFix 和 ChatImage，运行时确认 Showcase Item 原绘制钩子仍被调用。

即时原文仅发送可选 NativeChat payload，不在服务器主线程等待模型或网络。PendingNativeChat 保存已收到预览的 UUID；既有按序完成队列对其发送同 ID 的 update，旧客户端仍收取最终消息。MineAstrNativeChatClient/MineAstrChatAccess 在原版有界历史中替换；显示重排保留动画状态，不使用第二条消息伪装更新。MineAstrChatImages 每帧记录裁剪后的命中区域；MineAstrChatImageInteraction 只处理 ChatScreen 的鼠标交互；MineAstrImageScreen/MineAstrImageViewport 复用纹理并处理光标锚点缩放及有限拖动。背景模糊必须先于图片绘制。

原生聊天完成队列每批最多 8 条或 2 ms 检查点；超过预算时由原有调度线程 50 ms 后安排服务器线程续批，保持同一队列与调度锁。只移动少量最终包发送，不把玩家/世界访问放到网络线程；线程停止时沿用原清理机制。
