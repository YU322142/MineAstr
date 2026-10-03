# MineAstr 0.7.25 配置参考

本文只描述当前配置。历史字段变化见 [`CHANGELOG.md`](../CHANGELOG.md)。

## 配置文件

| 文件 | 作用 |
| --- | --- |
| `config/mineastr-common.toml` | 服务端桥接、工具、绑定和权限 |
| `config/mineastr-client.toml` | 客户端翻译 HUD、距离、缩放和截图策略 |

独立服务端只读取 common 配置。单人世界若启用本地桥接，也使用客户端实例中的 common 配置。

## 桥接基础项

| 键 | 建议值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启动桥接 |
| `websocketUrl` | 本地或受控地址 | AstrBot Minecraft 适配器 WebSocket |
| `token` | 随机长字符串 | 两端必须完全一致，禁止提交真实值 |
| `serverId` | 稳定短标识 | 多服务器环境中的唯一 ID |
| `serverName` | 服务器显示名 | 用于日志和机器人上下文 |
| `botDisplayName` | `AstrBot` | 游戏内机器人名称 |
| `reconnectSeconds` | `5` | 断线重连间隔 |
| `maxMessageLength` | `1000` | 转发聊天长度上限 |
| `enableBotImageMessages` | `true` | 服务端是否允许向具备图片渲染能力且主动接受的客户端下发 Bot 图片 |

远程 AstrBot 应通过受控网络、TLS 终结或可信反向代理暴露，不要把管理接口直接公开到互联网。

## 查询工具

玩家状态、背包摘要、附近实体和已加载区域特征可分别开关。区域工具不应主动加载新区块，也不返回容器内容、告示牌原文或完整方块实体 NBT。

## 命令工具

`enableCommandTool` 默认必须保持 `false`。确需启用时，同时配置强随机 Token、最小可信用户列表、最小命令规则和可审计的审批策略。不要使用单独的 `"*"` 规则，除非明确接受远程执行任意服务器命令的风险。

