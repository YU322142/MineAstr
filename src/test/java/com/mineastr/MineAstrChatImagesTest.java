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
        assertEquals(600, decoded.getWidth());
        assertEquals(400, decoded.getHeight());
        assertTrue(decoded.getColorModel().hasAlpha());
        assertEquals(0x80112233, decoded.getRGB(0, 0));
    }
    @Test void highResolutionDecodeRetainsDetailWithinTheTextureBudget() throws IOException {
        BufferedImage original = new BufferedImage(1800, 900, BufferedImage.TYPE_INT_ARGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        BufferedImage decoded = MineAstrChatImages.decodeThumbnail(bytes.toByteArray());
        assertEquals(1024, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }
    @Test void configurableSizeGrowsButNeverEscapesTheBodyOrViewport() {
        assertTrue(MineAstrChatGeometry.imageWidth(250, 200) > MineAstrChatGeometry.imageWidth(250, 100));
        assertEquals(60, MineAstrChatGeometry.imageWidth(64, 300));
        assertTrue(MineAstrChatGeometry.imageHeight(20, 9, 200) > MineAstrChatGeometry.imageHeight(20, 9, 100));
        assertEquals(18, MineAstrChatGeometry.imageHeight(3, 9, 300));
    }
    @Test void heightLimitUsesScreenSpaceAndPreservesMinecraftAutoSetting() {
        assertEquals(180, MineAstrChatGeometry.heightLimit(180, 350, 1, 0, 9));
        assertEquals(151, MineAstrChatGeometry.heightLimit(180, 350, 1, 50, 9));
        assertEquals(180, MineAstrChatGeometry.heightLimit(180, 350, .5, 50, 9));
        assertEquals(9, MineAstrChatGeometry.heightLimit(180, 60, 1, 1, 9));
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
