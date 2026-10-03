package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.List;
import org.junit.jupiter.api.Test;

class MineAstrChatImagesTest {
    @Test void thumbnailsKeepAspectRatioAndNeverUpscale() {
        assertEquals(new MineAstrChatGeometry.Size(96, 48), MineAstrChatGeometry.fit(800, 400, 96, 54));
        assertEquals(new MineAstrChatGeometry.Size(27, 54), MineAstrChatGeometry.fit(400, 800, 96, 54));
        assertEquals(new MineAstrChatGeometry.Size(8, 4), MineAstrChatGeometry.fit(8, 4, 96, 54));
    }
    @Test void narrowChatsAndSmallViewportsBoundTheImageBox() {
        assertEquals(96, MineAstrChatGeometry.senderColumn(320));
        assertEquals(32, MineAstrChatGeometry.senderColumn(96));
        assertEquals(38, MineAstrChatGeometry.imageWidth(64));
        assertEquals(9, MineAstrChatGeometry.imageHeight(3, 9));
        assertEquals(54, MineAstrChatGeometry.imageHeight(100, 9));
    }
    @Test void realPngDecodeProducesBoundedTransparentThumbnail() throws IOException {
        BufferedImage original = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        original.setRGB(0, 0, 0x80112233);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        BufferedImage decoded = MineAstrChatImages.decodeThumbnail(bytes.toByteArray());
        assertEquals(162, decoded.getWidth());
        assertEquals(108, decoded.getHeight());
        assertTrue(decoded.getColorModel().hasAlpha());
    }
    @Test void malformedImagesFailWithoutTextureAllocation() {
        assertThrows(IOException.class, () -> MineAstrChatImages.decodeThumbnail(new byte[] {1, 2, 3}));
    }
    @Test void platformAliasesCannotCreateRobotIcons() {
        assertEquals("qq", MineAstrBridge.normalizeChatPlatform("DEFAULT"));
        assertEquals("discord", MineAstrBridge.normalizeChatPlatform(" dc "));
        assertEquals("minecraft", MineAstrBridge.normalizeChatPlatform("AstrBot"));
    }
    @Test void optionalPresentationRoundTripsTranslatedMultilineTextAndImageReferences() {
        var value = new MineAstrPayloads.ChatPresentation("discord", "AstrBot", "你好\n[Original] Hello",
                List.of(new MineAstrPayloads.ImageRef("image-1", "图片.png")));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MineAstrPayloads.ChatPresentation.CODEC.encode(buffer, value);
            assertEquals(value, MineAstrPayloads.ChatPresentation.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void oversizedImageReferenceListFailsBeforeSending() {
        var images = java.util.Collections.nCopies(9, new MineAstrPayloads.ImageRef("id", "image"));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            assertThrows(IllegalArgumentException.class, () -> MineAstrPayloads.ChatPresentation.CODEC.encode(buffer,
                    new MineAstrPayloads.ChatPresentation("qq", "Alice", "text", images)));
        } finally { buffer.release(); }
    }
}
