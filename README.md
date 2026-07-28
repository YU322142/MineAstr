# MineAstr Fabric Mod

MineAstr 是同一仓库中 AstrBot MineAstr 插件的 Minecraft 端，已适配并锁定以下环境：

- Minecraft Java Edition `1.21.11`
- Fabric Loader `0.19.3`（最低 `0.18.1`）
- Fabric API `0.141.4+1.21.11`
- Java `21`
- MineAstr `0.6.7`

同一个 `mineastr-fabric-0.6.7.jar` 可以放在独立服务端、客户端或两端。服务端只安装 Mod 即可使用聊天、事件、绑定、登录检查及查询；截图功能要求目标玩家客户端也安装该 JAR。

## 功能

- Minecraft 与 AstrBot/Discord 双向聊天；
- 按每位玩家客户端 locale 显示 QQ/Discord/AstrBot 消息译文，并可选择同时显示原文；
- 服务器状态、在线玩家、TPS/MSPT、CPU、内存查询；
- 玩家状态、背包、附近实体与已加载区域结构分析；
- 玩家加入、离开、死亡事件；死亡事件包含原版 `death_type`、攻击者、直接伤害实体和武器，供 AstrBot 本地化原因；
- 账号绑定同步、重连后全量对账、可选原版白名单同步；
- 登录前绑定检查与一次性绑定验证码；
- 来自 Discord/聊天平台的定向玩家提醒；
- 默认关闭、双重白名单保护的服务器命令；
- 由玩家授权的低清晰度截图。

## 构建

安装 JDK 21 后运行：

```powershell
.\gradlew.bat clean build
```

产物位于 `build/libs/mineastr-fabric-0.6.7.jar`。

## 安装

1. 为 Minecraft 1.21.11 安装 Fabric Loader。
2. 把 `fabric-api-0.141.4+1.21.11.jar` 放入 `mods`。
3. 把 `mineastr-fabric-0.6.7.jar` 放入 `mods`。
4. 启动一次，生成 `config/mineastr-common.json`。
5. 把配置中的 `token` 改成与 AstrBot `minecraft` 平台适配器完全相同的随机字符串，然后重启。

客户端需要截图或单人世界桥接时也安装相同的 MineAstr 和 Fabric API JAR。进入游戏后按 `F8` 打开 MineAstr 客户端设置；按键可在 Minecraft 控制设置中修改。

游戏内自动翻译由 AstrBot 插件配置并调用文本模型生成，Mod 本身不连接翻译服务。服务端会按每位玩家客户端上报的 locale 选择对应译文；没有译文或翻译失败时显示原文。

## 服务端配置

文件：`config/mineastr-common.json`

```json
{
  "enabled": true,
  "websocketUrl": "ws://127.0.0.1:8765/ws",
  "token": "change-me",
  "serverId": "minecraft",
  "serverName": "Minecraft 服务器",
  "botDisplayName": "AstrBot",
  "reconnectSeconds": 5,
  "maxMessageLength": 1000,
  "enablePlayerStateTool": true,
  "enableInventoryTool": true,
  "enableNearbyEntitiesTool": true,
  "enableRegionTool": true,
  "regionMaxBlocks": 32768,
  "enableCommandTool": false,
  "trustedCommandUsers": [],
  "allowedCommandRules": [
    "list",
    "seed",
    "time query day",
    "time query daytime",
    "time query gametime"
  ],
  "commandPermissionLevel": 4,
  "commandMaxLength": 256,
  "enablePlayerNotifications": true,
  "notifyActionBar": true,
  "notifyTitle": false,
  "notifySound": true,
  "notificationMaxLength": 512,
  "enableBindingSync": false,
  "bindingSyncWhitelist": false,
  "loginBindingCheckEnabled": false,
  "loginCheckTimeoutSeconds": 5,
  "loginCheckFailOpen": true,
  "generateBindingCodeOnReject": true,
  "verifyCodeLength": 6,
  "loginCodeMessage": "\n绑定验证码：{code}\n请在 Discord/聊天平台使用 /mc bind {code}"
}
```

如果 AstrBot 不在同一台机器，将 `websocketUrl` 中的 `127.0.0.1` 改为 AstrBot 主机地址，并在 AstrBot 侧把监听 `host` 设为 Minecraft 主机可访问的地址。

### 绑定与登录拦截

两端开关需要对应启用：

| 功能 | AstrBot 插件 | Fabric Mod |
| --- | --- | --- |
| 绑定缓存同步 | `sync_binding_to_server=true` | `enableBindingSync=true` |
| 同步原版白名单 | 同上 | `bindingSyncWhitelist=true` |
| 未绑定禁止登录 | `need_bind_to_login=true` | `loginBindingCheckEnabled=true` |
| 验证码绑定 | `verify_method=VERIFY_CODE` | `generateBindingCodeOnReject=true` |

