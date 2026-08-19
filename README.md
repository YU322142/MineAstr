# MineAstr 0.6.29

[English](README.en.md) · [配置参考](docs/CONFIGURATION.zh-CN.md) · [变更日志](CHANGELOG.md) · [外部翻译 API](EXTERNAL_TRANSLATION_API.md)

MineAstr 是面向 Minecraft 1.21.1 / NeoForge 的 AstrBot 桥接模组。它把聊天、事件和受控查询送到 AstrBot，并根据每位玩家的客户端语言显示翻译结果。

| 项目 | 要求 |
| --- | --- |
| MineAstr | `0.6.29` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.219` 或更高 |
| Java | `21` |
| 运行侧 | 客户端与服务端 |

## 它负责什么

- 桥接聊天、加入、离开和结构化死亡事件。
- 按客户端语言显示翻译后的聊天内容。
- 为告示牌、实体和外部图片提供准星目标译文 HUD。
- 向 AstrBot 提供服务器、玩家、背包、附近实体和区域特征查询。
- 在玩家明确允许时提供低清晰度截图。
- 提供账号绑定、可选白名单同步和严格受控的命令工具。

MineAstr 不负责替换模组文件、同步客户端目录或保存画作图片；这些分别属于 MCSync 和对应内容模组的职责。

## 部署拓扑

```text
Minecraft 客户端
  └─ MineAstr：语言、HUD、准星目标、截图授权
        ⇅ NeoForge 自定义网络
Minecraft 服务端
  └─ MineAstr：事件、查询、权限、WebSocket 桥接
        ⇅ WebSocket
AstrBot
```

客户端不是普通聊天桥接的硬前提，但按玩家语言显示译文、准星目标 HUD、截图和沉浸画框图片翻译都要求客户端安装同版本 MineAstr。

## 安装

1. 把 `mineastr-neoforge-1.21.1-0.6.29.jar` 放入服务端 `mods/`。
2. 把同一个 JAR 放入参与翻译功能的客户端 `mods/`。
3. 首次启动后编辑服务端 `config/mineastr-common.toml`。
4. 在 AstrBot 的 Minecraft 适配器中设置相同的 WebSocket 路径和 Token。
5. 重启服务端，并用 `/mineastr status` 检查连接状态。

最小配置：

```toml
enabled = true
websocketUrl = "ws://127.0.0.1:8765/ws"
token = "CHANGE_ME_LOCAL_ONLY"
serverId = "minecraft"
```

真实地址和 Token 只应保存在运行环境，不要提交到公开仓库。完整字段见 [配置参考](docs/CONFIGURATION.zh-CN.md)，可复制的示例位于 [`examples/`](examples/)。

## 翻译显示行为

0.6.29 将告示牌、实体和沉浸画框统一为“当前准星目标”生命周期：

- 只在目标仍然有效时显示译文。
- 移开准星、打开界面、隐藏 HUD、切换世界或目标失效时立即清理。
- 目标 HUD 默认只显示译文，不在游戏画面旁重复原文。
- 优先复用 Create 风格悬浮文本渲染；Create 不可用时使用安全后备渲染。

普通聊天的“是否同时显示原文”是独立设置，不影响目标 HUD。

启用原生聊天翻译后，MineAstr 会用未签名消息重发译文，因此不保留完整的 Secure Chat 举报链路。如果服务器需要原版签名和过滤语义，应在 AstrBot 策略中关闭原生聊天翻译。

## 与沉浸画框联动

图片翻译要求：

- 客户端与服务端均安装 MineAstr `0.6.29`。
- 客户端与服务端均安装 Immersive Paintings `0.7.15+1.21.1`。
- AstrBot 桥接已连接并支持图片翻译。
- 客户端开启游戏翻译和悬浮翻译。

沉浸画框从自身缓存取得完整图片，压缩后调用 MineAstr 的公共图片翻译 API。MineAstr 负责请求和 HUD，画作原图仍由 Immersive Paintings 管理。

## 常用命令

| 命令 | 用途 |
| --- | --- |
| `/mineastr status` | 查看连接状态 |
| `/mineastr reconnect` | 立即重连 AstrBot |
| `/mineastr sign-translation status` | 查看当前告示牌缓存状态 |
| `/mineastr sign-translation set <locale> <translation>` | 写入人工译文 |
| `/mineastr sign-translation clear [locale]` | 清除当前告示牌缓存 |
| `/mineastr sign-translation clear-all` | 清空当前世界告示牌缓存，要求权限等级 4 |

## 安全默认值

- 部署前必须替换生成的 `change-me` Token。
- `enableCommandTool = false`：命令工具默认关闭。
- 截图策略默认 `ASK`：每次请求由玩家确认。
- 单人世界桥接默认关闭；启用后只影响本地集成服务器。
- 绑定同步、白名单同步和登录前绑定检查应按实际需求分别启用。

## 快速排错

| 现象 | 优先检查 |
| --- | --- |
| 日志显示“已被配置禁用” | 活动 `mineastr-common.toml` 的 `enabled` |
| 一直未连接 | `websocketUrl`、AstrBot 监听地址、防火墙和 Token |
| 告示牌正常、画作不翻译 | Immersive Paintings 0.7.15，客户端是否同样安装 MineAstr 0.6.29 |
| 移开准星仍显示 | 客户端是否混装旧 MineAstr 或旧画框 JAR |
| 图片请求没有结果 | AstrBot 图片能力和客户端完整图缓存 |
| 单人世界不连接 | `localWorldServerEnabled` 是否启用 |
| 服务端能连接但客户端无 HUD | 客户端版本、浮选开关和双方网络兼容性 |

排错时先确认 `mods/` 中每个 modId 只有一个 JAR，且客户端、服务端版本配对。

## 构建

```powershell
.\gradlew.bat clean test build --no-daemon
```

产物位于 `build/libs/`。

## 许可证与声明

本 NeoForge 1.21.1 分支采用 `AGPL-3.0-or-later`，详见 [LICENSE](LICENSE)、[AUTHORS.md](AUTHORS.md) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

项目使用生成式 AI 辅助设计、编码、审查、测试和文档整理；所有发布内容仍由维护者负责审核与验证。
