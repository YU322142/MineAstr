# MineAstr AstrBot 插件 0.8.1 — 图片重复附加修复

本次仅更新 AstrBot 插件。Minecraft NeoForge 模组继续使用 0.8，WebSocket 协议号仍为 1；无需更新 MC 模组。

- 同一条消息从组件链和平台原始数据提取出同一附件时，合并 Discord CDN/代理/签名链接、QQ 文件标识和 NTQQ 临时链接，避免重复附加。
- 多个来源最终转换为相同缓存图片时，QQ/Discord 投递只附加一次，GIF 动画继续保留。
- 去重仅限单条消息。用户在后续独立消息中重复发图仍正常转发；慢图片处理期间文字保持先发送。

修复前已复现六项失败场景；修复后 231 项 Python 测试、112 项子测试及 Ruff 关键检查通过，包含实际 HTTP 下载和双帧 GIF 缓存处理。平台发送在测试中捕获，尚未向真实 QQ/Discord 群发送测试消息；如果仍出现整条消息被重复发送，需要继续结合对应消息记录排查。

安装前备份插件目录及配置，通过 AstrBot 插件安装或更新并重载。本次没有配置迁移。此前 0.8 的完整客户端/服务端验证记录见主分支 `docs/VALIDATION_0.8.md`。

Deduplicate known platform attachment aliases and identical optimized cache paths within each message. Keep GIF animation, early text delivery and repeated images in later independent messages. Tested with 231 tests and 112 subtests, including a real local HTTP/GIF pipeline and captured platform sends. Live QQ/Discord delivery is not covered by these tests. Compatible with NeoForge mod 0.8 and protocol 1.
