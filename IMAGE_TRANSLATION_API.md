# MineAstr 图片翻译接口

当前源码版本：**0.8.2**（本地构建）。已发布版本见 GitHub Releases；本次图片翻译改进尚未部署。

MineAstr 提供 `image_translate_request` WebSocket 请求。NeoForge 客户端
通过 `MineAstrClient.requestImageTranslation(...)` 发起请求，AstrBot 使用当前会话
的多模态 Provider 识别图片文字并返回 JSON 译文。

图片翻译提示词独立配置在 `bridge_settings.image_translation_prompt`。请求自身也可
传 `prompt` 覆盖这一次调用；请求为空时才读取该配置。普通消息的
`translation_custom_instructions` 不会自动混入图片提示词。
AstrBot 配置中的图片提示词最多使用 40000 字；NeoForge 外部 Mod 在单次请求中临时传入的
`prompt` 仍受客户端网络载荷的 4096 字符上限约束。

服务端会按图片字节、目标语言、上下文和提示词缓存结果，避免同一图片重复调用模型。

## 等义双语图片 / Equivalent bilingual images

0.8.2 的多模态调用在 OCR 阶段同时判断图片是否已有完整、意思一致的双语，且已覆盖本次请求的全部目标语言。满足时跳过翻译和术语二次修正，不额外增加模型调用。仅有外文名称、混合文字、双语标题下仍有未翻译正文、意思冲突或目标语言未覆盖时，正常翻译。语义判断由所选多模态模型完成，不能仅凭文字脚本保证准确率。

WebSocket 成功跳过示例：

```json
{"type":"image_translate_result","message_id":"image-1","ok":true,"source_language":"multilingual","already_bilingual":true,"source_text":"","translations":{},"show_original":false}
```

MC 服务端和插件需配套 0.8.2，WebSocket 协议号及 NeoForge 网络字段顺序保持不变。客户端 `requestImageTranslation(...)` 的 Future 正常完成，结果 `ok()` 为 true、`alreadyBilingual()` 为 true，译文和 OCR 展示文字均为空。外部调用方应清除该图片原有翻译浮层并保留成功跳过判定，不能因译文为空而逐帧重新请求；普通失败仍按原来的异常路径处理。旧 MC 服务端不识别成功跳过，因此应一起更新。

成功跳过按图片字节、目标语言、提示词、上下文、模型和术语版本缓存；目标语言改变后重新判定。缓存仍遵循现有容量和过期清理策略。图片内的指令只作为图片内容，不得决定是否跳过。

The existing multimodal call checks semantic equivalence and full target coverage. A successful no-op has no display text, bypasses glossary correction, and is cached. Partial bilingual captions, unrelated languages and missing target coverage still require translation. External callers should remove an existing overlay and retain successful no-op results rather than retrying empty translations. Use the matching 0.8.2 plugin and server mod; the wire layout remains unchanged.
