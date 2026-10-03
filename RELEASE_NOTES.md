# MineAstr 0.7.26 — 修复 Showcase Item 聊天绘制冲突

本次仅记录相对 [上一正式版本 0.7.25](https://github.com/YU322142/MineAstr/releases/tag/v0.7.25) 的变化。

- 修复客户端同时安装 MineAstr 0.7.25 与 Showcase Item 1.21.1-1.1.0 时，双方接管同一个聊天文字绘制调用导致 Mixin 注入失败、游戏启动崩溃的问题。
- 聊天文字绘制改为可组合的 MixinExtras WrapOperation，并调用原有操作。Showcase Item 的物品渲染与 MineAstr 的主题色、淡入及整个聊天队列连续上移共同保留；没有跳过必需注入或关闭动画。
- 使用整合包中的 Showcase Item + ModernUI 3.13.0.1 + ModernFix 5.27.20 + ChatImage 1.4.7 进行实际客户端回归，验证原物品绘制钩子仍被调用，以及滚动、悬停、主题色、F8 设置和资源重载。52 项模组测试与 166 项插件测试通过；原 ZIP 的 12 个异步优化方法及既有日志保留。

插件与 NeoForge 模组统一为 **0.7.26**；插件本次仅同步版本和文档，协议号仍为 1。客户端需要更新修复包。Fabric 历史分支继续停止支持，仅同步发布文档。
