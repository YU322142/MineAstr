# MineAstr AstrBot 插件开发交接 / Developer Handoff

0.7.24 重写 Minecraft 聊天显示：MC / Discord / QQ 图标、发送人和正文分列，机器人回复沿用提问者的平台，图片使用有界异步内联缩略图。保留 0.7.24 的翻译、登录绑定、ZIP 异步优化及原有日志。插件与模组统一版本，Fabric 继续停止支持。

本文按文件说明 0.7.24 插件的职责和后续 TODO，避免接手者依赖目录猜测。

## 文件职责

| 文件 | 作用 |
| --- | --- |
| `main.py` | 插件入口、配置迁移、QQ/Discord/Minecraft 双向桥接、正文/引用分别翻译、跨平台 Bot 回复与媒体准备。 |
| `minecraft_adapter.py` | AstrBot Minecraft 平台适配器、WebSocket 生命周期、协议 JSON 和出站图片链路。 |
| `mineastr_glossary.py` | JSON 术语库加载、命中召回及翻译提示词拼接。 |
| `aqqbot_compat.py` | 旧 AQQBot 配置、过滤器、绑定数据和命令兼容层。 |
| `_conf_schema.json` | AstrBot WebUI 配置定义；图片开关、内联上限和单条图片数量在此展示。 |
| `metadata.yaml` | 插件版本、入口和仓库元数据。 |
| `scripts/package_plugin.py` | 生成首项为顶层目录的 AstrBot ZIP 安装包。 |
| `scripts/build_translation_glossary.py` | 从语言文件生成可导入的中英术语 JSON。 |
| `tests/test_minecraft_protocol.py` | WebSocket、出站 JSON、认证和媒体字段测试。 |
| `tests/test_z_discord_automation.py` | 主插件配置、Bot 跨平台/不回环、正文与引用混合语言、Discord/QQ 转发和媒体回归测试。 |
| `tests/test_glossary.py` / `test_glossary_builder.py` | 术语库加载、召回和生成测试。 |
| `README.md` / `PROTOCOL.md` | 安装、配置与 MineAstr 协议说明。 |
| `CHANGELOG.md` | 双语版本变更记录。 |
| `THIRD_PARTY_NOTICES.md` | ChatImage 联动说明及第三方声明；插件不再分发 ChatImage。 |

## 图片链路

`AstrBot MessageChain/Image` → `MineAstrPlugin._game_media_payloads` → `MinecraftPlatformAdapter.send_chat` → MineAstr `chat.media`。

公共 HTTPS 地址保留为地址；本地文件或 base64 只在大小、真实文件签名和 SHA-256 校验通过后转换为内联字段。任何本地路径都不会写入游戏聊天。客户端没有图片渲染能力、服务端关闭总开关或玩家关闭 F8 接收时，图片被安全忽略，文字链路仍可用。

## TODO

本版 166 项单元测试通过；真实 NeoForge + ModernFix + ChatImage 客户端已验证渲染与资源重载。[本次发布说明](RELEASE_NOTES.md) 记录两侧统一版本和本次变化。

- [ ] 通过 AstrBot 真实 `MessageEventResult` 发送本地图片、URL 图片和图片-only 回复。
- [ ] 增加图片转发统计（跳过原因、字节数、数量），但日志不得包含本地路径。
- [ ] 为失败的公共 URL 增加可选的 HEAD/Content-Type 预检，默认仍保持不阻塞聊天。
- [ ] 完善旧 AstrBot 版本对 `Image` 组件的兼容回归测试。
- [ ] 修改协议字段时同步 MineAstr Java payload、`PROTOCOL.md` 和插件 CHANGELOG。

## English summary

`main.py` owns plugin orchestration and safe media preparation; `minecraft_adapter.py` owns WebSocket transport; `_conf_schema.json` owns the WebUI controls; packaging and tests are isolated in `scripts/` and `tests/`. Local paths are never exposed to Minecraft. The TODO list is the intended next work queue.

## 0.7.24 平台显示

`_game_chat_platform` 从通知配置的自定义平台 ID 及适配器元信息判断平台；适配器回传 `sender_platform`，机器人回复沿用事件 origin。MinecraftPlatformEvent.send 也调用既有异步媒体准备器，使纯图片回复能通过图片接收策略。166 项测试包含平台映射、译文与媒体保留、Minecraft 图片回复。

新 Release 只比較上一正式发布版本；历史只放 CHANGELOG。

## ModernUI 字体兼容

昵称使用粗体。正文通过 Minecraft 的 Font、StringSplitter 和 Component 样式 API 排版，沿用 ModernUI 3.13.0.1 已接管的 TrueType/OpenType、黑体/字体回退、抗锯齿与 Unicode 渲染；不覆盖玩家的字体设置。按逻辑文字和样式分段换行，保留粗体、颜色、链接、悬停、双向文字及 Emoji，避免把视觉顺序文字再次重排。ModernFix 5.27.20 继续负责性能优化和兼容性修复。

[ModernUI 官方说明](https://github.com/BloCamLimb/ModernUI-MC) · [ModernFix 1.21.1 补丁说明](https://github.com/embeddedt/ModernFix/wiki/1.21.1-Summary-of-Patches)

游戏昵称仅由 `_game_sender_name` / `_game_reply_context` 用 owner_key 查询绑定，不按昵称猜测账号。BindingStore.display_player_name 以最早绑定为准，最多 512 项、30 秒 TTL；冷读通过 asyncio.to_thread 和绑定写锁合并，bind/unbind/migrate 即时失效。缓存只服务显示，登录与白名单查询仍读取数据库。普通消息、引用、Discord 编辑、撤回和 /mc say 使用同一解析器，跨平台正文保持社交昵称。
