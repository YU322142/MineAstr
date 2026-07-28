# Changelog

## 0.6.7

- `trustedCommandUsers` 除纯用户 ID、UUID 和名称外，也接受更明确的 `平台ID:用户ID`，例如 QQ/OneBot 的 `default:123456789`。
- 文档明确说明 AstrBot/MineAstr 管理员与 Mod 命令可信名单是独立的两层安全校验；`op *` 仅应按需授权。
- 玩家死亡事件新增 Minecraft `death_type`、攻击者、直接伤害实体和武器字段，AstrBot 可据此生成不重复玩家名的中文/英文死亡原因。
- 未绑定登录拒绝、验证码和游戏内定向提醒支持中英文客户端本地化；安装同版客户端 Mod 时自动跟随客户端语言，未安装时使用安全回退文本。

## 0.6.6

- 登录绑定检查改为通过 Mixin Accessor 读取原始 `requestedUsername`，不再使用会拼接 IP 和端口的 `getUserName()` 日志显示值。
- 防止连接地址进入验证码绑定、白名单 UUID 解析和聊天平台昵称。

## 0.6.5

- 目标平台固定为 Minecraft 1.21.11、Fabric Loader 与 Fabric API 0.141.4+1.21.11。
- 原版白名单同步不再依赖命令字符串：根据服务器在线/离线认证模式解析 `NameAndId`，直接持久化并读回核验。
- 白名单解析、保存或核验失败时向 AstrBot 返回明确错误，避免出现“绑定成功但仍无法登录”的假成功状态。
- 登录校验继续支持 `loginCheckFailOpen=true`，AstrBot 断线或超时时由 MineAstr 放行。
- 保留跨平台聊天、绑定验证码、状态/性能/玩家工具、定向通知、受控命令与玩家授权截图。
