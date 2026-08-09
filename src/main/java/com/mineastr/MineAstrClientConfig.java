package com.mineastr;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineAstrClientConfig {
    public enum ScreenshotMode {
        ASK,
        AUTO,
        DISABLED
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOCAL_WORLD_SERVER_ENABLED = BUILDER
            .comment("是否在本地单人世界中启动 MineAstr 集成服务器桥接。")
            .define("localWorldServerEnabled", false);
    public static final ModConfigSpec.BooleanValue GAME_TRANSLATIONS_ENABLED = BUILDER
            .comment("是否在游戏内显示 AstrBot 提供的本地化译文。")
            .define("gameTranslationsEnabled", true);
    public static final ModConfigSpec.BooleanValue SHOW_ORIGINAL_TRANSLATED_MESSAGES = BUILDER
            .comment("显示译文时是否同时保留原文。")
            .define("showOriginalTranslatedMessages", true);
    public static final ModConfigSpec.BooleanValue SIGN_TRANSLATIONS_ENABLED = BUILDER
            .comment("是否显示准星所指告示牌及外部画框接口的浮选译文。")
            .define("signTranslationsEnabled", true);
    public static final ModConfigSpec.IntValue SIGN_TRANSLATION_MAX_DISTANCE = BUILDER
            .comment("告示牌和外部显示接口的最大显示距离。")
            .defineInRange("signTranslationMaxDistance", 8, 1, 32);
    public static final ModConfigSpec.DoubleValue SIGN_TRANSLATION_SCALE = BUILDER
            .comment("告示牌和外部显示接口的译文缩放比例。")
            .defineInRange("signTranslationScale", 1.0, 0.50, 2.0);

    public static final ModConfigSpec.EnumValue<ScreenshotMode> SCREENSHOT_MODE = BUILDER
            .comment("截图请求策略：ASK、AUTO 或 DISABLED。")
            .defineEnum("screenshotMode", ScreenshotMode.ASK);
    public static final ModConfigSpec.IntValue SCREENSHOT_MAX_WIDTH = BUILDER
            .comment("发送给 AstrBot 的截图最大宽度。")
            .defineInRange("screenshotMaxWidth", 240, 64, 1024);
    public static final ModConfigSpec.IntValue SCREENSHOT_MAX_HEIGHT = BUILDER
            .comment("发送给 AstrBot 的截图最大高度。")
            .defineInRange("screenshotMaxHeight", 135, 36, 1024);
    public static final ModConfigSpec.DoubleValue SCREENSHOT_JPEG_QUALITY = BUILDER
            .comment("截图 JPEG 质量，范围 0.10 到 0.95。")
            .defineInRange("screenshotJpegQuality", 0.35, 0.10, 0.95);
    public static final ModConfigSpec.IntValue SCREENSHOT_MAX_BYTES = BUILDER
            .comment("单张截图编码后的最大字节数。")
            .defineInRange("screenshotMaxBytes", 131072, 8192, 524288);

    static final ModConfigSpec SPEC = BUILDER.build();

    private MineAstrClientConfig() {
    }
}
