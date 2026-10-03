# MineAstr 0.7.24 — Minecraft 聊天界面

本次说明仅列出相对 [上一个正式版本 0.7.23](https://github.com/YU322142/MineAstr/releases/tag/v0.7.23) 的变化。

- 游戏聊天改为左侧平台图标与发送人、右侧正文的布局。MC、Discord、QQ 使用三种平台图标，昵称使用粗体；多行文字、引用和原文在正文列对齐，长昵称限制宽度，悬停可查看完整名称。
- AstrBot 通过可选 `sender_platform` 字段上报回复对象所在平台；支持配置中的自定义 QQ / Discord 适配器 ID。机器人回复使用对应平台图标。
- 图片置于正文下方，保持比例，最大 96 × 54 聊天 GUI 像素，并根据正文宽度和可见聊天行数进一步缩小。下载、解码、缩放、写缓存在有界后台队列执行；纹理在渲染线程上传，按原版聊天滚动、裁剪和淡出显示。
- 新增可选 `mineastr:chat_presentation` 客户端通道，正文与图片预留区域先到达，图片完成后填入原位置。保持协议号 1 和旧图片分片格式；旧客户端保留原先显示方式。新客户端无需 ChatImage 即可接收内联缩略图。
- 修复 Minecraft 平台的机器人纯图片回复被丢弃；继续遵守服务端总开关和客户端 F8 图片接收设置。提示使用中英语言键，聊天历史/日志保留可读的图片名称。
- 保留原 ZIP 的 12 个异步队列、编码、超时调度和按序广播方法，以及既有服务器和图片缓存日志。缩略图提供字节/像素上限、超时、队列和纹理缓存上限，退出连接时释放资源并忽略旧任务回调。
- 实测 NeoForge 1.21.1 + ModernUI 3.13.0.1 + ModernFix 5.27.20 + ChatImage 1.4.7 的真实客户端聊天渲染、缩放与资源重载；31 项模组测试及 166 项插件测试通过。

- MC 内的用户名称统一优先使用已绑定的 Minecraft 游戏名，没有绑定时使用 QQ / Discord 用户名；平台图标仍表示消息来源。普通消息、模板、引用、编辑、撤回、@ 玩家提醒与广播同步此规则；多账号时使用最早绑定的游戏名，解绑后自动切换。昵称缓存最多 512 项、30 秒到期，绑定/解绑/迁移立即失效，登录鉴权仍读取实时绑定。

## 安装

插件和 NeoForge 模组统一为 **0.7.24**。要使用新的布局与平台信息，应同时更新 AstrBot 插件、MC 服务端及客户端的 MineAstr；未更新客户端仍可接收旧版文字/图片消息。Fabric 历史分支只同步文档，继续停止支持。

PNG、JPEG、BMP 和 GIF 首帧可由内联缩略图解码器显示；不支持的格式显示本地化失败提示。GIF 使用静态缩略图。ChatImage 的独立功能继续由该模组控制。不要在 `mods/` 同时保留多个 MineAstr JAR。

后续 Release 只说明相对上一正式 Release 的变化；完整历史保留在各分支的 CHANGELOG 中。

## ModernUI 字体兼容

昵称使用粗体。正文通过 Minecraft 的 Font、StringSplitter 和 Component 样式 API 排版，沿用 ModernUI 3.13.0.1 已接管的 TrueType/OpenType、黑体/字体回退、抗锯齿与 Unicode 渲染；不覆盖玩家的字体设置。按逻辑文字和样式分段换行，保留粗体、颜色、链接、悬停、双向文字及 Emoji，避免把视觉顺序文字再次重排。ModernFix 5.27.20 继续负责性能优化和兼容性修复。

[ModernUI 官方说明](https://github.com/BloCamLimb/ModernUI-MC) · [ModernFix 1.21.1 补丁说明](https://github.com/embeddedt/ModernFix/wiki/1.21.1-Summary-of-Patches)
