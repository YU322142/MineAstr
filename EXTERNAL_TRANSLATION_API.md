# MineAstr 外部图片翻译与显示接口

`0.6.21` 开始，外部客户端 Mod 不需要自己连接 AstrBot，也不需要自己绘制译文。

调用链如下：

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

`prompt` 参数是本次请求的临时提示词；为空时，服务端使用 AstrBot 配置中的
`bridge_settings.image_translation_prompt`。这个配置在 AstrBot 设置界面中是独立的
“图片翻译专用提示词”填写区域，不会复用普通消息或告示牌提示词。

## 统一显示接口

`MineAstrDisplayApi` 只负责提交/更新显示数据，实际浮选渲染由 MineAstr 客户端完成。
画框 Mod 不应再次实现一套世界空间文字渲染。

```java
MineAstrDisplayApi.showEntityTranslation(
        id, entityId, offset, translated, original);
MineAstrDisplayApi.remove(id);
MineAstrDisplayApi.clear();
```

默认只有准星指向对应实体或锚点时才显示，最大距离为 32 格；需要持续显示时可使用带
`onlyWhenTargeted=false` 的重载。原文是否附带显示继续遵守 MineAstr 客户端的
“显示译文时保留原文”设置；原文和译文一致时不会重复显示。
