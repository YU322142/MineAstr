# MineAstr 外部图片翻译与显示接口

当前源码版本：**0.8.2**（本地构建，尚未部署）；此前发行包保持不变。

NeoForge 1.21.1 版从 `0.6.25` 提供与 Fabric 版相同的客户端公开接口。外部 Mod 不需要直接连接 AstrBot，也不应自行绘制译文。

```text
图片字节
  -> MineAstrClient.requestImageTranslation(...)
  -> MineAstr 服务端 WebSocket
  -> AstrBot 多模态模型
  -> ImageTranslationResult
  -> MineAstrDisplayApi.showEntityTranslation(...)
```

## 请求图片翻译

```java
MineAstrClient.requestImageTranslation(
        jpegBytes,
        "image/jpeg",
        List.of("zh_cn", "en_us"),
        "这是沉浸画框中的图片；保留专有名词和换行。",
        "图片翻译专用要求：优先识别画面中的文字，不要描述图片。")
    .thenAccept(result -> {
        String displayId = "immersive-painting:" + painting.getId();
        if (result.alreadyBilingual()) {
            MineAstrDisplayApi.remove(displayId);
            // Cache this successful no-op in the caller's image state; do not retry empty text.
            // 调用方按图片内容和目标语言缓存成功跳过判定，不要因空译文反复请求。
            return;
        }
        String translated = result.translations().getOrDefault(
                "zh_cn", result.sourceText());
        if (translated == null || translated.isBlank()) {
            return;
        }
        MineAstrDisplayApi.showEntityTranslation(
                displayId,
                painting.getId(),
                new Vec3(0.0, painting.getBbHeight() + 0.2, 0.0),
                translated,
                result.sourceText());
    });
```

`prompt` 是本次请求的临时图片翻译提示词；为空时由 AstrBot 使用独立的 `image_translation_prompt` 配置。图片最大 768 KiB，上下文最大 2048 字符，临时提示词最大 4096 字符，客户端等待结果最长 45 秒。

0.8.2 的图片语义判断复用原有多模态调用，不额外增加模型轮次。图中完整文字已存在等义双语，且覆盖全部请求语言时，Future 正常完成，`result.ok()` 和 `result.alreadyBilingual()` 为 true，`sourceText()` 为空、`translations()` 为空、`showOriginal()` 为 false。不要回退展示 OCR 原文，不要将这个结果当失败重试；外部模组应清除旧浮层并缓存成功判定。部分双语、矛盾译文或尚未覆盖的第三种目标语言仍正常翻译。语义准确性取决于配置的多模态模型。

需要配套更新 AstrBot 插件和 MC 服务端为 0.8.2。NeoForge 网络字段顺序保持不变，新增 `alreadyBilingual()` 仅从现有成功、语言和空文字字段识别跳过状态；旧 MC 服务端不能识别插件的新成功跳过语义。

## 统一显示接口

```java
MineAstrDisplayApi.showEntityTranslation(
        id, entityId, offset, translated, original);
MineAstrDisplayApi.showWorldTranslation(
        id, dimension, anchor, translated, original);
MineAstrDisplayApi.remove(id);
MineAstrDisplayApi.clear();
```

默认仅在准星指向相应实体或锚点时显示。距离、缩放、浮选总开关和是否保留原文统一由 MineAstr 的 F8 设置控制；原文与译文一致时只显示一份。需要持续显示时可调用带 `onlyWhenTargeted=false` 的重载。

这些类只应从物理客户端调用。沉浸画框等调用方应在实体重建后重新提交，并在实体卸载时调用 `remove(id)`。
