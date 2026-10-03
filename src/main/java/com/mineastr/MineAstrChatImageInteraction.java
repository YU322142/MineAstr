package com.mineastr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

public final class MineAstrChatImageInteraction {
    private MineAstrChatImageInteraction() {}

    @SubscribeEvent public static void hover(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen)) return;
        String id = MineAstrChatImages.imageAt(event.getMouseX(), event.getMouseY());
        if (id == null) return;
        var image = MineAstrChatImages.view(id);
        if (image == null) return;
        var graphics = event.getGuiGraphics();
        int limitWidth = Math.max(1, Math.min(320, graphics.guiWidth() / 2 - 12));
        int limitHeight = Math.max(1, Math.min(240, graphics.guiHeight() / 2 - 24));
        var size = MineAstrChatGeometry.fit(image.width(), image.height(), limitWidth, limitHeight);
        var hint = Component.translatable("screen.mineastr.image.click");
        int panelWidth = Math.min(graphics.guiWidth() - 8, Math.max(size.width(), Minecraft.getInstance().font.width(hint)));
        int x = event.getMouseX() + 14, y = event.getMouseY() + 14;
        if (x + panelWidth + 8 > graphics.guiWidth()) x = event.getMouseX() - panelWidth - 14;
        x = Math.clamp(x, 4, Math.max(4, graphics.guiWidth() - panelWidth - 4));
        y = Math.clamp(y, 4, Math.max(4, graphics.guiHeight() - size.height() - 20));
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 800);
            graphics.fill(x - 3, y - 3, x + panelWidth + 3, y + size.height() + 17, 0xED101820);
            MineAstrChatImages.draw(graphics, image, x, y, size.width(), size.height(), 1);
            graphics.drawString(Minecraft.getInstance().font, Minecraft.getInstance().font.plainSubstrByWidth(hint.getString(), panelWidth), x, y + size.height() + 4, 0xFFF3F7FF);
        } finally { graphics.pose().popPose(); }
    }

    @SubscribeEvent public static void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen) || event.getButton() != 0) return;
        String id = MineAstrChatImages.imageAt(event.getMouseX(), event.getMouseY());
        if (id == null || MineAstrChatImages.view(id) == null) return;
        event.setCanceled(true);
        Minecraft.getInstance().setScreen(new MineAstrImageScreen(event.getScreen(), id));
    }
}
