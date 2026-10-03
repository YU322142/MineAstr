package com.mineastr;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Vector-authored 256px platform marks, drawn independently of the glyph atlas. */
public final class MineAstrChatIcons {
    private static final Set<ResourceLocation> FILTERED = new HashSet<>();
    private MineAstrChatIcons() {}

    public static void render(GuiGraphics graphics, String platform, int y, float alpha) {
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("mineastr", "textures/gui/platform/" + platform + ".png");
        if (FILTERED.add(texture)) MineAstrChatTextures.smooth(Minecraft.getInstance().getTextureManager().getTexture(texture), 256, 256);
        graphics.setColor(1, 1, 1, alpha);
        try { graphics.blit(texture, 0, y, 10, 10, 0, 0, 256, 256, 256, 256); }
        finally { graphics.setColor(1, 1, 1, 1); }
    }

    public static void reload() { FILTERED.clear(); }
}
