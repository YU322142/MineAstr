package com.mineastr;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reuses the chat texture; zoom and drag never decode, download or allocate another texture. */
public final class MineAstrImageScreen extends Screen {
    private final Screen parent;
    private final String imageId;
    private final MineAstrImageViewport viewport = new MineAstrImageViewport();
    private long lastClick;
    public MineAstrImageScreen(Screen parent, String imageId) {
        super(Component.translatable("screen.mineastr.image.title"));
        this.parent = parent; this.imageId = imageId;
        if (parent instanceof com.mineastr.mixin.MineAstrChatScreenAccessor accessor && accessor.mineastr$input() != null)
            accessor.mineastr$initial(accessor.mineastr$input().getValue());
    }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.image.reset"), b -> viewport.reset()).bounds(width / 2 - 104, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 + 4, height - 26, 100, 20).build());
    }
    private MineAstrChatGeometry.Size fitted(MineAstrChatImages.ImageView image) {
        return MineAstrChatGeometry.fit(image.width(), image.height(), Math.max(1, width - 24), Math.max(1, height - 84));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render would blur the already drawn image. Draw the background once, first.
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xF2101820);
        var image = MineAstrChatImages.view(imageId);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFF3F7FF);
        if (image == null) graphics.drawCenteredString(font, Component.translatable("message.mineastr.image_unavailable"), width / 2, height / 2, 0xFFF3F7FF);
        else {
            var fit = fitted(image);
            viewport.constrain(fit.width(), fit.height(), width - 16, height - 68);
            int w = Math.max(1, (int)Math.round(fit.width() * viewport.zoom()));
            int h = Math.max(1, (int)Math.round(fit.height() * viewport.zoom()));
            int x = (int)Math.round(width / 2.0 + viewport.panX() - w / 2.0);
            int y = (int)Math.round((height - 24) / 2.0 + viewport.panY() - h / 2.0);
            graphics.enableScissor(8, 30, width - 8, height - 38);
            try { MineAstrChatImages.draw(graphics, image, x, y, w, h, 1); }
            finally { graphics.disableScissor(); }
            graphics.drawCenteredString(font, Component.translatable("screen.mineastr.image.help", Math.round(viewport.zoom() * 100)), width / 2, height - 37, 0xFFA8B4C8);
        }
        for (var renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        var image = MineAstrChatImages.view(imageId);
        if (image == null) return false;
        var fit = fitted(image);
        viewport.scroll(vertical, x - width / 2.0, y - (height - 24) / 2.0, fit.width(), fit.height(), width - 16, height - 68);
        return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        var image = MineAstrChatImages.view(imageId);
        if (button != 0 || image == null) return super.mouseDragged(x,y,button,dx,dy);
        var fit = fitted(image);
        viewport.drag(dx,dy,fit.width(),fit.height(),width-16,height-68);
        return true;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x,y,button)) return true;
        if (button == 0) {
            long now = System.nanoTime();
            if (now - lastClick < 300_000_000L) viewport.reset();
            lastClick = now;
            return true;
        }
        return false;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
