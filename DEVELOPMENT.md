# MineAstr 开发交接 / Developer Handoff

0.7.22 修复客户端加载画面崩溃。协议保持版本 1，可连接现有 0.7.21 服务端与插件；此修复只需替换客户端 JAR，无需重启生产服务。

本文按文件记录当前 0.7.22 代码的职责、协议边界和后续 TODO。源码行为以 NeoForge 1.21.1 分支为准。

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
| `gradle.properties` | Minecraft、NeoForge 和 MineAstr 版本；本次为 `0.7.22`。 |
| `README.md` / `README.en.md` | 面向使用者的中文/英文安装与配置说明。 |
| `docs/CONFIGURATION.zh-CN.md` / `docs/CONFIGURATION.en.md` | 配置项级参考。 |
| `CHANGELOG.zh-CN.md` / `CHANGELOG.md` | 双语版本变更记录。 |
| `THIRD_PARTY_NOTICES.md` | 第三方依赖、ChatImage 联动说明；仓库不打包 ChatImage。 |

## 图片联动边界

- AstrBot 只向 Minecraft 发送公共 `http(s)` 图片地址，或经过格式、大小和 SHA-256 校验的内联图片。
- 本地路径、`file://` 路径和失败的图片数据不会进入游戏聊天。
- 服务端只向同时上报 `chatimage` 能力且在 F8 开启接收的客户端定向发送图片。
- 客户端没有 ChatImage 时，消息仍可显示文字或无路径的 `[图片]` 标记，不会显示原始路径。

`MineAstrClientStartupTest.java` 验证配置未加载时的加载画面 Tick、外部接口与偏好发送，以及配置加载/卸载后的设置切换。

## TODO

0.7.22 完整 `test build` 与 21 项测试通过。`connectWebSocket` 为 Upgrade 添加 10 秒超时并对同步/异步失败使用同一安全日志消息；不应把网络等待搬到主线程。ZIP 中的发送队列、截图后台编码和聊天按序派发等 12 个方法内容保持一致，已有运行日志保留。完整累计变化见 [发布说明](RELEASE_NOTES.md)。

- [ ] 在真实 NeoForge 客户端安装 ChatImage，验证 PNG/JPEG/WEBP 分片显示和缓存过期。
- [ ] 增加协议版本协商，允许未来图片传输字段平滑扩展。
- [ ] 为高延迟连接增加发送队列指标，避免大量图片占用主线程日志。
- [ ] 为服务端管理员增加图片大小/数量运行时监控。
- [ ] 每次改动 payload 后同步更新 `PROTOCOL.md` 与中英文变更日志。

## English summary

The file table above is the source-of-truth handoff map. `MineAstrBridge.java` owns server-side validation and routing; `MineAstrBotImageClient.java` owns client-side reassembly, hashing, cache retention and ChatImage rendering; `MineAstrPayloads.java` is the protocol schema. Bot images are optional, capability-gated and path-free. The TODO list above is intentionally kept small and actionable.
