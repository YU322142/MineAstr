# MineAstr 0.7.39 — QQ 图片缓存传输修复

QQ 普通图片的公开地址可能返回 HTTP 400，导致图片消息已到 Minecraft、缩略图却显示加载失败。本次保留 OneBot 图片文件标识，在客户端请求图片时通过 `get_image` 获取 NapCat 已下载的原图，优先使用 AstrBot 主机可读取的本地缓存；缓存不可读时使用 API 返回的公开地址。

文件查询、下载、解码和压缩在图片请求阶段异步执行，聊天消息先发送签名地址，不等待图片查询。原图缓存不会暴露给 Minecraft，HTTP 仍验证签名和有效期。GIF 首帧与完整动画共用一次文件查询，保留原有渐进加载、压缩与并发限制。

本次仅升级 AstrBot 插件到 0.7.39，兼容现有 NeoForge 服务端及客户端 0.7.38，协议仍为 1。Minecraft 服务端和客户端无需升级或重启，没有增加模组。AstrBot 插件更新后需重载或重启 AstrBot。

验证：213 项插件测试通过，包含完整 JPEG 经签名 HTTP/Range 接收、GIF 首帧与动画、同图去重后的文件标识、查询失败回退和消息路径不调用图片查询。上线前已确认受影响 QQ 地址返回 400，而 NapCat 缓存中的 JPEG 可完整读取。

下载 `astrbot_plugin_mineastr-v0.7.39.zip` 更新插件。客户端继续使用 [0.7.38 发布包](https://github.com/YU322142/MineAstr/releases/tag/v0.7.38)。