## 绑定和登录检查

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enableBindingSync` | `false` | 同步 MineAstr 绑定关系 |
| `bindingSyncWhitelist` | `false` | 把绑定结果同步到原版白名单 |
| `loginBindingCheckEnabled` | `false` | 登录前检查绑定 |
| `loginCheckFailOpen` | `false` | AstrBot 不可用时是否放行 |
| `generateBindingCodeOnReject` | `true` | 拒绝时生成一次性验证码 |

白名单同步和登录检查是独立功能，启用前应明确账号恢复与 AstrBot 故障策略。

## 客户端翻译

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `localWorldServerEnabled` | `false` | 单人集成服务器是否连接 AstrBot |
| `gameTranslationsEnabled` | `true` | 游戏翻译总开关 |
| `showOriginalTranslatedMessages` | `true` | 普通聊天是否同时显示原文 |
| `receiveImageMessages` | `true` | 图片接收偏好；F8 保存时同步更新旧开关 |
| `acceptBotImages` | `true` | 旧图片接收开关；为兼容原有关闭设置，两个开关都开启才接收图片 |
| `openConfigKeyEnabled` | `true` | 是否允许通过注册的 F8 快捷键打开设置 |
| `signTranslationsEnabled` | `true` | 准星目标浮选总开关 |
| `signTranslationMaxDistance` | `8` | 浮选最大距离 |
| `signTranslationScale` | `1.0` | 浮选缩放 |

`showOriginalTranslatedMessages` 只控制普通聊天。0.7.25 的目标 HUD 默认只显示译文。

Bot 端另有 `bridge_settings.relay_images_to_game` 总开关、`game_image_inline_max_bytes` 本地图片内联总上限和 `game_image_max_items` 单条消息图片数上限。关闭任意一端的开关都只停止图片，不影响文字桥接。图片路径和 URL 不会作为普通聊天文本显示。

图片偏好独立于 ChatImage 安装状态保存；新版客户端自带内联缩略图；旧客户端仍需 ChatImage。`/mineastr-images on|off` 可直接修改当前玩家的服务端偏好。

F8 页面和本地服务端子页面的文字通过 `screen.mineastr.*` 翻译键显示，包括按钮、说明、选项、数值单位和连接提示。多语言适配可在 `assets/mineastr/lang/<语言代码>.json` 新增语言文件，或用资源包覆盖同名键；现有 `zh_cn.json`、`en_us.json` 可作为模板，`%s` 参数必须保留，百分号写作 `%%`。

## 截图

`screenshotMode` 可设为 `ASK`、`AUTO` 或 `DISABLED`。公共整合包建议保留 `ASK`。宽度、高度、JPEG 质量和编码大小上限可分别配置。

## 配置变更流程

1. 停止服务端或确认对应配置允许热加载。
2. 备份 TOML。
3. 只修改目标键，不整文件覆盖玩家本地偏好。
4. 检查重复键和 TOML 语法。
5. 重启后运行 `/mineastr status` 并检查日志。

通过 MCSync 发布配置 OTA 时，优先使用精确键级补丁；Token 和私有地址必须继续由本地配置提供。

强制账号绑定：AstrBot 开启 `binding_enabled`、`need_bind_to_login`，服务端开启 `loginBindingCheckEnabled` 并设 `loginCheckFailOpen=false`。0.7.25 在 NeoForge 实际执行的配置任务中异步检查，完成前不得进入世界；旧 `PlayerNegotiationEvent` 不再使用。已有 TOML 值不会自动迁移，须检查故障放行策略。白名单同步不等于白名单已启用。

翻译模型须使用提供商实际的 API Base URL。若 OpenAI 兼容网关根地址返回 HTML 而 `/v1` 提供 API，应配置以 `/v1` 结尾的地址；插件不猜测或自动修改第三方路径。日志 `no_translation` 表示本次返回没有有效译文，`translated` 表示返回了译文；按客户端偏好显示。

## F8 聊天动画、高清图片与高度限制

| 配置键 | 默认 | 可选范围 / 作用 |
| --- | --- | --- |
| `chatImageScale` | 100 | 50–300%，自动受正文宽度和可见区域限制 |
| `chatMaxHeightPercent` | 0 | 0 跟随 Minecraft；1–100% 限制可用屏幕高度，不扩大原版高度设置 |
| `chatAnimationsEnabled` | true | 关闭时立即滚动、立即显示消息 |
| `chatScrollDuration` | 180 | 80–500 毫秒，ModernUI 三次减速滚动 |
| `chatArrivalDuration` | 200 | 80–500 毫秒，新消息淡入及整队消息上移的时长尺度 |
| `chatArrivalDistance` | 4 | 0–12 GUI 像素，新消息额外轻移幅度 |

高度会计入 GUI / 聊天缩放并至少保留一行。图片默认保持小尺寸（最高 96×54 聊天 GUI 像素），F8 可放大；原图最长边保留到 1024 像素。保存后重排历史，不重新下载图片。更大显示尺寸不会突破缓存、字节和像素上限；平台图标使用独立高清纹理。

## 玩家主题色

| Key | Default | Range |
| --- | --- | --- |
| `playerThemeColor` | `16777215` (#FFFFFF) | RGB24; contrast ≥ 4.5:1 against #303030 |
| `playerThemeSecond` | `7530177` (#72E6C1) | Same restriction when enabled |
| `playerThemeThird` | `11909375` (#B5B8FF) | Same restriction when enabled |
| `playerThemeStops` | `1` | 1 solid / 2 or 3 animated colors |
| `playerThemePeriod` | `8000` | 4000–20000 ms |

F8 可以通过 RGB 滑块与 #RRGGBB 输入修改每个启用的颜色并预览渐变。过暗或未完成的输入会禁止保存，不自动调整颜色。昵称和正文着色，左侧图标与图片不着色。服务器按 UUID 随世界保存主题，并同步历史主题；重连以该服务器已保存的主题为准。颜色不会更改消息内容、日志或原有字体。对比度以深色聊天背景为参考，完全透明背景下的任意世界画面无法通过颜色本身保证对比度。
