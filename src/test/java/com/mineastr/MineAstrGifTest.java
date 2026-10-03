package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

class MineAstrGifTest {
    @Test void interruptedDecodeStopsWithoutReturningFrames() throws IOException {
        byte[] bytes = gif("restoreToPrevious", 0);
        Thread.currentThread().interrupt();
        try { assertThrows(IOException.class, () -> MineAstrGif.decode(bytes)); }
        finally { Thread.interrupted(); }
    }
    private static final IndexColorModel PALETTE = new IndexColorModel(8, 4,
            new byte[]{0, (byte)255, 0, 0}, new byte[]{0, 0, (byte)255, 0},
            new byte[]{0, 0, 0, (byte)255}, new byte[]{0, (byte)255, (byte)255, (byte)255});

    private byte[] gif(String disposal, int repeats) throws IOException {
        return gif(disposal, repeats, 3);
    }

    private byte[] gif(String disposal, int repeats, int count) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output); writer.prepareWriteSequence(null);
            for (int index = 0; index < count; index++) {
                int width = index == 0 ? 4 : index == 1 ? 2 : 1;
                var image = new BufferedImage(width, width, BufferedImage.TYPE_BYTE_INDEXED, PALETTE);
                for (int y=0;y<width;y++) for(int x=0;x<width;x++) image.getRaster().setSample(x,y,0,index%3+1);
                var metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
                var tree = (IIOMetadataNode)metadata.getAsTree("javax_imageio_gif_image_1.0");
                var descriptor = (IIOMetadataNode)tree.getElementsByTagName("ImageDescriptor").item(0);
                descriptor.setAttribute("imageLeftPosition", index == 1 ? "1" : "0");
                descriptor.setAttribute("imageTopPosition", index == 1 ? "1" : "0");
                descriptor.setAttribute("imageWidth", Integer.toString(width));
                descriptor.setAttribute("imageHeight", Integer.toString(width));
                descriptor.setAttribute("interlaceFlag", "FALSE");
                var control = (IIOMetadataNode)tree.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", index == 1 ? disposal : "none");
                control.setAttribute("delayTime", index == 0 ? "3" : index == 1 ? "7" : "2");
                control.setAttribute("transparentColorFlag", "TRUE"); control.setAttribute("transparentColorIndex", "0");
                if (index == 0 && repeats >= 0) {
                    var extensions = new IIOMetadataNode("ApplicationExtensions");
                    var extension = new IIOMetadataNode("ApplicationExtension");
                    extension.setAttribute("applicationID", "NETSCAPE"); extension.setAttribute("authenticationCode", "2.0");
                    extension.setUserObject(new byte[]{1,(byte)repeats,(byte)(repeats>>>8)});
                    extensions.appendChild(extension); tree.appendChild(extensions);
                }
                metadata.setFromTree("javax_imageio_gif_image_1.0",tree);
                writer.writeToSequence(new IIOImage(image,null,metadata),null);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    @Test void partialFramesCompositeAndRestorePrevious() throws IOException {
        var animation=MineAstrGif.decode(gif("restoreToPrevious",0));
        assertEquals(3,animation.frames().size());
        assertEquals(0xFF00FF00,animation.frames().get(1).getRGB(1,1));
        assertEquals(0xFFFF0000,animation.frames().get(1).getRGB(0,0));
        assertEquals(0xFFFF0000,animation.frames().get(2).getRGB(1,1));
        assertEquals(0xFF0000FF,animation.frames().get(2).getRGB(0,0));
    }

    @Test void backgroundDisposalClearsTransparentPatches() throws IOException {
        var animation=MineAstrGif.decode(gif("restoreToBackgroundColor",0));
        assertEquals(0,animation.frames().get(2).getRGB(1,1));
        assertEquals(0xFFFF0000,animation.frames().get(2).getRGB(3,3));
    }

    @Test void variableDelaysAndInfiniteLoopsFollowMonotonicTime() throws IOException {
        var animation=MineAstrGif.decode(gif("none",0));
        assertArrayEquals(new int[]{30,70,20},animation.delays());
        assertEquals(0,animation.frameAt(-1)); assertEquals(0,animation.frameAt(29));
        assertEquals(1,animation.frameAt(30)); assertEquals(2,animation.frameAt(100));
        assertEquals(0,animation.frameAt(120)); assertEquals(1,animation.frameAt(150));
        assertEquals(0,animation.plays());
    }

    @Test void finiteAndUnspecifiedLoopsStopOnTheLastFrame() throws IOException {
        var once=MineAstrGif.decode(gif("none",-1));
        assertEquals(1,once.plays()); assertEquals(2,once.frameAt(120));
        var twice=MineAstrGif.decode(gif("none",1));
        assertEquals(2,twice.plays()); assertEquals(0,twice.frameAt(120)); assertEquals(2,twice.frameAt(240));
    }

    @Test void corruptGifFailsBeforeNativeTextureAllocation() {
        assertFalse(MineAstrGif.matches(new byte[]{1,2,3}));
        assertThrows(IOException.class,()->MineAstrGif.decode("GIF89a".getBytes()));
    }

    @Test void excessiveFrameCountFailsBeforeRetainingDecodedFrames() throws IOException {
        byte[] data = gif("none",0,MineAstrGif.MAX_FRAMES+1);
        IOException failure=assertThrows(IOException.class,()->MineAstrGif.decode(data));
        assertEquals("GIF frame limit",failure.getMessage());
    }
}
