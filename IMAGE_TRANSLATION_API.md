# MineAstr 图片翻译接口

MineAstr `v0.6.23` 提供 `image_translate_request` WebSocket 请求。Fabric 客户端
通过 `MineAstrClient.requestImageTranslation(...)` 发起请求，AstrBot 使用当前会话
的多模态 Provider 识别图片文字并返回 JSON 译文。

图片翻译提示词独立配置在 `bridge_settings.image_translation_prompt`。请求自身也可
传 `prompt` 覆盖这一次调用；请求为空时才读取该配置。普通消息的
`translation_custom_instructions` 不会自动混入图片提示词。

服务端会按图片字节、目标语言、上下文和提示词缓存结果，避免同一图片重复调用模型。
