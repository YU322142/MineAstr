package com.mineastr;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Generate mipmaps once on upload/reload, avoiding aliasing of high-resolution art. */
public final class MineAstrChatTextures {
    private static BlendScope activeBlend;
    private MineAstrChatTextures() {}

    /** Capture GL state once for all chat rows, avoiding per-row driver queries. */
    public static BlendScope blend(GuiGraphics graphics) {
        return new BlendScope(graphics);
    }

    public static final class BlendScope implements AutoCloseable {
        private final GuiGraphics graphics;
        private final BlendScope previous;
        private final boolean blended;
        private final int srcRgb, dstRgb, srcAlpha, dstAlpha;
        private final float[] color;
        private BlendScope(GuiGraphics graphics) {
            this.graphics = graphics;
            previous = activeBlend;
            graphics.flush();
            blended = GL11.glIsEnabled(GL11.GL_BLEND);
            srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            color = RenderSystem.getShaderColor().clone();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            activeBlend = this;
        }
        @Override public void close() {
            graphics.setColor(color[0], color[1], color[2], color[3]);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (!blended) RenderSystem.disableBlend();
            activeBlend = previous;
        }
    }

    /** Apply chat alpha to opaque and transparent textures alike. */
    public static void draw(GuiGraphics graphics, ResourceLocation texture, int x, int y,
            int width, int height, int sourceWidth, int sourceHeight, float alpha) {
        if (activeBlend == null) {
            try (var scope = blend(graphics)) {
                draw(graphics, texture, x, y, width, height, sourceWidth, sourceHeight, alpha);
            }
            return;
        }
        float[] color = activeBlend.color;
        graphics.setColor(1, 1, 1, Math.clamp(alpha, 0F, 1F));
        // Flushing font/render-type buffers can clear blend state inside a batch.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            graphics.blit(texture, x, y, width, height, 0F, 0F,
                    sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        } finally {
            graphics.setColor(color[0], color[1], color[2], color[3]);
        }
    }

    public static void smooth(AbstractTexture texture, int width, int height) {
        RenderSystem.assertOnRenderThread();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            texture.bind();
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            texture.setFilter(true, true);
        } finally { RenderSystem.bindTexture(previous); }
    }
}
