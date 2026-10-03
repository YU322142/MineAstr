package com.mineastr;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/** Generate mipmaps once on upload/reload, avoiding aliasing of high-resolution art. */
public final class MineAstrChatTextures {
    private MineAstrChatTextures() {}

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