Mod 每次连接或重连后，AstrBot 会先重置服务端绑定缓存，再发送当前全部绑定，避免断线期间的解绑遗留。你的需求是 AstrBot 断线或超时仍放行，因此应保持 `loginCheckFailOpen=true`；这只放宽 MineAstr 的在线校验，原版白名单仍会照常检查。

`bindingSyncWhitelist=true` 会根据服务器认证模式解析玩家 UUID，直接更新并保存原版白名单，再读回核验结果。`0.6.5` 起同步失败会明确回报 AstrBot，不再出现“命令执行失败却显示同步成功”。`0.6.6` 又修复了登录显示名混入 IP/端口的问题。启用前请先备份白名单，并确保 MineAstr 是这些账号的唯一白名单管理来源。

`0.6.7` 起，未绑定登录拒绝、绑定验证码和游戏内定向提醒提供 `zh_cn` / `en_us` 本地化。玩家安装同版 MineAstr 客户端 Mod 时，登录前提示通过翻译键跟随客户端语言；未安装客户端 Mod 时使用 AstrBot 返回的默认语言回退文本。进入游戏后的定向提醒还会读取客户端上报的语言，即使客户端未安装 Mod 也能选择中英文前缀。

### 受控命令

`enableCommandTool` 默认关闭。启用后，请同时配置：

- `trustedCommandUsers`：可信 Minecraft UUID、玩家名、AstrBot 用户 ID 或 `平台ID:用户ID`；QQ/OneBot 例如 `default:123456789`；
- `allowedCommandRules`：完整命令或带 `*` 的前缀规则，例如 `"say *"`。`"op *"` 会允许可信请求者授予任意玩家 OP，只应按需开启。

不要使用单独的 `"*"`，除非你明确接受远程执行任意服务器命令的风险。所有成功执行都会写 WARN 审计日志。

## 客户端配置

文件：`config/mineastr-client.json`

```json
{
  "localWorldServerEnabled": false,
  "gameTranslationsEnabled": true,
  "showOriginalTranslatedMessages": true,
  "screenshotMode": "ASK",
  "screenshotMaxWidth": 240,
  "screenshotMaxHeight": 135,
  "screenshotJpegQuality": 0.35,
  "screenshotMaxBytes": 131072
}
```

- `gameTranslationsEnabled`：显示服务器提供的客户端语言译文；关闭后只显示原文。
- `showOriginalTranslatedMessages`：有译文时在下一行附带原文。

这两个选项也可在 F8 设置界面修改，并在加入服务器时上报。没有安装客户端 Mod 的玩家仍会按 Minecraft 原生上报的 locale 收到译文，是否附带原文使用 AstrBot 插件的默认配置。

`screenshotMode` 可选：

- `ASK`：每次弹窗询问，默认且推荐；
- `AUTO`：自动发送，仅用于完全可信的私人服务器；
- `DISABLED`：始终拒绝。

截图只在内存中压缩为低清晰度 JPEG，不写入本地截图目录。单人世界桥接默认关闭；可按 `F8` 进入“本地服务端”页面开启。

## 命令

- `/mineastr status`：查看 WebSocket 连接状态；
- `/mineastr reconnect`：主动重连。

服务端命令需要权限等级 2。

## 服务端与客户端安装边界

- 仅服务端安装：聊天、事件、查询、绑定、登录检查可用；玩家无需安装 Mod。
- 服务端和客户端都安装：额外支持截图，以及玩家自己的译文/原文显示偏好。
- 仅客户端安装：可选单人世界集成服务器桥接；多人服务器端没有安装时无法提供桥接。

## 故障排查

- 依赖错误：确认是 Minecraft 1.21.11、Fabric API `0.141.4+1.21.11` 和 Java 21。
- `401`/认证失败：两端 `token` 不一致。
- 一直重连：确认 AstrBot `minecraft` 平台已启用，端口和路径一致，防火墙允许连接。
- 绑定没有进入 Mod：两端绑定同步开关都要启用。
- 登录检查无效：两端登录检查开关都要启用；查看 Mod 是否已连接 AstrBot。
- 游戏名后出现 `(/[IPv6]:端口)`：升级插件和 Mod 到 `0.6.7`；新版读取纯登录玩家名，插件会自动迁移旧绑定。
- 绑定成功但仍提示不在白名单：必须同时使用 `0.6.7` 插件和 Mod，并在日志确认出现 `whitelist_verified=true`；正版模式下无法解析的非正版玩家名不会被假装同步成功。
- 截图提示不支持：目标玩家客户端也需要安装同一 MineAstr JAR 和 Fabric API。
- F8 无反应：在“控制”中搜索 MineAstr，检查是否存在按键冲突。

## 许可与来源

本移植基于 [Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr)，许可标识为 `AGPL-3.0-or-later`。当前维护仓库为 [YU322142/MineAstr](https://github.com/YU322142/MineAstr)。AQQBot 功能语义参考 [alazeprt/AQQBot](https://github.com/alazeprt/AQQBot)。生成式 AI 参与了迁移、测试和文档工作，最终使用责任由维护者承担。
