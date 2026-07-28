# Changelog

## 0.6.6

- 登录绑定检查改为通过 Mixin Accessor 读取原始 `requestedUsername`，不再使用会拼接 IP 和端口的 `getUserName()` 日志显示值。
- 防止连接地址进入验证码绑定、白名单 UUID 解析和聊天平台昵称。

## 0.6.5

- 目标平台固定为 Minecraft 1.21.11、Fabric Loader 与 Fabric API 0.141.4+1.21.11。
- 原版白名单同步不再依赖命令字符串：根据服务器在线/离线认证模式解析 `NameAndId`，直接持久化并读回核验。
- 白名单解析、保存或核验失败时向 AstrBot 返回明确错误，避免出现“绑定成功但仍无法登录”的假成功状态。
- 登录校验继续支持 `loginCheckFailOpen=true`，AstrBot 断线或超时时由 MineAstr 放行。
- 保留跨平台聊天、绑定验证码、状态/性能/玩家工具、定向通知、受控命令与玩家授权截图。
