package com.mineastr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Owns the complete chat viewport: drawing, fractional scroll, scrollbar and hit testing. */
public final class MineAstrChatViewport {
    private final MineAstrScrollState scroll = new MineAstrScrollState();
    private final MineAstrChatInsertionMotion insertion = new MineAstrChatInsertionMotion();
    private final Map<GuiMessage.Line, Double> arrivals = new WeakHashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private double scale = 1, bottom, top, width, lineHeight = 9, contentHeight, now;
    private boolean focused, logged;
    private MineAstrChatGeometry.ChatPanel panel;
    private int previousRows = -1;
    private double previousHeight = -1, previousLineHeight = -1;

    public double position() { return scroll.value(MineAstrChatEasing.now()); }
    public void rowsInserted(List<GuiMessage.Line> lines, int count, int height) {
        double time = MineAstrChatEasing.now();
        for (int index = 0; index < count; index++) arrivals.put(lines.get(index), time);
        if (scroll.target() > 0) scroll.offset(count * height);
        else if (previousRows > 0 && MineAstrClientConfig.chatAnimationsEnabled())
            insertion.push(count, time, arrivalDuration());
    }
    public void clear() { scroll.reset(); insertion.clear(); arrivals.clear(); hits.clear(); previousRows = -1; }
    public void resetScroll() { scroll.reset(); insertion.clear(); }
    public void scroll(double pixels) {
        scroll.wheel(pixels, MineAstrChatEasing.now(), MineAstrClientConfig.chatAnimationsEnabled()
                ? (MineAstrClientConfig.isLoaded() ? MineAstrClientConfig.CHAT_SCROLL_DURATION.getAsInt() : 180) : 0);
    }
    public void restorePosition(double pixels) { scroll.to(pixels, MineAstrChatEasing.now(), 0); }
    private int arrivalDuration() { return MineAstrClientConfig.isLoaded() ? MineAstrClientConfig.CHAT_ARRIVAL_DURATION.getAsInt() : 200; }

