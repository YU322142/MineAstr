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
    public static final ModConfigSpec.BooleanValue RECEIVE_IMAGE_MESSAGES = BUILDER
            .comment("是否接收 AstrBot 图片消息。关闭后服务端将不再向该玩家发送图片，与是否安装 ChatImage 无关。")
            .define("receiveImageMessages", true);
    public static final ModConfigSpec.BooleanValue ACCEPT_BOT_IMAGES = BUILDER
            .comment("是否接收 AstrBot 图片消息；MineAstr 自带内联缩略图，兼容独立的 ChatImage 功能。")
            .define("acceptBotImages", true);
    public static final ModConfigSpec.IntValue PLAYER_THEME_COLOR = BUILDER
            .comment("玩家昵称与正文的 RGB 主题色；相对 #303030 对比度至少 4.5:1，过暗颜色不允许保存。")
            .defineInRange("playerThemeColor", MineAstrThemeColor.DEFAULT, 0, 0xFFFFFF);
    public static final ModConfigSpec.IntValue PLAYER_THEME_SECOND = BUILDER.defineInRange("playerThemeSecond", 0x72E6C1, 0, 0xFFFFFF);
    public static final ModConfigSpec.IntValue PLAYER_THEME_THIRD = BUILDER.defineInRange("playerThemeThird", 0xB5B8FF, 0, 0xFFFFFF);
    public static final ModConfigSpec.IntValue PLAYER_THEME_STOPS = BUILDER.defineInRange("playerThemeStops", 1, 1, 3);
    public static final ModConfigSpec.IntValue PLAYER_THEME_PERIOD = BUILDER.defineInRange("playerThemePeriod", 8000, 4000, 20000);
    public static final ModConfigSpec.IntValue CHAT_IMAGE_SCALE = BUILDER
            .comment("聊天内图片显示比例（百分比），自动限制在聊天视口内。")
            .defineInRange("chatImageScale", 100, 50, 300);
    public static final ModConfigSpec.BooleanValue CHAT_ANIMATIONS_ENABLED = BUILDER
            .comment("启用 ModernUI 风格的聊天缓动滚动与新消息淡入。")
            .define("chatAnimationsEnabled", true);
    public static final ModConfigSpec.IntValue CHAT_MAX_HEIGHT_PERCENT = BUILDER
            .comment("聊天区域最大高度占屏幕的百分比；0 跟随 Minecraft 设置。")
            .defineInRange("chatMaxHeightPercent", 0, 0, 100);
    public static final ModConfigSpec.IntValue CHAT_SCROLL_DURATION = BUILDER
            .comment("聊天滚动缓动时长（毫秒）。")
            .defineInRange("chatScrollDuration", 180, 80, 500);
    public static final ModConfigSpec.IntValue CHAT_ARRIVAL_DURATION = BUILDER
            .comment("新消息淡入和上移时长（毫秒）。")
            .defineInRange("chatArrivalDuration", 200, 80, 500);
    public static final ModConfigSpec.IntValue CHAT_ARRIVAL_DISTANCE = BUILDER
            .comment("新消息额外上移距离（聊天 GUI 像素）。")
            .defineInRange("chatArrivalDistance", 4, 0, 12);
    public static final ModConfigSpec.BooleanValue SIGN_TRANSLATIONS_ENABLED = BUILDER
            .comment("是否显示准星所指告示牌及外部画框接口的浮选译文。")
            .define("signTranslationsEnabled", true);
    public static final ModConfigSpec.IntValue SIGN_TRANSLATION_MAX_DISTANCE = BUILDER
            .comment("告示牌和外部显示接口的最大显示距离。")
            .defineInRange("signTranslationMaxDistance", 8, 1, 32);
    public static final ModConfigSpec.DoubleValue SIGN_TRANSLATION_SCALE = BUILDER
            .comment("告示牌和外部显示接口的译文缩放比例。")
            .defineInRange("signTranslationScale", 1.0, 0.50, 2.0);
    public static final ModConfigSpec.BooleanValue OPEN_CONFIG_KEY_ENABLED = BUILDER
            .comment("是否允许使用 F8 键唤起客户端配置界面。")
            .define("openConfigKeyEnabled", true);

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

    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    public static boolean receivesBotImages() {
        // Honour the legacy opt-out until the new UI explicitly updates both values.
        return isLoaded() && RECEIVE_IMAGE_MESSAGES.getAsBoolean() && ACCEPT_BOT_IMAGES.getAsBoolean();
    }

    public static int chatImageScale() { return isLoaded() ? CHAT_IMAGE_SCALE.getAsInt() : 100; }
    public static boolean chatAnimationsEnabled() { return !isLoaded() || CHAT_ANIMATIONS_ENABLED.getAsBoolean(); }
}
