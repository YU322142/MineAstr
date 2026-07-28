# Changelog

## 0.6.5

- 补齐 QQ/OneBot 与 Discord 的退群自动解绑、群名片/昵称同步和多游戏账号顺序显示。
- 验证码绑定直接采用 Minecraft 服务端上报的登录名，不再误套手工玩家名正则。
- 增加登录绑定校验审计日志，能直接确认数据库命中和放行结果。
- 配套 Fabric Mod 改为按在线/离线认证模式解析 UUID，直接写入、保存并核验原版白名单；同步失败不再假报成功。
- 修复 Windows ZIP 缺少首个目录条目时 AstrBot 4.23.6 上传安装报 `NotADirectoryError`；新增跨平台打包脚本和 CI 校验。
- WebSocket 客户端连接日志不再输出远端 IP 地址。
- 固定支持 Minecraft 1.21.11、Fabric API 0.141.4+1.21.11 和 Java 21。

## 0.6.4 及更早

早期版本完成 AstrBot Minecraft 平台适配器、AQQBot 兼容绑定流程、跨平台聊天、通知、状态查询、受控命令与 LLM 工具的初步移植。
