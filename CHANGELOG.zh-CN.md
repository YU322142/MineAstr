# 更新日志

[English](CHANGELOG.md)

社区 Fork：[YU322142/MineAstr](https://github.com/YU322142/MineAstr) · 原始上游：[Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr) · 联动 Fork：[YU322142/ImmersivePaintings](https://github.com/YU322142/ImmersivePaintings)

## 0.6.30 - NeoForge 1.21.1

- 新增与 ChatImage 的可选客户端联动，AstrBot 图片可直接显示在 Minecraft 聊天中。
- 客户端只有安装 ChatImage 且在 F8 中开启图片接收后才声明能力；没有 ChatImage 时不接收图片包。
- 新增 Bot 端图片总开关、内联大小上限和单条消息图片数上限；关闭图片不影响文字桥接。
- Bot 本机临时图片改为有界内联数据，公网图片使用受限 HTTP(S) 地址；不再把 Bot 本机路径或图片 URL 显示为普通聊天正文。
- 服务端验证图片类型、大小和 SHA-256，并以 24 KiB 有界分块定向发送给已同意的客户端。
- 客户端再次验证并缓存图片，使用 ChatImage CICode 渲染；缓存默认保留 7 天。
- 未安装 ChatImage 或关闭图片接收的玩家只保留正常文字消息；纯图片消息最多显示不含路径的 `[图片]` 占位。

## 0.6.29 - NeoForge 1.21.1

- 将实体的世界空间文字改为准星目标 HUD。
- 目标丢失或切换世界时立即移除当前译文，避免残留和远距离巨型文字。
- 默认只显示译文，不在游戏画面旁重复原文。
- 可用时使用 Create 的悬浮文本渲染器，并保留无 Create 环境的安全回退。
- 保留公共显示 API 和自定义射线目标支持，供沉浸画框等集成调用。
- 与社区维护的 Immersive Paintings Fork 配对联动；该功能不是上游官方联动。

## 0.6.28 - NeoForge 1.21.1

- 同步 MineAstr 发布版本号至 0.6.28。
- 协议和原生聊天翻译行为继续兼容 0.6.27。

## 0.6.27 - NeoForge 1.21.1

- 新增可选的 Minecraft 玩家原生聊天翻译，并按每位玩家的 locale 返回译文；失败时按原顺序回退原文。
- 把原生聊天响应路由回原连接，并串行化 WebSocket 发送。
- 明确记录未签名聊天与 Secure Chat 举报链路之间的取舍。
- 使用单一服务端线程分发队列，并覆盖所有在线接收者，包括旁观者。
- 加入有界速率和可用性回退，避免并发响应打乱聊天顺序。
- 在不拆分 emoji 的前提下把数据包正文控制在 Minecraft 256 字符限制内，完整译文仍通过未签名显示组件发送。

## 0.6.26 - NeoForge 1.21.1

- 将 NeoForge 构建与 AstrBot 插件 0.6.26 发布版同步。
- Minecraft 协议和客户端行为与 0.6.25 保持一致。

## 0.6.25 - NeoForge 1.21.1

- 基于 Minecraft 1.21.1 / NeoForge 21.1.219 基线迁移 Fabric 0.6.25 功能集。
- 支持按玩家 locale 显示聊天译文；译文与原文一致时不重复显示。
- 增加玩家加入、离开、结构化死亡、媒体链接、定向提醒、账号绑定同步、登录前绑定检查和验证码事件。
- 增加动态命令管理员同步，以及命令申请、批准、拒绝、超时、容量和 revision 防倒序保护。
- 增加普通、墙上和悬挂告示牌的准星 HUD 翻译，直接使用 Minecraft 已有命中结果。
- 把告示牌译文持久化到 `data/mineastr_sign_translations.dat`，支持双语同义跳过、人工译文、按语言清理和全世界清理。
- 增加外部图片翻译与统一显示 API，供客户端集成使用。
- F8 设置增加游戏翻译、原文、目标浮选、距离和缩放选项。
- 复用浮选变换栈，减少每帧分配。
- 保留 NeoForge 截图流程、TOML 配置、独立服务端无 GUI 和可选客户端协议。
- 增加持久化、策略升级、人工优先、双语跳过、清理和旧响应拒绝测试。
- 发布构建附带源码、许可证、作者和第三方声明。

## 0.4.1 - 上游基线

- Hgit-1/MineAstr 的 Minecraft 1.21.1 NeoForge 原始基线。
