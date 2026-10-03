# MineAstr 客户端聊天展示协议（0.7.24）

完整 WebSocket 消息与绑定/翻译协议见 [AstrBot 协议](https://github.com/YU322142/MineAstr/blob/astrbot-plugin/PROTOCOL.md)。本次协议号仍为 1，旧客户端图片分片不变。

AstrBot `chat.sender_platform` 可取 `minecraft`、`qq`、`discord`。机器人回复使用请求来源平台；插件将自定义适配器 ID 映射到平台种类。

NeoForge 可选客户端通道 `mineastr:chat_presentation` 依次编码 platform（UTF-8 16）、senderName（64）、按玩家语言选定的 content（16384）、图片数量 VarInt（0–8），随后每张图片的 id（64）和 name（128）。服务器仅对声明该通道的客户端使用；先发送正文与图片引用，随后使用相同图片 ID 发送既有 `bot_image_chunk`。

没有新通道的客户端继续接收旧版系统消息与分片。图片总开关、F8 接收偏好与哈希/大小验证仍然生效。旧 `chatImageAvailable` 字段在新版客户端表示已有图片渲染能力，新客户端无需额外安装 ChatImage。
