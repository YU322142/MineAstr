package com.mineastr;

import com.mineastr.mixin.MineAstrChatScreenAccessor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.GuiGraphics;

/** MineAstr input screen; native edit box, IME, history and command suggestions remain functional. */
public final class MineAstrChatScreen extends ChatScreen {
    public MineAstrChatScreen(String initial) { super(initial); }
    private MineAstrChatAccess chat() { return (MineAstrChatAccess) minecraft.gui.getChat(); }
    @Override protected void init() {
        super.init();
        // Keep text and its cursor clear of the input panel's left accent, including after resize.
        input.setX(12);
        input.setWidth(Math.max(1, width - 24));
        ((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions().updateCommandInfo();
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        minecraft.gui.getChat().render(graphics, minecraft.gui.getGuiTicks(), mouseX, mouseY, true);
        // The MineAstr screen owns the input panel; native controls retain IME and completion semantics.
        graphics.fill(2, height - 16, width - 2, height - 2, 0xDB101820);
        graphics.fill(2, height - 16, 3, height - 2, 0xFF72E6C1);
        input.render(graphics, mouseX, mouseY, partialTick);
        for (var renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
        var suggestions = ((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 200);
            suggestions.render(graphics, mouseX, mouseY);
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
        return chat().mineastr$chatClick(x, y, button) || super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return chat().mineastr$chatDrag(y, button) || super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        return chat().mineastr$chatRelease(button) || super.mouseReleased(x, y, button);
    }
    @Override public void removed() { chat().mineastr$chatRelease(0); super.removed(); }
}
