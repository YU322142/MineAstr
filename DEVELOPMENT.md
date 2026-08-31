# MineAstr AstrBot 插件开发交接 / Developer Handoff

本文按文件说明 0.6.30 插件的职责和后续 TODO，避免接手者依赖目录猜测。

## 文件职责

| 文件 | 作用 |
| --- | --- |
| `main.py` | 插件入口、配置迁移、QQ/Discord/Minecraft 双向桥接、翻译、术语库和 Bot 回复图片准备。 |
| `minecraft_adapter.py` | AstrBot Minecraft 平台适配器、WebSocket 生命周期、协议 JSON 和出站图片链路。 |
| `mineastr_glossary.py` | JSON 术语库加载、命中召回及翻译提示词拼接。 |
| `aqqbot_compat.py` | 旧 AQQBot 配置、过滤器、绑定数据和命令兼容层。 |
| `_conf_schema.json` | AstrBot WebUI 配置定义；图片开关、内联上限和单条图片数量在此展示。 |
| `metadata.yaml` | 插件版本、入口和仓库元数据。 |
| `scripts/package_plugin.py` | 生成首项为顶层目录的 AstrBot ZIP 安装包。 |
| `scripts/build_translation_glossary.py` | 从语言文件生成可导入的中英术语 JSON。 |
| `tests/test_minecraft_protocol.py` | WebSocket、出站 JSON、认证和媒体字段测试。 |
| `tests/test_z_discord_automation.py` | 主插件配置、Discord/QQ 转发和媒体安全测试。 |
| `tests/test_glossary.py` / `test_glossary_builder.py` | 术语库加载、召回和生成测试。 |
| `README.md` / `PROTOCOL.md` | 安装、配置与 MineAstr 协议说明。 |
| `CHANGELOG.md` | 双语版本变更记录。 |
| `THIRD_PARTY_NOTICES.md` | ChatImage 联动说明及第三方声明；插件不再分发 ChatImage。 |

## 图片链路

`AstrBot MessageChain/Image` → `MineAstrPlugin._game_media_payloads` → `MinecraftPlatformAdapter.send_chat` → MineAstr `chat.media`。

公共 HTTPS 地址保留为地址；本地文件或 base64 只在大小、真实文件签名和 SHA-256 校验通过后转换为内联字段。任何本地路径都不会写入游戏聊天。客户端没有 ChatImage、服务端关闭总开关或玩家关闭 F8 接收时，图片被安全忽略，文字链路仍可用。

## TODO

- [ ] 通过 AstrBot 真实 `MessageEventResult` 发送本地图片、URL 图片和图片-only 回复。
- [ ] 增加图片转发统计（跳过原因、字节数、数量），但日志不得包含本地路径。
- [ ] 为失败的公共 URL 增加可选的 HEAD/Content-Type 预检，默认仍保持不阻塞聊天。
- [ ] 完善旧 AstrBot 版本对 `Image` 组件的兼容回归测试。
- [ ] 修改协议字段时同步 MineAstr Java payload、`PROTOCOL.md` 和插件 CHANGELOG。

## English summary

`main.py` owns plugin orchestration and safe media preparation; `minecraft_adapter.py` owns WebSocket transport; `_conf_schema.json` owns the WebUI controls; packaging and tests are isolated in `scripts/` and `tests/`. Local paths are never exposed to Minecraft. The TODO list is the intended next work queue.
