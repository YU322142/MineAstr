package com.mineastr;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.w3c.dom.Node;

/** Decodes bounded GIF timelines off the render thread, including disposal and loop metadata. */
final class MineAstrGif {
    static final int MAX_FRAMES = 256;
    static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    static final long MAX_FRAME_PIXELS = 4_194_304L;

    record Animation(List<BufferedImage> frames, int[] delays, int plays) {
        int frameAt(long elapsedMillis) {
            long duration = 0;
            for (int delay : delays) duration += delay;
            if (duration <= 0 || frames.size() == 1) return 0;
            long elapsed = Math.max(0, elapsedMillis);
            if (plays > 0 && elapsed / duration >= plays) return frames.size() - 1;
            long position = elapsed % duration;
            for (int index = 0; index < delays.length; index++) {
                if (position < delays[index]) return index;
                position -= delays[index];
            }
            return frames.size() - 1;
        }
    }

    static boolean matches(byte[] data) {
        return data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
    }

    static Animation decode(byte[] data) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("unsupported GIF");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int count = reader.getNumImages(true);
                if (count < 1 || count > MAX_FRAMES) throw new IOException("GIF frame limit");
                Node stream = tree(reader.getStreamMetadata(), "javax_imageio_gif_stream_1.0");
                Node screen = child(stream, "LogicalScreenDescriptor");
                int width = number(screen, "logicalScreenWidth", reader.getWidth(0));
                int height = number(screen, "logicalScreenHeight", reader.getHeight(0));
                if (width <= 0 || height <= 0 || (long) width * height > MAX_FRAME_PIXELS
                        || (long) width * height * count > 134_217_728L) throw new IOException("GIF pixel limit");
                int bound = Math.max(1, Math.min(512, (int) Math.sqrt(MAX_FRAME_PIXELS / count)));
                var size = MineAstrChatGeometry.fit(width, height, bound, bound);
                var canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                var frames = new ArrayList<BufferedImage>(count);
                var delays = new int[count];
                int plays = 1;
                for (int index = 0; index < count; index++) {
                    Node metadata = tree(reader.getImageMetadata(index), "javax_imageio_gif_image_1.0");
                    if (index == 0) plays = plays(metadata);
                    Node descriptor = child(metadata, "ImageDescriptor");
                    Node control = child(metadata, "GraphicControlExtension");
                    if (index == 0) {
                        Graphics2D background = canvas.createGraphics();
                        try {
                            background.setComposite(AlphaComposite.Src);
                            background.setColor(new java.awt.Color(background(stream, control), true));
                            background.fillRect(0, 0, width, height);
                        } finally { background.dispose(); }
                    }
                    int left = number(descriptor, "imageLeftPosition", 0);
                    int top = number(descriptor, "imageTopPosition", 0);
                    int frameWidth = reader.getWidth(index), frameHeight = reader.getHeight(index);
                    if (left < 0 || top < 0 || frameWidth <= 0 || frameHeight <= 0
                            || (long) left + frameWidth > width || (long) top + frameHeight > height)
                        throw new IOException("GIF frame bounds");
                    String disposal = attribute(control, "disposalMethod", "none");
                    BufferedImage previous = disposal.equals("restoreToPrevious") ? copy(canvas, width, height) : null;
                    Graphics2D graphics = canvas.createGraphics();
                    try { graphics.drawImage(reader.read(index), left, top, null); }
                    finally { graphics.dispose(); }
                    frames.add(copy(canvas, size.width(), size.height()));
                    int delay = number(control, "delayTime", 10);
                    // Browsers clamp absent/zero GIF delays; avoid a CPU/GPU busy loop.
                    delays[index] = delay <= 1 ? 100 : Math.min(655_350, delay * 10);
                    if (disposal.equals("restoreToBackgroundColor")) {
                        graphics = canvas.createGraphics();
                        try {
                            int background = background(stream, control);
                            graphics.setComposite(AlphaComposite.Src);
                            graphics.setColor(new java.awt.Color(background, true));
                            graphics.fillRect(left, top, frameWidth, frameHeight);
                        } finally { graphics.dispose(); }
                    } else if (previous != null) canvas = previous;
                }
                return new Animation(List.copyOf(frames), delays, plays);
            } finally { reader.dispose(); }
        }
    }

    private static BufferedImage copy(BufferedImage source, int width, int height) {
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return result;
    }

    private static int plays(Node metadata) {
        Node extensions = child(metadata, "ApplicationExtensions");
        if (extensions == null) return 1;
        for (Node node = extensions.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof IIOMetadataNode item && attribute(node, "applicationID", "").equals("NETSCAPE")
                    && item.getUserObject() instanceof byte[] bytes && bytes.length >= 3 && bytes[0] == 1) {
                int repeats = (bytes[1] & 255) | ((bytes[2] & 255) << 8);
                return repeats == 0 ? 0 : repeats + 1;
            }
        }
        return 1;
    }

    private static int background(Node stream, Node control) {
        Node table = child(stream, "GlobalColorTable");
        int index = number(table, "backgroundColorIndex", 0);
        if (attribute(control, "transparentColorFlag", "FALSE").equalsIgnoreCase("TRUE")
                && number(control, "transparentColorIndex", -1) == index) return 0;
        if (table != null) for (Node color = table.getFirstChild(); color != null; color = color.getNextSibling()) {
            if (number(color, "index", -1) == index) return 0xFF000000 | number(color, "red", 0) << 16
                    | number(color, "green", 0) << 8 | number(color, "blue", 0);
        }
        return 0;
    }

    private static Node tree(IIOMetadata metadata, String format) {
        return metadata == null ? null : metadata.getAsTree(format);
    }

    private static Node child(Node parent, String name) {
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node.getNodeName().equals(name)) return node;
        return null;
    }

    private static String attribute(Node node, String name, String fallback) {
        Node value = node == null || node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return value == null ? fallback : value.getNodeValue();
    }

    private static int number(Node node, String name, int fallback) {
        try { return Integer.parseInt(attribute(node, name, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
