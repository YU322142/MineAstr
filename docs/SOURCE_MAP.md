# 源码职责索引 / Source responsibilities

先按职责找文件，再阅读方法。复杂算法、线程切换和持久化约束写在实现旁；简单访问器不重复逐行解释。

Find the responsible file first. Algorithm, threading and persistence constraints belong beside implementation.

| 文件 / File | 职责 / Responsibility |
| --- | --- |
| [api/MineAstrDisplayApi.java](../src/main/java/com/mineastr/api/MineAstrDisplayApi.java) | 供其他模组调用的客户端显示接口；扩展时保持消息标识和替换语义兼容。 |
| [BindingWhitelistStore.java](../src/main/java/com/mineastr/BindingWhitelistStore.java) | 完整绑定快照的原子持久化；读取失败时不使用缓存授权。 |
| [MineAstr.java](../src/main/java/com/mineastr/MineAstr.java) | NeoForge 生命周期与事件注册入口；客户端初始化必须保持专用服务端可加载。 |
| [MineAstrBindingService.java](../src/main/java/com/mineastr/MineAstrBindingService.java) | 登录决策、绑定快照与白名单对账；不拥有网络连接或命令权限。 |
| [MineAstrBotImageClient.java](../src/main/java/com/mineastr/MineAstrBotImageClient.java) | 接收机器人图片分片、校验完整性并写入缓存；断连清空未完成拼装。 |
| [MineAstrBridge.java](../src/main/java/com/mineastr/MineAstrBridge.java) | 连接生命周期与协议分发的协调入口；领域状态分别归各服务所有。 |
| [MineAstrBridgeContext.java](../src/main/java/com/mineastr/MineAstrBridgeContext.java) | 服务依赖的最小连接能力；不暴露其他领域的可变集合。 |
| [MineAstrChatAccess.java](../src/main/java/com/mineastr/MineAstrChatAccess.java) | Mixin 实现的聊天历史访问契约；替换原消息而不添加重复消息。 |
| [MineAstrChatEasing.java](../src/main/java/com/mineastr/MineAstrChatEasing.java) | 可选 ModernUI 缓动适配；缺少依赖时保留本地插值回退。 |
| [MineAstrChatGeometry.java](../src/main/java/com/mineastr/MineAstrChatGeometry.java) | 布局与缩略图共用的像素几何；纯计算，不访问渲染器或文件。 |
| [MineAstrChatIcons.java](../src/main/java/com/mineastr/MineAstrChatIcons.java) | 平台图标的独立纹理绘制；与字体图集分离。 |
| [MineAstrChatImageInteraction.java](../src/main/java/com/mineastr/MineAstrChatImageInteraction.java) | 聊天图片的鼠标交互入口；命中区域由绘制时记录的数据决定。 |
| [MineAstrChatImages.java](../src/main/java/com/mineastr/MineAstrChatImages.java) | 有界图片缓存与异步准备；纹理上传、释放及绘制使用客户端线程。 |
| [MineAstrChatInsertionMotion.java](../src/main/java/com/mineastr/MineAstrChatInsertionMotion.java) | 新消息插入的临界阻尼动画；重新定位时保持位置和速度连续。 |
| [MineAstrChatLayout.java](../src/main/java/com/mineastr/MineAstrChatLayout.java) | 聊天显示副本的换行排版；原版消息和历史记录保持原有归属。 |
| [MineAstrChatMotion.java](../src/main/java/com/mineastr/MineAstrChatMotion.java) | 与帧率无关的动画状态；可重新指定目标，不依赖游戏对象。 |
| [MineAstrChatScreen.java](../src/main/java/com/mineastr/MineAstrChatScreen.java) | 自定义聊天面板入口；沿用原版输入框、命令补全和提示。 |
| [MineAstrChatText.java](../src/main/java/com/mineastr/MineAstrChatText.java) | 聊天语言选择与文本规范化；服务端和测试共用的纯函数。 |
| [MineAstrChatTextures.java](../src/main/java/com/mineastr/MineAstrChatTextures.java) | 纹理上传及资源重载时构建 mipmap；避免每帧重复生成。 |
| [MineAstrChatViewport.java](../src/main/java/com/mineastr/MineAstrChatViewport.java) | 聊天视口绘制、滚动条及命中测试；布局和点击统一使用同一坐标系。 |
| [MineAstrClient.java](../src/main/java/com/mineastr/MineAstrClient.java) | 客户端事件和网络结果的协调入口；截图编码在线程池执行，显示回到客户端线程。 |
| [MineAstrClientConfig.java](../src/main/java/com/mineastr/MineAstrClientConfig.java) | 客户端显示与隐私偏好；保持默认值、设置页面和同步载荷一致。 |
| [MineAstrClientThemes.java](../src/main/java/com/mineastr/MineAstrClientThemes.java) | 客户端主题调色板；渐变预先计算，避免逐字形重复计算。 |
| [MineAstrCommands.java](../src/main/java/com/mineastr/MineAstrCommands.java) | 游戏内命令树及反馈格式；业务操作交给桥接服务。 |
| [MineAstrCommandService.java](../src/main/java/com/mineastr/MineAstrCommandService.java) | 受控命令、管理员可信名单和审批记录；仅在服务器线程修改。 |
| [MineAstrConfig.java](../src/main/java/com/mineastr/MineAstrConfig.java) | 服务端配置定义；连接参数、命令权限及登录策略从这里读取。 |
| [MineAstrConfigScreen.java](../src/main/java/com/mineastr/MineAstrConfigScreen.java) | 客户端设置控件及滚动页面；配置持久化与应用保持在既有客户端流程中。 |
| [MineAstrDispatchBudget.java](../src/main/java/com/mineastr/MineAstrDispatchBudget.java) | 每批至少推进一步，然后同时受数量和耗时限制。 |
| [MineAstrGif.java](../src/main/java/com/mineastr/MineAstrGif.java) | 后台解码有界 GIF 时间轴；处理帧处置、循环和像素上限。 |
| [MineAstrGifPreview.java](../src/main/java/com/mineastr/MineAstrGifPreview.java) | 检测可独立解码的首张 GIF 图像；不得尝试解码不完整 LZW 数据。 |
| [MineAstrImageJobs.java](../src/main/java/com/mineastr/MineAstrImageJobs.java) | 有界图片任务与虚拟线程；网络等待不占解码许可，下载体保留受并发上限约束。 |
| [MineAstrImageScreen.java](../src/main/java/com/mineastr/MineAstrImageScreen.java) | 大图浏览界面复用聊天纹理；缩放拖拽不重新下载或分配图片纹理。 |
| [MineAstrImageViewport.java](../src/main/java/com/mineastr/MineAstrImageViewport.java) | 图片缩放与拖拽的纯几何状态；不下载、不解码图片。 |
| [MineAstrLimits.java](../src/main/java/com/mineastr/MineAstrLimits.java) | 协议长度、速率和超时上限；改变这些值时检查兼容性。 |
| [MineAstrLocalServerConfigScreen.java](../src/main/java/com/mineastr/MineAstrLocalServerConfigScreen.java) | 本地服务器配置页面；校验输入后保存与重新连接。 |
| [MineAstrLoginTask.java](../src/main/java/com/mineastr/MineAstrLoginTask.java) | 绑定决策完成前阻止进入世界；异步结果只完成当前登录任务。 |
| [MineAstrNativeChatClient.java](../src/main/java/com/mineastr/MineAstrNativeChatClient.java) | 原生聊天预览与最终替换的客户端入口；通过历史访问接口更新已有消息。 |
| [MineAstrNativeChatService.java](../src/main/java/com/mineastr/MineAstrNativeChatService.java) | 原生聊天的即时预览、按序译文更新与预算派发；不改变网络等待机制。 |
| [MineAstrNetwork.java](../src/main/java/com/mineastr/MineAstrNetwork.java) | 载荷注册与游戏线程切换边界；客户端处理器延迟反射加载。 |
| [MineAstrPayloads.java](../src/main/java/com/mineastr/MineAstrPayloads.java) | 双端共用的载荷类型与编解码上限；修改字段须同步发送端和接收端。 |
| [MineAstrPlayerThemes.java](../src/main/java/com/mineastr/MineAstrPlayerThemes.java) | 服务端持久化玩家主题；只允许已认证玩家修改自己的 UUID。 |
| [MineAstrProtocolValues.java](../src/main/java/com/mineastr/MineAstrProtocolValues.java) | 协议输入的有界读取与媒体签名检查；无连接状态、无 I/O。 |
| [MineAstrRangeDownload.java](../src/main/java/com/mineastr/MineAstrRangeDownload.java) | HTTP 分段下载的纯校验；检测范围、响应长度和资源验证标识是否一致。 |
| [MineAstrRegionScan.java](../src/main/java/com/mineastr/MineAstrRegionScan.java) | 按列推进的区域分析；仅访问已加载区块，所有世界访问留在服务器线程。 |
| [MineAstrScreenshotConsentScreen.java](../src/main/java/com/mineastr/MineAstrScreenshotConsentScreen.java) | 截图请求的用户同意界面；接受与拒绝均回传对应请求标识。 |
| [MineAstrScreenshotService.java](../src/main/java/com/mineastr/MineAstrScreenshotService.java) | 截图能力、请求、超时与分片重组；断线和离开时统一回收。 |
| [MineAstrScrollState.java](../src/main/java/com/mineastr/MineAstrScrollState.java) | 聊天和配置页共用的连续像素滚动状态；不按行量化。 |
| [MineAstrServerWorkQueue.java](../src/main/java/com/mineastr/MineAstrServerWorkQueue.java) | 主线程上的有界、轮转工作队列；每个 Tick 共用一个预算。 |
| [MineAstrSignService.java](../src/main/java/com/mineastr/MineAstrSignService.java) | 告示牌翻译、人工覆盖与缓存修订；旧异步结果不能复活已清理缓存。 |
| [MineAstrTargets.java](../src/main/java/com/mineastr/MineAstrTargets.java) | 在线玩家和已加载维度的选择；调用者必须在服务器线程。 |
| [MineAstrThemeColor.java](../src/main/java/com/mineastr/MineAstrThemeColor.java) | 主题颜色解析和插值；输入校验与显示复用相同规则。 |
| [MineAstrTools.java](../src/main/java/com/mineastr/MineAstrTools.java) | 服务器状态、玩家与实体查询；调用方须在服务端线程访问游戏对象。 |
| [MineAstrWhitelist.java](../src/main/java/com/mineastr/MineAstrWhitelist.java) | 一次同步只建立一次名称索引；未变化的白名单绝不写盘。 |
| [mixin/MineAstrChatMixin.java](../src/main/java/com/mineastr/mixin/MineAstrChatMixin.java) | 聊天组件注入与历史替换实现；升级 Minecraft 时检查注入点和字段映射。 |
| [mixin/MineAstrChatScreenAccessor.java](../src/main/java/com/mineastr/mixin/MineAstrChatScreenAccessor.java) | 原版聊天输入框的 Mixin 访问器；字段名依赖当前游戏版本。 |
| [SignTranslationStore.java](../src/main/java/com/mineastr/SignTranslationStore.java) | 告示牌翻译及人工覆盖的持久化；指纹和策略版本控制缓存命中。 |
