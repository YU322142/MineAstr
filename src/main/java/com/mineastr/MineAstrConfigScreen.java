package com.mineastr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

public final class MineAstrConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int PANEL_HEIGHT = 340;
    private static final int ROW_HEIGHT = 26;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int CONTENT_PAD_TOP = 38;
    private static final int BOTTOM_BUTTON_AREA_HEIGHT = 40;
    private static final int CONTENT_PAD_BOTTOM = BOTTOM_BUTTON_AREA_HEIGHT;
    private static final int ACCENT = 0xFF72E6C1;
    private static final int TEXT = 0xFFF3F7FF;
    private static final int MUTED = 0xFFA8B4C8;

    private final Screen parent;

    // 当前编辑值
    private boolean receiveImageMessages;
    private boolean openConfigKeyEnabled;
    private MineAstrClientConfig.ScreenshotMode screenshotMode;
    private boolean gameTranslationsEnabled;
    private boolean showOriginalTranslatedMessages;
    private boolean signTranslationsEnabled;
    private int signTranslationMaxDistance;
    private double signTranslationScale;
    private int maxWidth;
    private int maxHeight;
    private double jpegQuality;
    private int maxBytes;

    private int themeColor, themeSecond, themeThird, themeStops, themePeriod, themeEditingStop = 1;
    private ThemeSettings savedThemeSettings;
    private EditBox themeHex;
    private Button saveButton;
    private final List<ValueSlider> themeChannels = new ArrayList<>();
    private boolean updatingTheme;
    private record ThemeSettings(int color, int second, int third, int stops, int period) {
        static ThemeSettings load() { return new ThemeSettings(MineAstrClientConfig.PLAYER_THEME_COLOR.getAsInt(), MineAstrClientConfig.PLAYER_THEME_SECOND.getAsInt(), MineAstrClientConfig.PLAYER_THEME_THIRD.getAsInt(), MineAstrClientConfig.PLAYER_THEME_STOPS.getAsInt(), MineAstrClientConfig.PLAYER_THEME_PERIOD.getAsInt()); }
        static ThemeSettings defaults() { return new ThemeSettings(MineAstrClientConfig.PLAYER_THEME_COLOR.getDefault(), MineAstrClientConfig.PLAYER_THEME_SECOND.getDefault(), MineAstrClientConfig.PLAYER_THEME_THIRD.getDefault(), MineAstrClientConfig.PLAYER_THEME_STOPS.getDefault(), MineAstrClientConfig.PLAYER_THEME_PERIOD.getDefault()); }
    }
    private ThemeSettings currentThemeSettings() { return new ThemeSettings(themeColor, themeSecond, themeThird, themeStops, themePeriod); }
    private void applyThemeSettings(ThemeSettings settings) {
        themeColor = settings.color(); themeSecond = settings.second(); themeThird = settings.third(); themeStops = settings.stops(); themePeriod = settings.period();
    }
    private boolean themeValid() {
        boolean hexValid = themeHex == null || themeHex.getValue().matches("#?[0-9a-fA-F]{6}");
        return hexValid && new MineAstrPayloads.PlayerTheme(new java.util.UUID(0, 0), "", themeColor, themeSecond, themeThird, themeStops, themePeriod).valid();
    }
    private int selectedThemeColor() { return themeEditingStop == 1 ? themeColor : themeEditingStop == 2 ? themeSecond : themeThird; }
    private void setThemeColor(int color, boolean syncHex) {
        if (themeEditingStop == 1) themeColor = color; else if (themeEditingStop == 2) themeSecond = color; else themeThird = color;
        updatingTheme = true;
        try {
            if (syncHex && themeHex != null) themeHex.setValue(MineAstrThemeColor.hex(color));
            for (int index = 0; index < themeChannels.size(); index++) themeChannels.get(index).setActual((color >>> (16 - 8 * index)) & 255);
        } finally { updatingTheme = false; }
    }

    private int chatImageScale, chatMaxHeight, chatScrollDuration, chatArrivalDuration, chatArrivalDistance;
    private boolean chatAnimations;
    private ChatSettings savedChatSettings;

    private record ChatSettings(int imageScale, int maxHeight, boolean animations,
            int scrollDuration, int arrivalDuration, int arrivalDistance) {
        static ChatSettings load() {
            return new ChatSettings(MineAstrClientConfig.CHAT_IMAGE_SCALE.getAsInt(),
                    MineAstrClientConfig.CHAT_MAX_HEIGHT_PERCENT.getAsInt(),
                    MineAstrClientConfig.CHAT_ANIMATIONS_ENABLED.getAsBoolean(),
                    MineAstrClientConfig.CHAT_SCROLL_DURATION.getAsInt(),
                    MineAstrClientConfig.CHAT_ARRIVAL_DURATION.getAsInt(),
                    MineAstrClientConfig.CHAT_ARRIVAL_DISTANCE.getAsInt());
        }
        static ChatSettings defaults() {
            return new ChatSettings(MineAstrClientConfig.CHAT_IMAGE_SCALE.getDefault(),
                    MineAstrClientConfig.CHAT_MAX_HEIGHT_PERCENT.getDefault(),
                    MineAstrClientConfig.CHAT_ANIMATIONS_ENABLED.getDefault(),
                    MineAstrClientConfig.CHAT_SCROLL_DURATION.getDefault(),
                    MineAstrClientConfig.CHAT_ARRIVAL_DURATION.getDefault(),
                    MineAstrClientConfig.CHAT_ARRIVAL_DISTANCE.getDefault());
        }
    }

    private ChatSettings currentChatSettings() {
        return new ChatSettings(chatImageScale, chatMaxHeight, chatAnimations,
                chatScrollDuration, chatArrivalDuration, chatArrivalDistance);
    }

    private void applyChatSettings(ChatSettings settings) {
        chatImageScale = settings.imageScale();
        chatMaxHeight = settings.maxHeight();
        chatAnimations = settings.animations();
        chatScrollDuration = settings.scrollDuration();
        chatArrivalDuration = settings.arrivalDuration();
        chatArrivalDistance = settings.arrivalDistance();
    }

    // 已保存快照（用于撤销）
    private boolean savedReceiveImageMessages;
    private boolean savedOpenConfigKeyEnabled;
    private MineAstrClientConfig.ScreenshotMode savedScreenshotMode;
    private boolean savedGameTranslationsEnabled;
    private boolean savedShowOriginalTranslatedMessages;
    private boolean savedSignTranslationsEnabled;
    private int savedSignTranslationMaxDistance;
    private double savedSignTranslationScale;
    private int savedMaxWidth;
    private int savedMaxHeight;
    private double savedJpegQuality;
    private int savedMaxBytes;

    private int panelLeft;
    private int panelTop;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private double scrollOffset;
    private final MineAstrScrollState contentScroll = new MineAstrScrollState();
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private ScrollBar scrollBar;

    public MineAstrConfigScreen(Screen parent) {
        super(Component.translatable("screen.mineastr.config.title"));
        this.parent = parent;
        loadValues();
    }

    private void loadValues() {
        applyThemeSettings(ThemeSettings.load());
        applyChatSettings(ChatSettings.load());
        receiveImageMessages = MineAstrClientConfig.receivesBotImages();
        openConfigKeyEnabled = MineAstrClientConfig.OPEN_CONFIG_KEY_ENABLED.getAsBoolean();
        screenshotMode = MineAstrClientConfig.SCREENSHOT_MODE.get();
        gameTranslationsEnabled = MineAstrClientConfig.GAME_TRANSLATIONS_ENABLED.getAsBoolean();
        showOriginalTranslatedMessages = MineAstrClientConfig.SHOW_ORIGINAL_TRANSLATED_MESSAGES.getAsBoolean();
        signTranslationsEnabled = MineAstrClientConfig.SIGN_TRANSLATIONS_ENABLED.getAsBoolean();
        signTranslationMaxDistance = MineAstrClientConfig.SIGN_TRANSLATION_MAX_DISTANCE.getAsInt();
        signTranslationScale = MineAstrClientConfig.SIGN_TRANSLATION_SCALE.getAsDouble();
        maxWidth = MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.getAsInt();
        maxHeight = MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.getAsInt();
        jpegQuality = MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.getAsDouble();
        maxBytes = MineAstrClientConfig.SCREENSHOT_MAX_BYTES.getAsInt();
        saveSnapshot();
    }

    /** 将当前编辑值保存为撤销快照。 */
    private void saveSnapshot() {
        savedThemeSettings = currentThemeSettings();
        savedChatSettings = currentChatSettings();
        savedReceiveImageMessages = receiveImageMessages;
        savedOpenConfigKeyEnabled = openConfigKeyEnabled;
        savedScreenshotMode = screenshotMode;
        savedGameTranslationsEnabled = gameTranslationsEnabled;
        savedShowOriginalTranslatedMessages = showOriginalTranslatedMessages;
        savedSignTranslationsEnabled = signTranslationsEnabled;
        savedSignTranslationMaxDistance = signTranslationMaxDistance;
        savedSignTranslationScale = signTranslationScale;
        savedMaxWidth = maxWidth;
        savedMaxHeight = maxHeight;
        savedJpegQuality = jpegQuality;
        savedMaxBytes = maxBytes;
    }

    /** 撤销：恢复到上次保存的快照。 */
    private void undoChanges() {
        applyThemeSettings(savedThemeSettings);
        applyChatSettings(savedChatSettings);
        receiveImageMessages = savedReceiveImageMessages;
        openConfigKeyEnabled = savedOpenConfigKeyEnabled;
        screenshotMode = savedScreenshotMode;
        gameTranslationsEnabled = savedGameTranslationsEnabled;
        showOriginalTranslatedMessages = savedShowOriginalTranslatedMessages;
        signTranslationsEnabled = savedSignTranslationsEnabled;
        signTranslationMaxDistance = savedSignTranslationMaxDistance;
        signTranslationScale = savedSignTranslationScale;
        maxWidth = savedMaxWidth;
        maxHeight = savedMaxHeight;
        jpegQuality = savedJpegQuality;
        maxBytes = savedMaxBytes;
        rebuildWidgets();
    }

    /** 客户端配置默认值字典。 */
    private record ClientConfigDefaults(
            boolean receiveImageMessages,
            boolean openConfigKeyEnabled,
            MineAstrClientConfig.ScreenshotMode screenshotMode,
            boolean gameTranslationsEnabled,
            boolean showOriginalTranslatedMessages,
            boolean signTranslationsEnabled,
            int signTranslationMaxDistance,
            double signTranslationScale,
            int maxWidth,
            int maxHeight,
            double jpegQuality,
            int maxBytes) {
        static ClientConfigDefaults fromConfig() {
            return new ClientConfigDefaults(
                    MineAstrClientConfig.RECEIVE_IMAGE_MESSAGES.getDefault(),
                    MineAstrClientConfig.OPEN_CONFIG_KEY_ENABLED.getDefault(),
                    MineAstrClientConfig.SCREENSHOT_MODE.getDefault(),
                    MineAstrClientConfig.GAME_TRANSLATIONS_ENABLED.getDefault(),
                    MineAstrClientConfig.SHOW_ORIGINAL_TRANSLATED_MESSAGES.getDefault(),
                    MineAstrClientConfig.SIGN_TRANSLATIONS_ENABLED.getDefault(),
                    MineAstrClientConfig.SIGN_TRANSLATION_MAX_DISTANCE.getDefault(),
                    MineAstrClientConfig.SIGN_TRANSLATION_SCALE.getDefault(),
                    MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.getDefault(),
                    MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.getDefault(),
                    MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.getDefault(),
                    MineAstrClientConfig.SCREENSHOT_MAX_BYTES.getDefault());
        }
    }

    /** 重置客户端配置：将当前编辑值恢复为默认值（不写入文件，需点保存生效）。 */
    private void resetClientConfigToDefaults() {
        applyThemeSettings(ThemeSettings.defaults());
        applyChatSettings(ChatSettings.defaults());
        ClientConfigDefaults defaults = ClientConfigDefaults.fromConfig();
        receiveImageMessages = defaults.receiveImageMessages();
        openConfigKeyEnabled = defaults.openConfigKeyEnabled();
        screenshotMode = defaults.screenshotMode();
        gameTranslationsEnabled = defaults.gameTranslationsEnabled();
        showOriginalTranslatedMessages = defaults.showOriginalTranslatedMessages();
        signTranslationsEnabled = defaults.signTranslationsEnabled();
        signTranslationMaxDistance = defaults.signTranslationMaxDistance();
        signTranslationScale = defaults.signTranslationScale();
        maxWidth = defaults.maxWidth();
        maxHeight = defaults.maxHeight();
        jpegQuality = defaults.jpegQuality();
        maxBytes = defaults.maxBytes();
        rebuildWidgets();
    }

    private int rowY(int rowIndex) {
        return contentTop + rowIndex * ROW_HEIGHT - (int) Math.floor(scrollOffset);
    }

    private int maxScroll() {
        return Math.max(0, rowWidgets.size() * ROW_HEIGHT - (contentBottom - contentTop));
    }

    private void setScrollOffset(double value) {
        contentScroll.to(value, MineAstrChatEasing.now(), 0);
        scrollOffset = Math.clamp(value, 0, maxScroll());
        repositionRows();
    }

    private void repositionRows() {
        int index = 0;
        for (AbstractWidget widget : rowWidgets) {
            widget.setY(rowY(index));
            widget.visible = MineAstrScrollState.intersects(widget.getY() - scrollFraction(), widget.getHeight(), contentTop, contentBottom);
            index++;
        }
    }

    private double scrollFraction() { return scrollOffset - Math.floor(scrollOffset); }

    private void updateScroll() {
        scrollOffset = contentScroll.value(MineAstrChatEasing.now());
        repositionRows();
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        panelLeft = (width - panelWidth) / 2;
        panelHeight = Math.max(1, Math.min(PANEL_HEIGHT, height - 16));
        panelTop = Math.max(8, (height - panelHeight) / 2);
        int controlLeft = panelLeft + 130;
        int controlWidth = panelWidth - 150;
        contentTop = panelTop + CONTENT_PAD_TOP;
        contentBottom = panelTop + panelHeight - CONTENT_PAD_BOTTOM;

        rowWidgets.clear();
        int rowIndex = 0;

        themeChannels.clear();
        rowWidgets.add(addRenderableWidget(new ValueCycleButton<>(controlLeft, rowY(rowIndex++), controlWidth, 20,
                List.of(1, 2, 3), themeStops, count -> Component.translatable("screen.mineastr.config.theme_mode." + count),
                count -> { themeStops = count; themeEditingStop = Math.min(themeEditingStop, count); rebuildWidgets(); }).widget()));
        var stops = new ArrayList<Integer>(); for (int stop = 1; stop <= themeStops; stop++) stops.add(stop);
        rowWidgets.add(addRenderableWidget(new ValueCycleButton<>(controlLeft, rowY(rowIndex++), controlWidth, 20,
                stops, themeEditingStop, stop -> Component.translatable("screen.mineastr.config.theme_stop", stop),
                stop -> { themeEditingStop = stop; rebuildWidgets(); }).widget()));
        themeHex = new EditBox(font, controlLeft, rowY(rowIndex++), controlWidth, 20, Component.translatable("screen.mineastr.config.theme_hex.label"));
        themeHex.setMaxLength(7); themeHex.setFilter(value -> value.matches("#?[0-9a-fA-F]{0,6}"));
        themeHex.setValue(MineAstrThemeColor.hex(selectedThemeColor()));
        themeHex.setTooltip(Tooltip.create(Component.translatable("screen.mineastr.config.theme_help")));
        themeHex.setResponder(value -> {
            if (updatingTheme || !value.matches("#?[0-9a-fA-F]{6}")) return;
            setThemeColor(MineAstrThemeColor.parse(value), false);
        });
        rowWidgets.add(addRenderableWidget(themeHex));
        for (int channel = 0; channel < 3; channel++) {
            int shift = 16 - 8 * channel;
            var slider = new ValueSlider(controlLeft, rowY(rowIndex++), controlWidth,
                    "screen.mineastr.config.theme_channel", 0, 255, (selectedThemeColor() >>> shift) & 255,
                    value -> setThemeColor((selectedThemeColor() & ~(255 << shift)) | ((int) Math.round(value) << shift), true),
                    value -> Long.toString(Math.round(value)));
            themeChannels.add(slider); rowWidgets.add(addRenderableWidget(slider));
        }
        rowWidgets.add(addRenderableWidget(new ThemePreview(controlLeft, rowY(rowIndex++), controlWidth)));
        rowWidgets.add(addRenderableWidget(new ValueSlider(controlLeft, rowY(rowIndex++), controlWidth,
                "screen.mineastr.config.theme_period", 4, 20, themePeriod / 1000.0,
                value -> themePeriod = (int) Math.round(value * 1000), value -> String.format(Locale.ROOT, "%.1f", value))));

        // 接收图片消息
        rowWidgets.add(addRenderableWidget(new ToggleButton(
                controlLeft, rowY(rowIndex), controlWidth, 20,
                receiveImageMessages, v -> receiveImageMessages = v).widget()));
        rowIndex++;

        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex++), controlWidth,
                "screen.mineastr.config.chat_image_scale", 50, 300, chatImageScale,
                value -> chatImageScale = (int) Math.round(value),
                value -> Integer.toString((int) Math.round(value)))));
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex++), controlWidth,
                "screen.mineastr.config.chat_max_height", 0, 100, chatMaxHeight,
                value -> chatMaxHeight = (int) Math.round(value),
                value -> Math.round(value) == 0 ? Component.translatable("screen.mineastr.config.follow_minecraft").getString()
                        : Component.translatable("screen.mineastr.config.percent", Math.round(value)).getString())));
        rowWidgets.add(addRenderableWidget(new ToggleButton(
                controlLeft, rowY(rowIndex++), controlWidth, 20,
                chatAnimations, value -> chatAnimations = value).widget()));
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex++), controlWidth,
                "screen.mineastr.config.chat_scroll_duration", 80, 500, chatScrollDuration,
                value -> chatScrollDuration = (int) Math.round(value),
                value -> Long.toString(Math.round(value)))));
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex++), controlWidth,
                "screen.mineastr.config.chat_arrival_duration", 80, 500, chatArrivalDuration,
                value -> chatArrivalDuration = (int) Math.round(value),
                value -> Long.toString(Math.round(value)))));
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex++), controlWidth,
                "screen.mineastr.config.chat_arrival_distance", 0, 12, chatArrivalDistance,
                value -> chatArrivalDistance = (int) Math.round(value),
                value -> Long.toString(Math.round(value)))));

        // Row 1: F8 唤起配置界面
        rowWidgets.add(addRenderableWidget(new ToggleButton(
                controlLeft, rowY(rowIndex), controlWidth, 20,
                openConfigKeyEnabled, v -> openConfigKeyEnabled = v).widget()));
        rowIndex++;

        // Row 2: 截图授权策略
        rowWidgets.add(addRenderableWidget(new ValueCycleButton<>(
                controlLeft, rowY(rowIndex), controlWidth, 20,
                List.of(MineAstrClientConfig.ScreenshotMode.values()),
                screenshotMode,
                mode -> Component.translatable("screen.mineastr.config.mode." + mode.name().toLowerCase(Locale.ROOT)),
                value -> screenshotMode = value).widget()));
        rowIndex++;

        // Row 3: 显示游戏内译文
        rowWidgets.add(addRenderableWidget(new ToggleButton(
                controlLeft, rowY(rowIndex), controlWidth, 20,
                gameTranslationsEnabled, v -> gameTranslationsEnabled = v).widget()));
        rowIndex++;

        // Row 4: 译文下方显示原文
        rowWidgets.add(addRenderableWidget(new ToggleButton(
                controlLeft, rowY(rowIndex), controlWidth, 20,
                showOriginalTranslatedMessages, v -> showOriginalTranslatedMessages = v).widget()));
        rowIndex++;

        // Row 5: 显示告示牌/画框浮选译文
        rowWidgets.add(addRenderableWidget(new ToggleButton(
                controlLeft, rowY(rowIndex), controlWidth, 20,
                signTranslationsEnabled, v -> signTranslationsEnabled = v).widget()));
        rowIndex++;

        // Row 6: 浮选译文最大距离
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex), controlWidth,
                "screen.mineastr.config.sign_distance", 1, 32, signTranslationMaxDistance,
                value -> signTranslationMaxDistance = (int) Math.round(value),
                value -> Integer.toString((int) Math.round(value)))));
        rowIndex++;

        // Row 7: 浮选译文大小
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex), controlWidth,
                "screen.mineastr.config.sign_scale", 0.50, 2.0, signTranslationScale,
                value -> signTranslationScale = value,
                value -> Long.toString(Math.round(value * 100)))));
        rowIndex++;

        // Row 8: 最大宽度
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex), controlWidth,
                "screen.mineastr.config.width", 64, 1024, maxWidth,
                value -> maxWidth = (int) Math.round(value), value -> Integer.toString((int) Math.round(value)))));
        rowIndex++;

        // Row 9: 最大高度
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex), controlWidth,
                "screen.mineastr.config.height", 36, 1024, maxHeight,
                value -> maxHeight = (int) Math.round(value), value -> Integer.toString((int) Math.round(value)))));
        rowIndex++;

        // Row 10: JPEG 质量
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex), controlWidth,
                "screen.mineastr.config.quality", 0.10, 0.95, jpegQuality,
                value -> jpegQuality = value, value -> Long.toString(Math.round(value * 100)))));
        rowIndex++;

        // Row 11: 文件大小上限
        rowWidgets.add(addRenderableWidget(new ValueSlider(
                controlLeft, rowY(rowIndex), controlWidth,
                "screen.mineastr.config.bytes", 8192, 524288, maxBytes,
                value -> maxBytes = roundToStep((int) Math.round(value), 1024),
                value -> Integer.toString(roundToStep((int) Math.round(value), 1024) / 1024))));
        rowIndex++;

        // Row 12: 本地服务端
        rowWidgets.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.mineastr.config.local_server.button"),
                button -> minecraft.setScreen(new MineAstrLocalServerConfigScreen(this)))
                .bounds(controlLeft, rowY(rowIndex), controlWidth, 20).build()));
        rowIndex++;

        // Row 13: 重置客户端配置
        rowWidgets.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.mineastr.config.reset_client.button"),
                button -> resetClientConfigToDefaults())
                .bounds(controlLeft, rowY(rowIndex), controlWidth, 20).build()));
        rowIndex++;

        // 滚动条
        int viewportHeight = contentBottom - contentTop;
        int contentHeight = rowIndex * ROW_HEIGHT;
        contentScroll.bounds(contentHeight, viewportHeight, MineAstrChatEasing.now());
        scrollOffset = Math.clamp(scrollOffset, 0, Math.max(0, contentHeight - viewportHeight));
        contentScroll.to(scrollOffset, MineAstrChatEasing.now(), 0);
        scrollBar = new ScrollBar(
                panelLeft + panelWidth - 10,
                contentTop,
                SCROLLBAR_WIDTH,
                viewportHeight,
                contentHeight,
                contentScroll,
                this::setScrollOffset);
        addRenderableWidget(scrollBar);

        // 底部居中三按钮：取消 / 撤销 / 保存（固定在面板底部按钮区域，不随滚动移动）
        int buttonAreaTop = panelTop + panelHeight - BOTTOM_BUTTON_AREA_HEIGHT;
        int buttonY = buttonAreaTop + (BOTTOM_BUTTON_AREA_HEIGHT - 20) / 2;
        int buttonGap = 10;
        int buttonWidth = Math.min(110, (panelWidth - 48 - buttonGap * 2) / 3);
        int totalButtonWidth = buttonWidth * 3 + buttonGap * 2;
        int buttonStartX = panelLeft + (panelWidth - totalButtonWidth) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(buttonStartX, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.undo"), button -> undoChanges())
                .bounds(buttonStartX + buttonWidth + buttonGap, buttonY, buttonWidth, 20).build());
        saveButton = addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.save"), button -> saveAndClose())
                .bounds(buttonStartX + (buttonWidth + buttonGap) * 2, buttonY, buttonWidth, 20).build());

        repositionRows();
    }

    private void saveAndClose() {
        if (!themeValid()) return;
        MineAstrClientConfig.PLAYER_THEME_COLOR.set(themeColor);
        MineAstrClientConfig.PLAYER_THEME_SECOND.set(themeSecond);
        MineAstrClientConfig.PLAYER_THEME_THIRD.set(themeThird);
        MineAstrClientConfig.PLAYER_THEME_STOPS.set(themeStops);
        MineAstrClientConfig.PLAYER_THEME_PERIOD.set(themePeriod);
        MineAstrClientConfig.RECEIVE_IMAGE_MESSAGES.set(receiveImageMessages);
        MineAstrClientConfig.ACCEPT_BOT_IMAGES.set(receiveImageMessages);
        MineAstrClientConfig.OPEN_CONFIG_KEY_ENABLED.set(openConfigKeyEnabled);
        MineAstrClientConfig.SCREENSHOT_MODE.set(screenshotMode);
        MineAstrClientConfig.GAME_TRANSLATIONS_ENABLED.set(gameTranslationsEnabled);
        MineAstrClientConfig.SHOW_ORIGINAL_TRANSLATED_MESSAGES.set(showOriginalTranslatedMessages);
        MineAstrClientConfig.SIGN_TRANSLATIONS_ENABLED.set(signTranslationsEnabled);
        MineAstrClientConfig.SIGN_TRANSLATION_MAX_DISTANCE.set(signTranslationMaxDistance);
        MineAstrClientConfig.SIGN_TRANSLATION_SCALE.set(signTranslationScale);
        MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.set(maxWidth);
        MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.set(maxHeight);
        MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.set(jpegQuality);
        MineAstrClientConfig.SCREENSHOT_MAX_BYTES.set(maxBytes);
        MineAstrClientConfig.CHAT_IMAGE_SCALE.set(chatImageScale);
        MineAstrClientConfig.CHAT_MAX_HEIGHT_PERCENT.set(chatMaxHeight);
        MineAstrClientConfig.CHAT_ANIMATIONS_ENABLED.set(chatAnimations);
        MineAstrClientConfig.CHAT_SCROLL_DURATION.set(chatScrollDuration);
        MineAstrClientConfig.CHAT_ARRIVAL_DURATION.set(chatArrivalDuration);
        MineAstrClientConfig.CHAT_ARRIVAL_DISTANCE.set(chatArrivalDistance);
        MineAstrClientConfig.SPEC.save();
        minecraft.gui.getChat().rescaleChat();
        saveSnapshot();
        MineAstrClient.sendTranslationPreferences();
        MineAstrClient.sendBotImagePreferences();
        MineAstrClient.sendThemePreferences(true);
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // 鼠标滚轮仅限翻页，不分发给子控件（避免滚轮改变开关/滑块值）
        contentScroll.wheel(-Math.clamp(verticalAmount, -8, 8) * ROW_HEIGHT * .85,
                MineAstrChatEasing.now(), chatAnimations ? chatScrollDuration : 0);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        updateScroll();
        for (var child : children()) {
            boolean row = rowWidgets.contains(child);
            if (row && (mouseY < contentTop || mouseY >= contentBottom)) continue;
            if (child.mouseClicked(mouseX, row ? mouseY + scrollFraction() : mouseY, button)) {
                setFocused(child);
                if (button == 0) setDragging(true);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (isDragging() && button == 0 && getFocused() != null && rowWidgets.contains(getFocused()))
            return getFocused().mouseDragged(mouseX, mouseY + scrollFraction(), button, dx, dy);
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (getFocused() != null && rowWidgets.contains(getFocused())) {
            setDragging(false);
            return getFocused().mouseReleased(mouseX, mouseY + scrollFraction(), button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        // Focus navigation can reach the next offscreen control and scroll it into view.
        if (key == 258) for (var widget : rowWidgets) widget.visible = true;
        boolean handled = super.keyPressed(key, scan, modifiers);
        if ((key == 258 || key == 264 || key == 265) && getFocused() instanceof AbstractWidget widget && rowWidgets.contains(widget)) {
            double y = widget.getY() - scrollFraction();
            if (y < contentTop) contentScroll.wheel(y - contentTop, MineAstrChatEasing.now(), 120);
            else if (y + widget.getHeight() > contentBottom)
                contentScroll.wheel(y + widget.getHeight() - contentBottom, MineAstrChatEasing.now(), 120);
        }
        repositionRows();
        return handled;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateScroll();
        if (saveButton != null) {
            saveButton.active = themeValid();
            saveButton.setTooltip(saveButton.active ? null : Tooltip.create(Component.translatable("screen.mineastr.config.theme_invalid")));
        }
        renderBackground(graphics, mouseX, mouseY, partialTick);

        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        graphics.enableScissor(panelLeft + 4, contentTop, panelLeft + panelWidth - 8, contentBottom);
        graphics.pose().pushPose();
        graphics.pose().translate(0, (float) -scrollFraction(), 0);
        try {
            for (var renderable : this.renderables) {
                if (rowWidgets.contains(renderable)) {
                    renderable.render(graphics, mouseX, mouseY >= contentTop && mouseY < contentBottom
                            ? (int) Math.floor(mouseY + scrollFraction()) : Integer.MIN_VALUE / 2, partialTick);
                }
            }
        } finally { graphics.pose().popPose(); graphics.disableScissor(); }

        for (var renderable : this.renderables) {
            if (!rowWidgets.contains(renderable)) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xFF08111F, 0xFF101D31);
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = panelLeft;
        int top = panelTop;
        graphics.fill(left - 2, top - 2, left + panelWidth + 2, top + panelHeight + 2, 0x553DE0B4);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xEE101827);
        graphics.fill(left, top, left + 4, top + panelHeight, ACCENT);

        graphics.drawString(font, title, left + 16, top + 10, TEXT, false);
        List<FormattedCharSequence> subtitle = font.split(Component.translatable("screen.mineastr.config.subtitle"), panelWidth - 32);
        if (!subtitle.isEmpty()) {
            graphics.drawString(font, subtitle.getFirst(), left + 16, top + 24, MUTED, false);
        }

        String[] labels = {
                "screen.mineastr.config.theme_mode.label",
                "screen.mineastr.config.theme_edit_stop.label",
                "screen.mineastr.config.theme_hex.label",
                "screen.mineastr.config.theme_red.label",
                "screen.mineastr.config.theme_green.label",
                "screen.mineastr.config.theme_blue.label",
                "screen.mineastr.config.theme_preview.label",
                "screen.mineastr.config.theme_period.label",
                "screen.mineastr.config.receive_image_messages.label",
                "screen.mineastr.config.chat_image_scale.label",
                "screen.mineastr.config.chat_max_height.label",
                "screen.mineastr.config.chat_animations.label",
                "screen.mineastr.config.chat_scroll_duration.label",
                "screen.mineastr.config.chat_arrival_duration.label",
                "screen.mineastr.config.chat_arrival_distance.label",
                "screen.mineastr.config.open_config_key.label",
                "screen.mineastr.config.mode.label",
                "screen.mineastr.config.translation.label",
                "screen.mineastr.config.translation_original.label",
                "screen.mineastr.config.sign_translation.label",
                "screen.mineastr.config.sign_distance.label",
                "screen.mineastr.config.sign_scale.label",
                "screen.mineastr.config.width.label",
                "screen.mineastr.config.height.label",
                "screen.mineastr.config.quality.label",
                "screen.mineastr.config.bytes.label",
                "screen.mineastr.config.local_server.label",
                "screen.mineastr.config.reset_client.label"
        };
        graphics.enableScissor(left + 4, contentTop, left + panelWidth - 8, contentBottom);
        graphics.pose().pushPose();
        graphics.pose().translate(0, (float) -scrollFraction(), 0);
        try {
            for (int i = 0; i < labels.length; i++) {
                int labelY = rowY(i) + 5;
                if (labelY + 9 >= contentTop && labelY <= contentBottom) {
                    graphics.drawString(font, Component.translatable(labels[i]), left + 16, labelY, TEXT, false);
                }
            }
        } finally { graphics.pose().popPose(); graphics.disableScissor(); }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int roundToStep(int value, int step) {
        return Math.max(step, Math.round(value / (float) step) * step);
    }

    /**
     * 自定义开关按钮：完全掌控按钮文字渲染，从代码层面阻断任何冒号或前缀的出现。
     * 按钮上仅显示纯 "开启"/"关闭"（options.on / options.off），不经过 CycleButton 的消息拼接层。
     */
    private static final class ToggleButton {
        private final Button button;
        private boolean value;

        private ToggleButton(
                int x, int y, int width, int height,
                boolean initial, Consumer<Boolean> onChange) {
            this.value = initial;
            this.button = Button.builder(
                            Component.translatable(initial ? "options.on" : "options.off"),
                            b -> {
                                ToggleButton.this.value = !ToggleButton.this.value;
                                b.setMessage(Component.translatable(
                                        ToggleButton.this.value ? "options.on" : "options.off"));
                                onChange.accept(ToggleButton.this.value);
                            })
                    .bounds(x, y, width, height)
                    .build();
        }

        Button widget() {
            return button;
        }
    }

    /**
     * 通用多态循环按钮：与 ToggleButton 同原理，完全绕开 CycleButton 渲染链。
     * 点击时在 values 列表中循环切换，按钮文字由 toText 函数直接生成，无任何前缀或冒号。
     */
    private static final class ValueCycleButton<T> {
        private final Button button;
        private final List<T> values;
        private final Function<T, Component> toText;
        private int index;

        private ValueCycleButton(
                int x, int y, int width, int height,
                List<T> values, T initial,
                Function<T, Component> toText,
                Consumer<T> onChange) {
            this.values = values;
            this.toText = toText;
            this.index = Math.max(0, values.indexOf(initial));
            this.button = Button.builder(
                            toText.apply(values.get(this.index)),
                            b -> {
                                ValueCycleButton.this.index =
                                        (ValueCycleButton.this.index + 1) % ValueCycleButton.this.values.size();
                                T newValue = ValueCycleButton.this.values.get(ValueCycleButton.this.index);
                                b.setMessage(ValueCycleButton.this.toText.apply(newValue));
                                onChange.accept(newValue);
                            })
                    .bounds(x, y, width, height)
                    .build();
        }

        Button widget() {
            return button;
        }
    }

    @FunctionalInterface
    private interface ValueConsumer {
        void accept(double value);
    }

    @FunctionalInterface
    private interface ValueFormatter {
        String format(double value);
    }

    private static final class ValueSlider extends AbstractSliderButton {
        private final String translationKey;
        private final double min;
        private final double max;
        private final ValueConsumer consumer;
        private final ValueFormatter formatter;

        private ValueSlider(
                int x,
                int y,
                int width,
                String translationKey,
                double min,
                double max,
                double initial,
                ValueConsumer consumer,
                ValueFormatter formatter) {
            super(x, y, width, 20, Component.empty(), Mth.clamp((initial - min) / (max - min), 0.0, 1.0));
            this.translationKey = translationKey;
            this.min = min;
            this.max = max;
            this.consumer = consumer;
            this.formatter = formatter;
            updateMessage();
        }

        private void setActual(double actual) { value = Mth.clamp((actual - min) / (max - min), 0.0, 1.0); updateMessage(); }

        private double actualValue() {
            return Mth.lerp(value, min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(translationKey, formatter.format(actualValue())));
        }

        @Override
        protected void applyValue() {
            consumer.accept(actualValue());
            updateMessage();
        }
    }

    private final class ThemePreview extends AbstractWidget {
        private ThemeSettings cached;
        private final int[] palette = new int[1024];
        private ThemePreview(int x, int y, int width) { super(x, y, width, 20, Component.translatable("screen.mineastr.config.theme_preview.label")); }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX()+getWidth(), getY()+20, 0xFF303030);
            if (!themeValid()) {
                graphics.drawString(font, Component.translatable("screen.mineastr.config.theme_invalid"), getX()+4, getY()+6, 0xFFFF9090, false);
                return;
            }
            ThemeSettings settings = currentThemeSettings();
            if (!settings.equals(cached)) {
                cached = settings;
                for (int index=0; index<1024; index++) palette[index] = MineAstrThemeColor.gradient(themeColor, themeSecond, themeThird, themeStops, index/1024.0);
            }
            String text = Component.translatable("screen.mineastr.config.theme_sample").getString();
            int phase = (int)((MineAstrChatEasing.now() % themePeriod) / themePeriod * 1024);
            var component = Component.empty();
            int[] points = text.codePoints().toArray();
            for (int index=0; index<points.length; index++) { int color = palette[(phase + index*256/Math.max(1,points.length)) & 1023]; component.append(Component.literal(new String(Character.toChars(points[index])))
                    .withStyle(style -> style.withBold(true).withColor(color))); }
            var lines = font.split(component, getWidth()-8);
            if (!lines.isEmpty()) graphics.drawString(font, lines.getFirst(), getX()+4, getY()+6, 0xFFFFFFFF, false);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private static final class ScrollBar extends AbstractWidget {
        private final int viewportHeight;
        private final int contentHeight;
        private final MineAstrScrollState scroll;
        private final DoubleConsumer onScroll;

        private ScrollBar(
                int x,
                int y,
                int width,
                int viewportHeight,
                int contentHeight,
                MineAstrScrollState scroll,
                DoubleConsumer onScroll) {
            super(x, y, width, viewportHeight, Component.empty());
            this.viewportHeight = viewportHeight;
            this.contentHeight = contentHeight;
            this.scroll = scroll;
            this.onScroll = onScroll;
        }

        private int maxScroll() {
            return Math.max(0, contentHeight - viewportHeight);
        }

        private double thumbHeight() {
            return scroll.thumbSize(viewportHeight, contentHeight);
        }

        private double thumbY() {
            return scroll.thumbTop(getY(), viewportHeight, contentHeight, false, MineAstrChatEasing.now());
        }

        private void updateScrollFromMouse(double mouseY) {
            scroll.drag(mouseY, getY(), viewportHeight, contentHeight, false, MineAstrChatEasing.now());
            onScroll.accept(scroll.target());
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            scroll.beginDrag(mouseY, thumbY(), thumbHeight());
            updateScrollFromMouse(mouseY);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!scroll.dragging() || button != 0) {
                return false;
            }
            updateScrollFromMouse(mouseY);
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button == 0) scroll.endDrag();
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0x66202A3A);
            int thumbColor = (scroll.dragging() || isHoveredOrFocused()) ? 0xFF72E6C1 : 0xFF52657C;
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(0, (float) thumbY(), 0);
                int th = (int) Math.ceil(thumbHeight());
                graphics.fill(getX(), 0, getRight(), th, thumbColor);
                graphics.fill(getX(), 0, getX() + 1, th, 0x33FFFFFF);
            } finally { graphics.pose().popPose(); }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
