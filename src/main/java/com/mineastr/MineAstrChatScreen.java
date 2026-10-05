package com.mineastr;

import com.mineastr.mixin.MineAstrChatScreenAccessor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** MineAstr input screen; native edit box, IME, history and command suggestions remain functional. */
public final class MineAstrChatScreen extends ChatScreen {
    private MutableComponent senderHeader;
    private double inputScale = 1;
    private int panelRight;
    public MineAstrChatScreen(String initial) { super(initial); }
    private MineAstrChatAccess chat() { return (MineAstrChatAccess) minecraft.gui.getChat(); }
    @Override protected void init() {
        super.init();
        inputScale = Math.max(.01, minecraft.gui.getChat().getScale());
        int logicalWidth = Math.max(1, (int) Math.floor(width / inputScale));
        int logicalHeight = Math.max(1, (int) Math.floor(height / inputScale));
        int chatWidth = Math.min((int) (minecraft.gui.getChat().getWidth() / inputScale), Math.max(1, logicalWidth - 12));
        senderHeader = MineAstrChatLayout.senderHeader(Component.literal(minecraft.player.getGameProfile().getName()), "minecraft", chatWidth);
        panelRight = Math.min(logicalWidth - 2, chatWidth + 12);
        input.setX(4 + font.width(senderHeader));
        input.setY(logicalHeight - 12);
        input.setWidth(Math.max(1, panelRight - 4 - input.getX()));
        // Completion uses the same logical canvas as the scaled editor, without resizing the actual screen.
        ChatScreen canvas = new ChatScreen("") {
            @Override public GuiEventListener getFocused() { return MineAstrChatScreen.this.getFocused(); }
        };
        canvas.width = logicalWidth;
        canvas.height = logicalHeight;
        var suggestions = new CommandSuggestions(minecraft, canvas, input, font, false, false, 1, 10, true, -805306368);
        ((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions(suggestions);
        suggestions.setAllowHiding(false);
        suggestions.updateCommandInfo();
    }
    public double inputScale() { return inputScale; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        minecraft.gui.getChat().render(graphics, minecraft.gui.getGuiTicks(), mouseX, mouseY, true);
        // The editable row shares its width, icon, ID spacing, opacity and zoom with displayed chat.
        graphics.pose().pushPose();
        try {
            graphics.pose().scale((float) inputScale, (float) inputScale, 1);
            graphics.fill(0, input.getY() - 4, panelRight, input.getY() + 10,
                    (int) (255 * minecraft.options.textBackgroundOpacity().get()) << 24);
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(4, input.getY(), 0);
                graphics.drawString(font, MineAstrClientThemes.animate(senderHeader.getVisualOrderText(), MineAstrChatEasing.now()), 0, 0, 0xFFFFFFFF);
                MineAstrChatIcons.render(graphics, "minecraft", -1, 1);
            } finally { graphics.pose().popPose(); }
            input.render(graphics, (int) Math.floor(mouseX / inputScale), (int) Math.floor(mouseY / inputScale), partialTick);
        } finally { graphics.pose().popPose(); }
        for (var renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
        var suggestions = ((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 200);
            graphics.pose().scale((float) inputScale, (float) inputScale, 1);
            suggestions.render(graphics, (int) Math.floor(mouseX / inputScale), (int) Math.floor(mouseY / inputScale));
        } finally { graphics.pose().popPose(); }
        var tag = minecraft.gui.getChat().getMessageTagAt(mouseX, mouseY);
        if (tag != null && tag.text() != null) graphics.renderTooltip(font, font.split(tag.text(), 210), mouseX, mouseY);
        else {
            var style = minecraft.gui.getChat().getClickedComponentStyleAt(mouseX, mouseY);
            if (style != null && style.getHoverEvent() != null) graphics.renderComponentHoverEffect(font, style, mouseX, mouseY);
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions().mouseScrolled(vertical)) return true;
        double lineHeight = 9 * (minecraft.options.chatLineSpacing().get() + 1);
        chat().mineastr$scrollPixels(Math.clamp(vertical, -8, 8) * lineHeight * (hasShiftDown() ? .5 : 2.5));
        return true;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (chat().mineastr$chatClick(x, y, button)) return true;
        if (((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions().mouseClicked(x / inputScale, y / inputScale, button)) return true;
        if (y >= (input.getY() - 4) * inputScale && y < (input.getY() + 10) * inputScale
                && x >= 0 && x < panelRight * inputScale) {
            // Prefix clicks focus the editor but never become part of the outgoing message.
            input.mouseClicked(Math.max(input.getX(), x / inputScale), y / inputScale, button);
            setFocused(input);
            if (button == 0) setDragging(true);
            return true;
        }
        if (button == 0) {
            var component = minecraft.gui.getChat();
            if (component.handleChatQueueClicked(x, y)) return true;
            var style = component.getClickedComponentStyleAt(x, y);
            if (style != null && handleComponentClicked(style)) {
                ((MineAstrChatScreenAccessor) (Object) this).mineastr$initial(input.getValue());
                return true;
            }
        }
        for (var child : children()) {
            if (child != input && child.mouseClicked(x, y, button)) {
                setFocused(child);
                if (button == 0) setDragging(true);
                return true;
            }
        }
        return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (chat().mineastr$chatDrag(y, button)) return true;
        double scale = getFocused() == input ? inputScale : 1;
        return super.mouseDragged(x / scale, y / scale, button, dx / scale, dy / scale);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (chat().mineastr$chatRelease(button)) return true;
        double scale = getFocused() == input ? inputScale : 1;
        return super.mouseReleased(x / scale, y / scale, button);
    }
    @Override public void removed() { chat().mineastr$chatRelease(0); super.removed(); }
}
