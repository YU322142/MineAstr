# MineAstr 外部图片翻译与显示接口

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
        String translated = result.translations().getOrDefault(
                "zh_cn", result.sourceText());
        if (translated == null || translated.isBlank()) {
            return;
        }
        MineAstrDisplayApi.showEntityTranslation(
                "immersive-painting:" + painting.getId(),
                painting.getId(),
                new Vec3(0.0, painting.getBbHeight() + 0.2, 0.0),
                translated,
                result.sourceText());
    });
```

`prompt` 是本次请求的临时图片翻译提示词；为空时由 AstrBot 使用独立的 `image_translation_prompt` 配置。图片最大 768 KiB，上下文最大 2048 字符，临时提示词最大 4096 字符，客户端等待结果最长 45 秒。

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
