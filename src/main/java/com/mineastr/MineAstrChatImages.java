package com.mineastr;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Bounded, asynchronous thumbnail decoding. Only texture upload/rendering uses the client thread. */
public final class MineAstrChatImages {
    private static final int MAX_IMAGES = 128;
    private static final Map<String, Entry> IMAGES = new LinkedHashMap<>(MAX_IMAGES, .75F, true);
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "MineAstr-ChatThumbnails");
                thread.setDaemon(true);
                return thread;
            });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private MineAstrChatImages() {}

    public static void reserve(String id, String name) {
        if (IMAGES.containsKey(id)) return;
        IMAGES.put(id, new Entry(name));
        while (IMAGES.size() > MAX_IMAGES) {
            var iterator = IMAGES.entrySet().iterator();
            Entry oldest = iterator.next().getValue();
            iterator.remove();
            release(oldest);
        }
    }

    static boolean contains(String id) { return IMAGES.containsKey(id); }

    static void receive(String id, byte[] bytes, String url, Runnable cacheWrite) {
        Entry entry = IMAGES.get(id);
        if (entry == null || entry.loading) return;
        entry.loading = true;
        long generation = GENERATION.get();
        try {
            WORKER.execute(() -> {
                try {
                    byte[] data = bytes;
                    if (data.length == 0) {
                        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
                        var future = HTTP.sendAsync(request, ignored -> new LimitedBody());
                        var response = future.orTimeout(15, TimeUnit.SECONDS).join();
                        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                        data = response.body();
                    }
                    if (data.length > MineAstrPayloads.MAX_BOT_IMAGE_BYTES) throw new IOException("image byte limit");
                    BufferedImage image = decodeThumbnail(data);
                    cacheWrite.run();
                    Minecraft.getInstance().execute(() -> {
                        if (generation != GENERATION.get() || IMAGES.get(id) != entry) return;
                        NativeImage pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
                        try {
                            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                                int argb = image.getRGB(x, y);
                                pixels.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16));
                            }
                            entry.texture = ResourceLocation.fromNamespaceAndPath("mineastr", "chat/" + id);
                            Minecraft.getInstance().getTextureManager().register(entry.texture, new DynamicTexture(pixels));
                            entry.width = image.getWidth();
                            entry.height = image.getHeight();
                            MineAstr.LOGGER.debug("MineAstr 聊天缩略图就绪：name={} width={} height={}", entry.name, entry.width, entry.height);
                        } catch (RuntimeException exc) {
                            pixels.close();
                            if (entry.texture != null) Minecraft.getInstance().getTextureManager().release(entry.texture);
                            entry.texture = null;
                            entry.failed = true;
                            MineAstr.LOGGER.warn("MineAstr 聊天缩略图上传失败：{}", exc.getMessage());
                        }
                    });
                } catch (Exception exc) {
                    Minecraft.getInstance().execute(() -> {
                        if (generation != GENERATION.get() || IMAGES.get(id) != entry) return;
                        entry.failed = true;
                        MineAstr.LOGGER.warn("MineAstr 聊天缩略图加载失败：name={} reason={}", entry.name, exc.getClass().getSimpleName());
                    });
                }
            });
        } catch (RejectedExecutionException exc) {
            entry.failed = true;
            MineAstr.LOGGER.warn("MineAstr 聊天缩略图队列已满：name={}", entry.name);
        }
    }

    static BufferedImage decodeThumbnail(byte[] data) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("unsupported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 32_000_000L) throw new IOException("image pixel limit");
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / 512);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage source = reader.read(0, parameters);
                var size = MineAstrChatGeometry.fit(width, height, 192, 108);
                BufferedImage target = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = target.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, size.width(), size.height(), null);
                } finally { graphics.dispose(); }
                return target;
            } finally { reader.dispose(); }
        }
    }

    public static void renderRow(GuiGraphics graphics, MineAstrChatLayout.ImageRow row, int lineBottom,
            int lineHeight, float scale, float alpha) {
        Entry entry = IMAGES.get(row.id());
        int top = lineBottom - lineHeight - row.row() * lineHeight + 2;
        if (entry == null || entry.texture == null) {
            if (row.row() == 0) {
                var label = net.minecraft.network.chat.Component.translatable(entry != null && entry.failed
                        ? "message.mineastr.image_failed" : "message.mineastr.image_loading");
                graphics.drawString(Minecraft.getInstance().font,
                        Minecraft.getInstance().font.plainSubstrByWidth(label.getString(), row.width()), row.column(), top,
                        ((int) (alpha * 255) << 24) | 0xAAAAAA);
            }
            return;
        }
        var size = MineAstrChatGeometry.fit(entry.width, entry.height, row.width(), row.height());
        // Scissor uses screen GUI coordinates; the enclosing pose uses vanilla chat scale/indent.
        graphics.enableScissor((int) Math.floor((row.column() + 4) * scale),
                (int) Math.floor((lineBottom - lineHeight) * scale),
                (int) Math.ceil((row.column() + size.width() + 4) * scale),
                (int) Math.ceil(lineBottom * scale));
        try {
            graphics.setColor(1, 1, 1, alpha);
            graphics.blit(entry.texture, row.column(), top, size.width(), size.height(),
                    0.0F, 0.0F, entry.width, entry.height, entry.width, entry.height);
        } finally {
            graphics.setColor(1, 1, 1, 1);
            graphics.disableScissor();
        }
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        WORKER.getQueue().clear();
        IMAGES.values().forEach(MineAstrChatImages::release);
        IMAGES.clear();
    }

    private static void release(Entry entry) {
        if (entry.texture != null) Minecraft.getInstance().getTextureManager().release(entry.texture);
    }

    private static final class Entry {
        final String name;
        boolean loading, failed;
        int width, height;
        ResourceLocation texture;
        Entry(String name) { this.name = name; }
    }

    /** Never buffer an unbounded HTTP body; cancel the subscription on size/timeout. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        LimitedBody() {
            result.orTimeout(15, TimeUnit.SECONDS).whenComplete((value, error) -> {
                if (error != null && subscription != null) subscription.cancel();
            });
        }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (result.isDone()) value.cancel(); else value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MineAstrPayloads.MAX_BOT_IMAGE_BYTES - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("image byte limit"));
                    return;
                }
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
