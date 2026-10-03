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
import java.util.concurrent.Semaphore;
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
    private static final long MAX_TEXTURE_PIXELS = 16_777_216L;
    private static long texturePixels;
    private static final java.util.ArrayList<ImageHit> HITS = new java.util.ArrayList<>();
    private static long frameTime;
    private static final Semaphore UPLOAD_SLOTS = new Semaphore(2);
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
                    if (data.length > downloadLimit(data)) throw new IOException("image byte limit");
                    MineAstrGif.Animation animation = MineAstrGif.matches(data) ? MineAstrGif.decode(data) : null;
                    BufferedImage image = animation == null ? decodeThumbnail(data) : animation.frames().getFirst();
                    cacheWrite.run();
                    if (!UPLOAD_SLOTS.tryAcquire(5, TimeUnit.SECONDS)) throw new IOException("upload queue saturated");
                    NativeImage pixels = null;
                    NativeImage[] frames = null;
                    try {
                        if (animation != null && animation.frames().size() > 1) {
                            frames = new NativeImage[animation.frames().size()];
                            for (int index = 0; index < frames.length; index++) frames[index] = nativePixels(animation.frames().get(index));
                        }
                        pixels = nativePixels(image);
                        NativeImage uploadPixels = pixels;
                        NativeImage[] uploadFrames = frames;
                        Minecraft.getInstance().execute(() -> upload(id, entry, generation, uploadPixels,
                                image.getWidth(), image.getHeight(), animation, uploadFrames));
                    } catch (RuntimeException error) {
                        if (pixels != null) pixels.close();
                        closeFrames(frames);
                        UPLOAD_SLOTS.release();
                        throw error;
                    }
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

    private static void upload(String id, Entry entry, long generation, NativeImage pixels, int width, int height,
            MineAstrGif.Animation animation, NativeImage[] frames) {
        DynamicTexture texture = null;
        boolean registered = false;
        try {
            if (generation != GENERATION.get() || IMAGES.get(id) != entry) { pixels.close(); closeFrames(frames); return; }
            texture = new DynamicTexture(pixels);
            MineAstrChatTextures.smooth(texture, width, height);
            entry.texture = ResourceLocation.fromNamespaceAndPath("mineastr", "chat/" + id);
            Minecraft.getInstance().getTextureManager().register(entry.texture, texture);
            entry.width = width; entry.height = height;
            entry.readyAt = MineAstrChatEasing.now();
            entry.dynamicTexture = texture;
            entry.animation = frames == null ? null : animation;
            entry.frames = frames;
            // Account for retained GIF pixels, buffered frames and the active GPU/CPU texture.
            entry.pixelCost = (long) width * height * (frames == null ? 1 : 2L * frames.length + 1);
            texturePixels += entry.pixelCost;
            registered = true;
            var iterator = IMAGES.entrySet().iterator();
            while (texturePixels > MAX_TEXTURE_PIXELS && iterator.hasNext()) {
                Entry oldest = iterator.next().getValue();
                iterator.remove(); release(oldest);
            }
            MineAstr.LOGGER.debug("MineAstr 聊天缩略图就绪：name={} width={} height={}", entry.name, width, height);
        } catch (RuntimeException error) {
            if (registered) release(entry);
            else {
                if (texture != null) texture.close(); else pixels.close();
                closeFrames(frames);
            }
            entry.texture = null; entry.failed = true;
            MineAstr.LOGGER.warn("MineAstr 聊天缩略图上传失败：{}", error.getMessage());
        } finally { UPLOAD_SLOTS.release(); }
    }

    private static NativeImage nativePixels(BufferedImage image) {
        NativeImage pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
        try {
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                pixels.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16));
            }
            return pixels;
        } catch (RuntimeException error) { pixels.close(); throw error; }
    }

    private static void closeFrames(NativeImage[] frames) {
        if (frames != null) for (NativeImage frame : frames) if (frame != null) frame.close();
    }

    private static void animate(Entry entry) {
        if (entry.animation == null || entry.frames == null || entry.dynamicTexture == null) return;
        int index = entry.animation.frameAt((long) (MineAstrChatEasing.now() - entry.readyAt));
        if (index == entry.frameIndex) return;
        entry.dynamicTexture.getPixels().copyFrom(entry.frames[index]);
        entry.dynamicTexture.upload();
        MineAstrChatTextures.smooth(entry.dynamicTexture, entry.width, entry.height);
        entry.frameIndex = index;
    }

    static int downloadLimit(byte[] prefix) {
        return MineAstrGif.matches(prefix) ? MineAstrGif.MAX_DOWNLOAD_BYTES : MineAstrPayloads.MAX_BOT_IMAGE_BYTES;
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
                int sample = Math.max(1, Math.max(width, height) / 1024);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage source = reader.read(0, parameters);
                var size = MineAstrChatGeometry.fit(width, height, 1024, 1024);
                if (source.getWidth() == size.width() && source.getHeight() == size.height()) return source;
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
        renderRow(graphics, row, lineBottom, lineHeight, scale, alpha, 0);
    }

    public static void renderRow(GuiGraphics graphics, MineAstrChatLayout.ImageRow row, int lineBottom,
            int lineHeight, float scale, float alpha, float offsetY) {
        renderRow(graphics, row, lineBottom, lineHeight, scale, alpha, offsetY, 0, graphics.guiHeight() / scale);
    }

    public static void renderRow(GuiGraphics graphics, MineAstrChatLayout.ImageRow row, int lineBottom,
            int lineHeight, float scale, float alpha, float offsetY, float viewportTop, float viewportBottom) {
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
        animate(entry);
        if(MineAstrClientConfig.chatAnimationsEnabled()) {
            int duration=MineAstrClientConfig.isLoaded()?MineAstrClientConfig.CHAT_ARRIVAL_DURATION.getAsInt():200;
            alpha *= (float)MineAstrChatEasing.gentle((MineAstrChatEasing.now()-entry.readyAt)/duration);
        }
        var size = MineAstrChatGeometry.fit(entry.width, entry.height, row.width(), row.height());
        float visibleTop = Math.max(viewportTop, Math.max(top + offsetY, lineBottom - lineHeight + offsetY));
        float visibleBottom = Math.min(viewportBottom, Math.min(top + size.height() + offsetY, lineBottom + offsetY));
        if (visibleBottom > visibleTop && alpha > .05F) HITS.add(new ImageHit(row.id(),
                (row.column() + 4) * scale, visibleTop * scale,
                (row.column() + size.width() + 4) * scale, visibleBottom * scale));
        // Scissor uses screen GUI coordinates; the enclosing pose uses vanilla chat scale/indent.
        graphics.enableScissor((int) Math.floor((row.column() + 4) * scale),
                (int) Math.floor((lineBottom - lineHeight + offsetY) * scale),
                (int) Math.ceil((row.column() + size.width() + 4) * scale),
                (int) Math.ceil((lineBottom + offsetY) * scale));
        try {
            MineAstrChatTextures.draw(graphics, entry.texture, row.column(), top,
                    size.width(), size.height(), entry.width, entry.height, alpha);
        } finally { graphics.disableScissor(); }
    }

    public static void beginFrame() { HITS.clear(); frameTime = System.nanoTime(); }
    public static String imageAt(double x, double y) {
        if(System.nanoTime()-frameTime>250_000_000L)return null;
        for(int i=HITS.size()-1;i>=0;i--) {
            var hit=HITS.get(i);
            if(x>=hit.left && x<hit.right && y>=hit.top && y<hit.bottom)return hit.id;
        }
        return null;
    }
    public static ImageView view(String id) {
        Entry entry=IMAGES.get(id);
        if (entry != null && entry.texture != null) animate(entry);
        return entry==null || entry.texture==null ? null : new ImageView(entry.texture,entry.width,entry.height,entry.name);
    }
    public static void draw(GuiGraphics graphics, ImageView image, int x, int y, int width, int height, float alpha) {
        MineAstrChatTextures.draw(graphics, image.texture, x, y, width, height, image.width, image.height, alpha);
    }
    public record ImageView(ResourceLocation texture,int width,int height,String name) {}
    private record ImageHit(String id,float left,float top,float right,float bottom) {}

    public static void clear() {
        HITS.clear();
        GENERATION.incrementAndGet();
        WORKER.getQueue().clear();
        IMAGES.values().forEach(MineAstrChatImages::release);
        IMAGES.clear();
    }

    private static void release(Entry entry) {
        if (entry.texture != null) {
            Minecraft.getInstance().getTextureManager().release(entry.texture);
            texturePixels = Math.max(0, texturePixels - entry.pixelCost);
            entry.texture = null;
        }
        closeFrames(entry.frames);
        entry.frames = null; entry.animation = null; entry.dynamicTexture = null;
    }

    private static final class Entry {
        final String name;
        boolean loading, failed;
        int width, height;
        double readyAt;
        long pixelCost;
        int frameIndex;
        NativeImage[] frames;
        MineAstrGif.Animation animation;
        DynamicTexture dynamicTexture;
        ResourceLocation texture;
        Entry(String name) { this.name = name; }
    }

    /** Never buffer an unbounded HTTP body; cancel the subscription on size/timeout. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final byte[] prefix = new byte[6];
        private int prefixLength;
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
                if (prefixLength < prefix.length) {
                    ByteBuffer header = chunk.duplicate();
                    while (header.hasRemaining() && prefixLength < prefix.length) prefix[prefixLength++] = header.get();
                }
                if (chunk.remaining() > downloadLimit(prefix) - output.size()) {
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
