# Changelog

## 0.6.29 - NeoForge 1.21.1

- 将实体翻译改为准星目标 HUD，目标丢失或切换世界时立即移除，避免残留和远距离巨型文字。
- 默认仅显示译文，不在游戏画面旁重复原文。
- 可用时通过 Create 的悬浮文本接口渲染，并保留无 Create 环境的安全回退。
- 为沉浸画框等调用方保留公共 Display API 与自定义射线目标支持。

## 0.6.28 - NeoForge 1.21.1

- 同步 MineAstr 版本号至 v0.6.28；协议与原生聊天翻译行为保持兼容。

## 0.6.27 - NeoForge 1.21.1

- Add optional native Minecraft player-chat translation with per-player locale results and ordered original-text fallback.
- Route native-chat responses to the originating connection, serialize WebSocket sends, and document the unsigned-chat/Secure Chat reporting trade-off.
- Use one server-thread dispatch queue, snapshot every online recipient (including spectators), and place bounded rate/availability fallbacks behind earlier translations so concurrent responses cannot reorder chat.
- Keep the packet body within Minecraft's 256-character limit without splitting emoji, while carrying the full translation in the unsigned display component.

## 0.6.26 - NeoForge 1.21.1

- Synchronize the NeoForge build with the AstrBot plugin v0.6.26 release.
- No Minecraft protocol or client behavior changes from 0.6.25.

## 0.6.25 - NeoForge 1.21.1

- 基于上游 MineAstr Minecraft 1.21.1 / NeoForge 21.1.219 基线完整迁移 Fabric 0.6.25 的 20 个功能提交。
- 支持按玩家 locale 显示聊天译文；译文与原文一致时只显示原文。
- 增加玩家加入、离开、结构化死亡事件、媒体链接、定向提醒、账号绑定同步、登录前绑定检查与验证码。
- 增加动态管理员同步和服务器命令申请、批准、拒绝、超时、限量及 revision 防倒序覆盖。
- 增加所有普通、墙上和悬挂告示牌的准星 HUD 翻译；直接复用原版命中结果，不执行额外射线检测。
- 告示牌缓存持久化到世界 `data/mineastr_sign_translations.dat`，支持双语同义跳过、人工译文、按语言清理和全世界清理。
- 增加外部图片翻译和统一显示 API，供沉浸画框等客户端 Mod 调用 AstrBot 多模态模型。
- F8 界面增加游戏翻译、原文显示、告示牌/画框浮选、距离和缩放选项。
- 复用画框 HUD 的渲染变换栈，避免多个浮选条目在每帧产生额外分配。
- 保留 NeoForge 1.21.1 原有截图、TOML 配置、独服无 GUI 和 optional 客户端协议。
- 增加 11 项 JUnit 测试，覆盖缓存持久化、策略升级、人工优先、双语跳过、清理和旧响应失效。
- 发布构建同时提供源码 JAR，并在主 JAR 与源码 JAR 中附带许可证、作者和第三方声明。

## 0.4.1 - Upstream baseline

- 上游 Hgit-1/MineAstr 的 Minecraft 1.21.1 NeoForge 基线。
