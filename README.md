# MineAstr

[![AI Assisted](https://img.shields.io/badge/AI-OpenAI%20Codex%20Assisted-10A37F?style=for-the-badge&logo=openai&logoColor=white)](#ai-制作声明)

> [!IMPORTANT]
> **AI 制作声明：MineAstr 采用生成式 AI 参与架构设计、编码、UI、文档与测试。** AI 生成或修改的内容由项目维护者审阅、验证并承担最终维护责任。

MineAstr 将 Minecraft 1.21.11 Fabric 服务器接入 AstrBot、QQ/OneBot 与 Discord，提供 AQQBot 兼容的账号绑定、跨平台聊天、退群解绑、群名片/昵称同步、通知、状态查询、受控命令和 LLM 工具。

## 项目分支

- [`astrbot-plugin`](https://github.com/YU322142/MineAstr/tree/astrbot-plugin)：AstrBot 插件端；分支根目录可直接作为插件项目。
- [`minecraft-mod`](https://github.com/YU322142/MineAstr/tree/minecraft-mod)：Minecraft Fabric Mod；目标为 Minecraft `1.21.11`、Fabric API `0.141.4+1.21.11`、Java `21`。

`main` 是项目索引；两个可构建工程分别保留在以上分支，以兼容 AstrBot 从仓库分支安装插件的目录要求。

## 当前版本

- AstrBot 插件：`0.6.5`
- MineAstr Fabric Mod：`0.6.5`
- Minecraft：`1.21.11`
- Fabric API：`0.141.4+1.21.11`

成品请从 [GitHub Releases](https://github.com/YU322142/MineAstr/releases) 下载：

- `astrbot_plugin_mineastr-v0.6.5.zip`
- `mineastr-fabric-0.6.5.jar`

## 连接方式

Minecraft Mod 主动连接 AstrBot 插件注册的 `minecraft` 平台适配器：

```text
Minecraft MineAstr Mod -> ws://astrbot.example.com:8765/ws -> AstrBot
```

跨机器部署时，AstrBot 监听地址应设为 `0.0.0.0`，Mod 填写 AstrBot 主机的可达地址。Token 必须由部署者自行生成并在两端保持一致。

仓库不包含真实服务器 IP、Token、QQ 号、Discord 用户 ID 或运行时绑定数据库。示例仅使用 `127.0.0.1`、`0.0.0.0`、`change-me` 和虚构账号；运行时配置、日志与数据库均已加入 `.gitignore`。

## 绑定与白名单

`0.6.5` 会按服务器在线/离线认证模式解析玩家 UUID，直接写入并保存原版白名单，再读回核验。若解析、保存或核验失败，Mod 会向 AstrBot 返回失败，不再显示虚假的同步成功。

需要“检查玩家已绑定、同步原版白名单、AstrBot 断线或超时仍放行”时：

- AstrBot 插件：`binding_enabled=true`、`need_bind_to_login=true`、`sync_binding_to_server=true`、`binding_sync_required=true`
- Fabric Mod：`enableBindingSync=true`、`bindingSyncWhitelist=true`、`loginBindingCheckEnabled=true`、`loginCheckFailOpen=true`

完整安装和权限说明请查看两个工程分支的 README。

## 构建验证

- AstrBot 插件：29 个测试通过，另含 20 个参数化子测试。
- Fabric Mod：Gradle `clean build` 通过。
- 实机协议联调：Minecraft 1.21.11 + Fabric API 0.141.4，已验证绑定写入 UUID、`whitelist_verified=true`、解绑移除和正常关服保存。

## 许可与来源

本项目按 `AGPL-3.0-or-later` 开源，详见 `LICENSE`。本移植基于 [Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr)，AQQBot 功能语义参考 [alazeprt/AQQBot](https://github.com/alazeprt/AQQBot)（LGPL-2.1）；详细第三方说明位于插件分支的 `THIRD_PARTY_NOTICES.md`。

## AI 制作声明

本项目使用 OpenAI Codex 等生成式 AI 能力参与 Java/Python 代码、LLM tools、客户端 UI、安全审查、README 与构建测试。AI 输出仍需经过人工审阅和实际测试。

英文声明：*This project was created with assistance from generative AI, including OpenAI Codex. AI-assisted changes remain subject to human review, testing, and maintainer responsibility.*