    public void render(GuiGraphics graphics, List<GuiMessage.Line> lines, int tick, int mouseX, int mouseY,
            boolean focused, int chatWidth, int viewportHeight, int height, double chatScale, boolean hidden) {
        Minecraft client = Minecraft.getInstance();
        now = MineAstrChatEasing.now();
        this.focused = focused && !hidden;
        scale = chatScale;
        panel = MineAstrChatGeometry.chatPanel(chatWidth, graphics.guiWidth(), scale);
        width = panel.textWidth(scale);
        lineHeight = height;
        bottom = (graphics.guiHeight() - 40) / scale;
        top = Math.max(0, bottom - viewportHeight);
        contentHeight = lines.size() * lineHeight;
        if (previousRows != lines.size() || previousHeight != bottom - top || previousLineHeight != lineHeight) {
            scroll.bounds(contentHeight, bottom - top, now);
            previousRows = lines.size(); previousHeight = bottom - top; previousLineHeight = lineHeight;
        }
        if (!logged) {
            logged = true;
            MineAstr.LOGGER.info("MineAstr chat system active: renderer=MineAstr continuous_scroll=true draggable_scrollbar=true layout=icon_sender_body");
        }
        hits.clear();
        MineAstrChatImages.beginFrame(panel.textLeft(), panel.contentRight());
        if (hidden || lines.isEmpty()) return;
        double offset = scroll.value(now);
        double inserted = MineAstrClientConfig.chatAnimationsEnabled() ? insertion.value(now) * height : 0;
        double baseline = Math.round(-8 * (client.options.chatLineSpacing().get() + 1) + 4 * client.options.chatLineSpacing().get());
        graphics.enableScissor(panel.left(), (int) Math.floor(top * scale),
                panel.right(), (int) Math.ceil(bottom * scale));
        graphics.pose().pushPose();
        graphics.pose().scale((float) scale, (float) scale, 1);
        graphics.pose().translate((float) (panel.textLeft() / scale), 0, 50);
        try {
            int first = Math.max(0, (int) Math.floor((offset + inserted) / height) - 1);
            int last = Math.min(lines.size(), first + (int) Math.ceil((bottom - top) / height) + 4);
            for (int index = first; index < last; index++) {
                GuiMessage.Line line = lines.get(index);
                int age = tick - line.addedTime();
                if (!focused && age >= 200) continue;
                double progress = 1;
                Double arrival = arrivals.get(line);
                if (arrival != null && MineAstrClientConfig.chatAnimationsEnabled())
                    progress = MineAstrChatEasing.gentle((now - arrival) / arrivalDuration());
                double rise = (1 - progress) * (MineAstrClientConfig.isLoaded() ? MineAstrClientConfig.CHAT_ARRIVAL_DISTANCE.getAsInt() : 4);
                double rowBottom = bottom - index * height + offset + inserted + rise;
                if (!MineAstrScrollState.intersects(rowBottom - height, height, top, bottom)) continue;
                double fade = focused ? 1 : Math.pow(Math.clamp((1 - age / 200.0) * 10, 0, 1), 2);
                float alpha = (float) (fade * progress * (client.options.chatOpacity().get() * .9 + .1));
                if (alpha * 255 <= 3) continue;
                hits.add(new Hit(index, rowBottom - height, rowBottom));
                graphics.pose().pushPose();
                graphics.pose().translate(0, (float) rowBottom, 0);
                try {
                    int background = (int) (255 * fade * progress * client.options.textBackgroundOpacity().get());
                    // Background/indicator retain the full panel; text and images share a separate content clip.
                    graphics.pose().pushPose();
                    graphics.pose().scale((float) (1 / scale), 1, 1);
                    graphics.fill(panel.left() - panel.textLeft(), -height, panel.right() - panel.textLeft(), 0, background << 24);
                    int indicator = line.tag() == null ? 0xD0D0D0 : line.tag().indicatorColor();
                    graphics.fill(panel.left() - panel.textLeft(), -height, panel.left() + 1 - panel.textLeft(), 0,
                            indicator | ((int) (alpha * 255) << 24));
                    graphics.pose().popPose();
                    graphics.enableScissor(panel.textLeft(), (int) Math.floor(top * scale), panel.contentRight(), (int) Math.ceil(bottom * scale));
                    try {
                        if (line.tag() != null) {
                            if (line.tag().icon() != null && (mouseX - panel.textLeft()) / scale >= client.font.width(line.content()) + 4
                                    && (mouseX - panel.textLeft()) / scale < client.font.width(line.content()) + 4 + line.tag().icon().width
                                    && mouseY / scale >= rowBottom - height && mouseY / scale < rowBottom)
                                line.tag().icon().draw(graphics, client.font.width(line.content()) + 4, (int) baseline + 8 - line.tag().icon().height);
                        }
                        graphics.drawString(client.font, MineAstrClientThemes.animate(line.content(), now), 0, (int) baseline,
                                0xFFFFFF | ((int) (alpha * 255) << 24));
                        var decorations = MineAstrChatLayout.decorations(line.content());
                        if (decorations.platform() != null) MineAstrChatIcons.render(graphics, decorations.platform(), (int) baseline - 1, alpha);
                    } finally { graphics.disableScissor(); }
                } finally { graphics.pose().popPose(); }
                var image = MineAstrChatLayout.imageRow(line.content());
                if (image != null) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(0, (float) rowBottom, 0);
                    try { MineAstrChatImages.renderRow(graphics, image, 0, height, (float) scale, alpha,
                            (float) rowBottom, (float) top, (float) bottom); }
                    finally { graphics.pose().popPose(); }
                }
            }
        } finally { graphics.pose().popPose(); graphics.disableScissor(); }
        renderQueue(graphics, client);
        if (focused && scroll.maximum() > 0) renderScrollbar(graphics);
    }

    private void renderQueue(GuiGraphics graphics, Minecraft client) {
        long queued = client.getChatListener().queueSize();
        if (queued <= 0) return;
        graphics.pose().pushPose();
        try {
            graphics.pose().scale((float) scale, (float) scale, 1);
            graphics.pose().translate((float) (panel.textLeft() / scale), (float) bottom, 50);
            graphics.fill(0, 0, (int) width, 9, 0x99000000);
            graphics.drawString(client.font, client.font.plainSubstrByWidth(Component.translatable("chat.queue", queued).getString(), (int) width), 0, 1, 0xFFFFFFFF);
        } finally { graphics.pose().popPose(); }
    }
    private double barX() { return panel == null ? 0 : panel.scrollbarLeft(); }
    private double thumb() { return scroll.thumbSize((bottom - top) * scale, contentHeight * scale); }
    private double thumbTop() { return scroll.thumbTop(top * scale, (bottom - top) * scale, contentHeight * scale, true, now); }
    private void renderScrollbar(GuiGraphics graphics) {
        graphics.pose().pushPose();
        try {
            graphics.pose().translate((float) barX(), (float) (top * scale), 100);
            int track = (int) Math.ceil((bottom - top) * scale);
            graphics.fill(0, 0, 6, track, 0x55202A3A);
            graphics.pose().translate(0, (float) (thumbTop() - top * scale), 0);
            graphics.fill(0, 0, 6, (int) Math.ceil(thumb()), scroll.dragging() ? 0xFF72E6C1 : 0xCC8095B0);
        } finally { graphics.pose().popPose(); }
    }
    public boolean click(double mouseX, double mouseY, int button) {
        if (!focused || button != 0 || scroll.maximum() <= 0 || mouseX < barX() - 2 || mouseX > barX() + 8
                || mouseY < top * scale || mouseY >= bottom * scale) return false;
        now = MineAstrChatEasing.now();
        scroll.beginDrag(mouseY, thumbTop(), thumb());
        drag(mouseY, button);
        return true;
    }
    public boolean drag(double mouseY, int button) {
        if (button != 0 || !scroll.dragging()) return false;
        scroll.drag(mouseY, top * scale, (bottom - top) * scale, contentHeight * scale, true, MineAstrChatEasing.now());
        return true;
    }
    public boolean release(int button) { return button == 0 && scroll.endDrag(); }
    public int lineAt(double chatX, double chatY) {
        if (!focused || chatX < 0 || chatX > width || chatY < 0 || chatY * lineHeight >= bottom - top) return -1;
        double y = bottom - chatY * lineHeight;
        for (Hit hit : hits) if (y >= hit.top && y < hit.bottom) return hit.index;
        return -1;
    }
    public Style styleAt(List<GuiMessage.Line> lines, double mouseX, double mouseY) {
        if (panel == null || mouseX < panel.textLeft() || mouseX >= panel.contentRight()) return null;
        int line = lineAt((mouseX - panel.textLeft()) / scale, (bottom - mouseY / scale) / lineHeight);
        return line < 0 || line >= lines.size() ? null : Minecraft.getInstance().font.getSplitter()
                .componentStyleAtWidth(lines.get(line).content(), (int) Math.floor((mouseX - panel.textLeft()) / scale));
    }
    public GuiMessageTag tagAt(List<GuiMessage.Line> lines, double mouseX, double mouseY) {
        if (panel == null || mouseX < panel.left() || mouseX >= panel.contentRight()) return null;
        double x = (mouseX - panel.textLeft()) / scale;
        int line = lineAt(Math.max(0, x), (bottom - mouseY / scale) / lineHeight);
        if (line < 0 || line >= lines.size()) return null;
        // Vanilla tag is associated with the bottom line of the original message.
        while (line >= 0 && !lines.get(line).endOfEntry()) line--;
        if (line < 0) return null;
        GuiMessage.Line item = lines.get(line);
        GuiMessageTag tag = item.tag();
        if (tag == null) return null;
        int iconLeft = Minecraft.getInstance().font.width(item.content()) + 4;
        return x < 0 || (tag.icon() != null && x >= iconLeft && x < iconLeft + tag.icon().width) ? tag : null;
    }
    private record Hit(int index, double top, double bottom) {}
}
