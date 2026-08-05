# Changelog

## 0.6.18

- Add a reliable server-side sign translation trigger when a player interacts with a sign.
- Keep modern client overlays and legacy chat fallback working, with duplicate pending-request suppression and diagnostic logs.

## 0.6.17

- Added optional chat media forwarding from AstrBot to Minecraft and preserved image URLs in the in-game relay.
- Synchronized the Fabric Mod with MineAstr plugin `v0.6.17`.

## 0.6.16

- Synchronize the Fabric Mod version with the AstrBot plugin at `v0.6.16`.
- Keep targeted sign overlays, persistent sign caches, and normalized duplicate-text handling from the previous release line.

## 0.6.12

- New release line for targeted sign translation overlays and normalized duplicate-text handling.
- The original sign remains unchanged; only the sign under the crosshair can show a world-space translation overlay.
- Sign translations remain fingerprinted and persisted in the Minecraft world save.

## 0.6.11

- `allowedCommandRules` 改为公开命令白名单：命中规则的指令任何真实聊天请求者都可立即执行。
- 白名单外指令不再直接拒绝或执行，而是保存为限量、限时的待审批项；只有静态或 AstrBot 实时同步管理员可批准/拒绝，批准时只执行 Mod 已保存的精确原始指令。
- 新增 `commandApprovalTimeoutSeconds` 与 `commandMaxPendingApprovals`，并为申请、审批、拒绝和执行写入 WARN 审计日志。
- 管理员同步新增 revision 防倒序覆盖，并在 WebSocket 断线时同时清除动态管理员与待审批命令。
- 新安装默认开启 `syncTrustedCommandUsers`；静态 `trustedCommandUsers` 仍与动态名单取并集且不会被覆盖。
- 告示牌翻译改为准星目标式世界浮选：不再进入服务器时批量查询或刷聊天，原始牌面保持不变，译文只显示在准星指向的告示牌旁。
- 服务端继续把告示牌译文按指纹持久化到世界存档；翻译前后规范化文本一致时保留原文，不添加重复译文或原文标签。

## 0.6.10

- 修复离线模式服务端误把 Mojang 正版 UUID 写入原版白名单：绑定同步现在使用与原版离线登录一致的 UUID。
- 在原版 `PlayerList` 白名单检查前记录并对账本次连接的真实 `NameAndId`，兼容 Velocity/BungeeCord、Floodgate 和混合认证产生的自定义 UUID。
- 绑定时清理同名但 UUID 不同的旧白名单项，并在响应中返回 `identity_source`，避免 `whitelist_verified=true` 只验证了错误身份仍被误判为成功。

## 0.6.9

- 新增 `syncTrustedCommandUsers`：可接收 AstrBot 插件同步的 MineAstr 管理员与 AstrBot 全局管理员，作为当前 WebSocket 连接的内存命令可信集合。
- 同步集合不会覆盖静态 `trustedCommandUsers`，断线时自动清空；`enableCommandTool` 与 `allowedCommandRules` 继续独立执行二次保护。

## 0.6.7

- `trustedCommandUsers` 除纯用户 ID、UUID 和名称外，也接受更明确的 `平台ID:用户ID`，例如 QQ/OneBot 的 `default:123456789`。
- 文档明确说明 AstrBot/MineAstr 管理员与 Mod 命令可信名单是独立的两层安全校验；`op *` 仅应按需授权。
- 玩家死亡事件新增 Minecraft `death_type`、攻击者、直接伤害实体和武器字段，AstrBot 可据此生成不重复玩家名的中文/英文死亡原因。
- 未绑定登录拒绝、验证码和游戏内定向提醒支持中英文客户端本地化；安装同版客户端 Mod 时自动跟随客户端语言，未安装时使用安全回退文本。
- AstrBot 可随聊天正文提供多 locale 译文；服务端按每位玩家客户端语言分别显示，没有匹配译文时回退原文。
- F8 客户端设置新增“显示游戏内译文”和“译文下方显示原文”，偏好通过独立的向后兼容载荷上报。

## 0.6.6

- 登录绑定检查改为通过 Mixin Accessor 读取原始 `requestedUsername`，不再使用会拼接 IP 和端口的 `getUserName()` 日志显示值。
- 防止连接地址进入验证码绑定、白名单 UUID 解析和聊天平台昵称。

## 0.6.5

- 目标平台固定为 Minecraft 1.21.11、Fabric Loader 与 Fabric API 0.141.4+1.21.11。
- 原版白名单同步不再依赖命令字符串：根据服务器在线/离线认证模式解析 `NameAndId`，直接持久化并读回核验。
- 白名单解析、保存或核验失败时向 AstrBot 返回明确错误，避免出现“绑定成功但仍无法登录”的假成功状态。
- 登录校验继续支持 `loginCheckFailOpen=true`，AstrBot 断线或超时时由 MineAstr 放行。
- 保留跨平台聊天、绑定验证码、状态/性能/玩家工具、定向通知、受控命令与玩家授权截图。
