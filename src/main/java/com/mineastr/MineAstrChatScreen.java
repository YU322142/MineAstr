package com.mineastr;

import com.mineastr.mixin.MineAstrChatScreenAccessor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;

/** Custom chat viewport and panel; native editor, command completion and usage hints remain intact. */
public final class MineAstrChatScreen extends ChatScreen {
    private MineAstrChatGeometry.ChatPanel panel;
    public MineAstrChatScreen(String initial) { super(initial); }
    private MineAstrChatAccess chat() { return (MineAstrChatAccess) minecraft.gui.getChat(); }
    @Override protected void init() {
        super.init();
        var component = minecraft.gui.getChat();
        panel = MineAstrChatGeometry.chatPanel(component.getWidth(), width, component.getScale());
        input.setX(panel.textLeft());
        input.setWidth(Math.max(1, panel.contentRight() - panel.textLeft()));
        // Use the original instance created by ChatScreen, with the actual screen and unscaled editor.
        ((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions().updateCommandInfo();
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        minecraft.gui.getChat().render(graphics, minecraft.gui.getGuiTicks(), mouseX, mouseY, true);
        graphics.fill(panel.left(), input.getY() - 4, panel.right(), input.getY() + 10,
                (int) (255 * minecraft.options.textBackgroundOpacity().get()) << 24);
        // A fixed panel accent, separate from the native moving/blinking text caret.
        graphics.fill(panel.left(), input.getY() - 4, panel.left() + MineAstrChatGeometry.RAIL_WIDTH, input.getY() + 10, 0xFF72E6C1);
        input.render(graphics, mouseX, mouseY, partialTick);
        for (var renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 200);
            ((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions().render(graphics, mouseX, mouseY);
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
        // The original popup gets priority even when it overlaps the internal chat scrollbar.
        if (((MineAstrChatScreenAccessor) (Object) this).mineastr$suggestions().mouseClicked(x, y, button)) return true;
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
