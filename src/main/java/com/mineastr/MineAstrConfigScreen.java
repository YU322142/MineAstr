package com.mineastr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
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
    private int scrollOffset;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private ScrollBar scrollBar;

    public MineAstrConfigScreen(Screen parent) {
        super(Component.translatable("screen.mineastr.config.title"));
        this.parent = parent;
        loadValues();
    }

    private void loadValues() {
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
        return contentTop + rowIndex * ROW_HEIGHT - scrollOffset;
    }

    private int maxScroll() {
        return Math.max(0, rowWidgets.size() * ROW_HEIGHT - (contentBottom - contentTop));
    }

    private void setScrollOffset(int value) {
        scrollOffset = Mth.clamp(value, 0, maxScroll());
        repositionRows();
    }

    private void repositionRows() {
        int index = 0;
        for (AbstractWidget widget : rowWidgets) {
            widget.setY(rowY(index));
            widget.visible = widget.getY() >= contentTop
                    && widget.getY() + widget.getHeight() <= contentBottom;
            index++;
        }
        if (scrollBar != null) {
            scrollBar.setScroll(scrollOffset);
        }
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

        // Row 0: 接收图片消息
        rowWidgets.add(addRenderableWidget(new ToggleButton(
                controlLeft, rowY(rowIndex), controlWidth, 20,
                receiveImageMessages, v -> receiveImageMessages = v).widget()));
        rowIndex++;

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
        scrollOffset = Mth.clamp(scrollOffset, 0, Math.max(0, contentHeight - viewportHeight));
        scrollBar = new ScrollBar(
                panelLeft + panelWidth - 10,
                contentTop,
                SCROLLBAR_WIDTH,
                viewportHeight,
                contentHeight,
                scrollOffset,
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
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.save"), button -> saveAndClose())
                .bounds(buttonStartX + (buttonWidth + buttonGap) * 2, buttonY, buttonWidth, 20).build());

        repositionRows();
    }

    private void saveAndClose() {
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
        MineAstrClientConfig.SPEC.save();
        saveSnapshot();
        MineAstrClient.sendTranslationPreferences();
        MineAstrClient.sendBotImagePreferences();
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // 鼠标滚轮仅限翻页，不分发给子控件（避免滚轮改变开关/滑块值）
        setScrollOffset(scrollOffset - (int) Math.round(verticalAmount * ROW_HEIGHT));
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        graphics.enableScissor(panelLeft + 4, contentTop, panelLeft + panelWidth - 8, contentBottom);
        for (var renderable : this.renderables) {
            if (rowWidgets.contains(renderable)) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();

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
                "screen.mineastr.config.receive_image_messages.label",
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
        for (int i = 0; i < labels.length; i++) {
            int labelY = rowY(i) + 5;
            if (labelY + 9 >= contentTop && labelY <= contentBottom) {
                graphics.drawString(font, Component.translatable(labels[i]), left + 16, labelY, TEXT, false);
            }
        }
        graphics.disableScissor();
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

    private static final class ScrollBar extends AbstractWidget {
        private final int viewportHeight;
        private final int contentHeight;
        private int scroll;
        private final IntConsumer onScroll;
        private boolean dragging;

        private ScrollBar(
                int x,
                int y,
                int width,
                int viewportHeight,
                int contentHeight,
                int scroll,
                IntConsumer onScroll) {
            super(x, y, width, viewportHeight, Component.empty());
            this.viewportHeight = viewportHeight;
            this.contentHeight = contentHeight;
            this.scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - viewportHeight));
            this.onScroll = onScroll;
        }

        private int maxScroll() {
            return Math.max(0, contentHeight - viewportHeight);
        }

        private int thumbHeight() {
            if (contentHeight <= viewportHeight) {
                return viewportHeight;
            }
            return Math.max(16, Math.round(viewportHeight * (viewportHeight / (float) contentHeight)));
        }

        private int thumbY() {
            int max = maxScroll();
            if (max <= 0) {
                return getY();
            }
            return getY() + Math.round(scroll / (float) max * (viewportHeight - thumbHeight()));
        }

        void setScroll(int scroll) {
            this.scroll = Mth.clamp(scroll, 0, maxScroll());
        }

        private void updateScrollFromMouse(double mouseY) {
            int max = maxScroll();
            if (max <= 0) {
                return;
            }
            int track = viewportHeight - thumbHeight();
            if (track <= 0) {
                return;
            }
            int target = Math.round((float) ((mouseY - getY() - thumbHeight() / 2.0) / track) * max);
            scroll = Mth.clamp(target, 0, max);
            onScroll.accept(scroll);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            updateScrollFromMouse(mouseY);
            dragging = true;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!dragging) {
                return false;
            }
            updateScrollFromMouse(mouseY);
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            dragging = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0x66202A3A);
            int thumbColor = (dragging || isHoveredOrFocused()) ? 0xFF72E6C1 : 0xFF52657C;
            int ty = thumbY();
            int th = thumbHeight();
            graphics.fill(getX(), ty, getRight(), ty + th, thumbColor);
            graphics.fill(getX(), ty, getX() + 1, ty + th, 0x33FFFFFF);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
