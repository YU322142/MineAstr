# MineAstr 客户端聊天展示协议（0.7.25）

完整 WebSocket 消息与绑定/翻译协议见 [AstrBot 协议](https://github.com/YU322142/MineAstr/blob/astrbot-plugin/PROTOCOL.md)。本次协议号仍为 1，旧客户端图片分片不变。

AstrBot `chat.sender_platform` 可取 `minecraft`、`qq`、`discord`。机器人回复使用请求来源平台；插件将自定义适配器 ID 映射到平台种类。

NeoForge 可选客户端通道 `mineastr:chat_presentation` 依次编码 platform（UTF-8 16）、senderName（64）、按玩家语言选定的 content（16384）、图片数量 VarInt（0–8），随后每张图片的 id（64）和 name（128）。服务器仅对声明该通道的客户端使用；先发送正文与图片引用，随后使用相同图片 ID 发送既有 `bot_image_chunk`。

没有新通道的客户端继续接收旧版系统消息与分片。图片总开关、F8 接收偏好与哈希/大小验证仍然生效。旧 `chatImageAvailable` 字段在新版客户端表示已有图片渲染能力，新客户端无需额外安装 ChatImage。

## 玩家主题色（0.7.25，可选 Minecraft 通道）

`mineastr:theme_preferences` 为客户端到服务端的可选 play payload：`update:boolean`、`color/second/third:int RGB24`、`count:varint (1–3)`、`period:varint (4000–20000 ms)`。只取已登录连接的 UUID 和名称，客户端不能指定被修改者。`update=false` 请求首次快照并保留已有主题；`true` 修改本人主题。每个启用颜色须相对 #303030 达到 4.5:1 对比度，非法颜色拒绝，更新限流 500 ms。

`mineastr:theme_palette` 为服务端到客户端的可选 payload：`reset:boolean`、`count:varint (0–128)`，后跟至多 128 条 `{uuid:UUID, name:utf(64), color:int, second:int, third:int, count:varint, period:varint}`。首次连接按 128 条分批快照，首包 reset；之后发送差异。主题保存到主世界 `data/mineastr_player_themes.dat`，退出清理客户端色表。双色/三色使用线性光空间插值与 1024 阶预计算表，仅绘制昵称和正文，图标不着色。不改变既有 payload 字段或协议号 1；旧客户端不接收新通道。AstrBot 不存储主题或参与这个 Minecraft 客户端通道，已绑定平台消息通过 MC 名命中同一主题。
