# MineAstr 开发交接 / Developer Handoff

本文按文件记录当前 0.6.30 代码的职责、协议边界和后续 TODO。源码行为以 NeoForge 1.21.1 分支为准。

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
| `src/main/java/com/mineastr/MineAstrConfigScreen.java` | F8 设置界面；ChatImage 不存在时图片开关保持不可用。 |
| `src/main/java/com/mineastr/MineAstrBotImageClient.java` | Bot 图片分片重组、SHA-256 校验、缓存清理和 ChatImage CICode 显示。 |
| `src/main/resources/assets/mineastr/lang/zh_cn.json` | 中文界面和提示文本。 |
| `src/main/resources/assets/mineastr/lang/en_us.json` | English 等价界面和提示文本。 |
| `src/main/templates/META-INF/neoforge.mods.toml` | Mod 元数据及 ChatImage 的可选客户端依赖声明。 |
| `src/test/java/com/mineastr/MineAstrBridgeTest.java` | 桥接协议、图片输入校验和安全边界测试。 |
| `build.gradle` | NeoForge 构建、测试和发布产物配置。 |
| `gradle.properties` | Minecraft、NeoForge 和 MineAstr 版本；本次为 `0.6.30`。 |
| `README.md` / `README.en.md` | 面向使用者的中文/英文安装与配置说明。 |
| `docs/CONFIGURATION.zh-CN.md` / `docs/CONFIGURATION.en.md` | 配置项级参考。 |
| `CHANGELOG.zh-CN.md` / `CHANGELOG.md` | 双语版本变更记录。 |
| `THIRD_PARTY_NOTICES.md` | 第三方依赖、ChatImage 联动说明；仓库不打包 ChatImage。 |

## 图片联动边界

- AstrBot 只向 Minecraft 发送公共 `http(s)` 图片地址，或经过格式、大小和 SHA-256 校验的内联图片。
- 本地路径、`file://` 路径和失败的图片数据不会进入游戏聊天。
- 服务端只向同时上报 `chatimage` 能力且在 F8 开启接收的客户端定向发送图片。
- 客户端没有 ChatImage 时，消息仍可显示文字或无路径的 `[图片]` 标记，不会显示原始路径。

## TODO

- [ ] 在真实 NeoForge 客户端安装 ChatImage，验证 PNG/JPEG/WEBP 分片显示和缓存过期。
- [ ] 增加协议版本协商，允许未来图片传输字段平滑扩展。
- [ ] 为高延迟连接增加发送队列指标，避免大量图片占用主线程日志。
- [ ] 为服务端管理员增加图片大小/数量运行时监控。
- [ ] 每次改动 payload 后同步更新 `PROTOCOL.md` 与中英文变更日志。

## English summary

The file table above is the source-of-truth handoff map. `MineAstrBridge.java` owns server-side validation and routing; `MineAstrBotImageClient.java` owns client-side reassembly, hashing, cache retention and ChatImage rendering; `MineAstrPayloads.java` is the protocol schema. Bot images are optional, capability-gated and path-free. The TODO list above is intentionally kept small and actionable.
