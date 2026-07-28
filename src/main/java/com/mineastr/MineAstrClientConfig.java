package com.mineastr;

public final class MineAstrClientConfig {
    public enum ScreenshotMode {
        ASK,
        AUTO,
        DISABLED
    }

    private static final MineAstrConfigStore STORE = new MineAstrConfigStore("mineastr-client.json");

    public static final MineAstrConfigStore.BooleanValue LOCAL_WORLD_SERVER_ENABLED = STORE.bool("localWorldServerEnabled", false);
    public static final MineAstrConfigStore.BooleanValue GAME_TRANSLATIONS_ENABLED =
            STORE.bool("gameTranslationsEnabled", true);
    public static final MineAstrConfigStore.BooleanValue SHOW_ORIGINAL_TRANSLATED_MESSAGES =
            STORE.bool("showOriginalTranslatedMessages", true);
    public static final MineAstrConfigStore.EnumValue<ScreenshotMode> SCREENSHOT_MODE =
            STORE.enumValue("screenshotMode", ScreenshotMode.ASK, ScreenshotMode.class);
    public static final MineAstrConfigStore.IntValue SCREENSHOT_MAX_WIDTH = STORE.integer("screenshotMaxWidth", 240, 64, 1024);
    public static final MineAstrConfigStore.IntValue SCREENSHOT_MAX_HEIGHT = STORE.integer("screenshotMaxHeight", 135, 36, 1024);
    public static final MineAstrConfigStore.DoubleValue SCREENSHOT_JPEG_QUALITY = STORE.decimal("screenshotJpegQuality", 0.35, 0.10, 0.95);
    public static final MineAstrConfigStore.IntValue SCREENSHOT_MAX_BYTES = STORE.integer("screenshotMaxBytes", 131072, 8192, 524288);

    static final Spec SPEC = new Spec();

    public static void load() {
        STORE.load();
    }

    static final class Spec {
        void save() {
            STORE.save();
        }
    }

    private MineAstrClientConfig() {
    }
}
