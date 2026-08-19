# MineAstr 0.6.29 配置参考

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
| `loginCheckFailOpen` | `true` | AstrBot 不可用时是否放行 |
| `generateBindingCodeOnReject` | `true` | 拒绝时生成一次性验证码 |

白名单同步和登录检查是独立功能，启用前应明确账号恢复与 AstrBot 故障策略。

## 客户端翻译

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `localWorldServerEnabled` | `false` | 单人集成服务器是否连接 AstrBot |
| `gameTranslationsEnabled` | `true` | 游戏翻译总开关 |
| `showOriginalTranslatedMessages` | `true` | 普通聊天是否同时显示原文 |
| `signTranslationsEnabled` | `true` | 准星目标浮选总开关 |
| `signTranslationMaxDistance` | `8` | 浮选最大距离 |
| `signTranslationScale` | `1.0` | 浮选缩放 |

`showOriginalTranslatedMessages` 只控制普通聊天。0.6.29 的目标 HUD 默认只显示译文。

## 截图

`screenshotMode` 可设为 `ASK`、`AUTO` 或 `DISABLED`。公共整合包建议保留 `ASK`。宽度、高度、JPEG 质量和编码大小上限可分别配置。

## 配置变更流程

1. 停止服务端或确认对应配置允许热加载。
2. 备份 TOML。
3. 只修改目标键，不整文件覆盖玩家本地偏好。
4. 检查重复键和 TOML 语法。
5. 重启后运行 `/mineastr status` 并检查日志。

通过 MCSync 发布配置 OTA 时，优先使用精确键级补丁；Token 和私有地址必须继续由本地配置提供。
