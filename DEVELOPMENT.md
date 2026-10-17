# MineAstr 0.8 开发说明 / Developer guide

当前目标为 Minecraft 1.21.1 / NeoForge 21.1.219 / Java 21，协议号为 1。版本由 `gradle.properties` 中的 `mod_version` 声明；`MineAstr.MOD_VERSION` 是握手版本，修改版本时须同步核对两处。

## 维护入口 / Maintenance entry

完整文件职责、线程边界、1.21.1 反射访问、卡顿修复机制及生产排查步骤见 [维护说明](docs/MAINTENANCE.md)。配置见 [中文](docs/CONFIGURATION.zh-CN.md) / [English](docs/CONFIGURATION.en.md)，网络字段见 [协议](PROTOCOL.md)。

- 服务端路由：`MineAstrBridge.java`；白名单与存储：`MineAstrWhitelist.java`、`BindingWhitelistStore.java`。
- 服务端领域逻辑：`MineAstrBindingService`、`MineAstrCommandService`、`MineAstrScreenshotService`、`MineAstrSignService`、`MineAstrNativeChatService`；完整职责见 [源码索引](docs/SOURCE_MAP.md)。
- 耗时任务：`MineAstrServerWorkQueue.java`；区域统计：`MineAstrRegionScan.java`。
- 客户端入口与配置：`MineAstrClient.java`、`MineAstrClientConfig.java`、`MineAstrConfigScreen.java`。
- 聊天布局与交互：`MineAstrChatLayout`、`MineAstrChatViewport`、`MineAstrChatScreen`；图片资源：`MineAstrChatImages`、`MineAstrBotImageClient`。
- 网络：`MineAstrNetwork.java`、`MineAstrPayloads.java`；改变字段时同步协议与旧客户端兼容测试。

All world, player and whitelist state belongs to the server thread. Keep network waits and image decoding asynchronous. The bounded tick queue owns incremental region work; whitelist persistence is isolated in a version-specific helper. See the maintenance guide for the compatibility and failure boundaries.

## 构建与测试 / Build and test

Windows：`gradlew.bat test build`；Linux/macOS：`./gradlew test build`。使用 Java 21，构建结果位于 `build/libs/`，带 `-sources` 的 JAR 仅为源码包。0.8 已通过 101 项 JUnit 测试。

## 必须保留的客户端边界 / Client invariants

- 图片下载、解码与转换留在后台；纹理上传留在客户端线程，许可和过期代际检查须成对释放。
- ModernUI 字体排版与原版 Component 样式、链接、悬停和屏蔽设置保持通过 Minecraft API 处理。
- 聊天绘制 Mixin 使用 `WrapOperation` 调用原绘制，保留 Showcase Item 等模组的已有钩子。
- 原生聊天用相同消息 ID 更新译文；清空、淘汰或过期的聊天记录不得被旧回调复活。
- 配置与资源重载后重新布局，保留有效动画和图片生命周期；不要在渲染路径同步请求网络。

Keep image decoding off the render thread, preserve font and Component APIs, and retain existing draw hooks. Translation updates must not recreate cleared history. Production profiling remains separate from unit/build validation.
