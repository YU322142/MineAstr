package com.mineastr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Four ranges per large GIF, eight extra range connections globally, verified before assembly. */
final class MineAstrRangeDownload {
    private static final int FIRST_BYTES = 262144;
    private static final Semaphore CONNECTIONS = new Semaphore(8, true);
    private static final Semaphore PREVIEWS = new Semaphore(2, true);
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    static String previewUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!uri.getPath().matches("/mineastr/media/[a-f0-9]{64}\\.source")
                    || uri.getRawQuery() == null || !uri.getRawQuery().contains("signature=")) return null;
            return url + "&preview=1";
        } catch (RuntimeException error) { return null; }
    }

    static byte[] loadPreview(HttpClient client, String url,
            BiFunction<Integer, Consumer<byte[]>, HttpResponse.BodySubscriber<byte[]>> bodies) throws Exception {
        String preview = previewUrl(url);
        if (preview == null) return new byte[0];
        var response = request(client, preview, "", "", MineAstrPayloads.MAX_BOT_IMAGE_BYTES, data -> {}, bodies);
        if (response.statusCode() == 204) return new byte[0];
        if (response.statusCode() != 200) throw new IOException("preview HTTP " + response.statusCode());
        return response.body();
    }

    static byte[] loadProgressive(HttpClient client, String url, Consumer<byte[]> preview,
            BiFunction<Integer, Consumer<byte[]>, HttpResponse.BodySubscriber<byte[]>> bodies) throws Exception {
        if (previewUrl(url) == null) return load(client, url, preview, bodies);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var first = executor.submit(() -> {
            PREVIEWS.acquire();
            try {
                byte[] data = loadPreview(client, url, bodies);
                if (data.length > 0) preview.accept(data);
                return data;
            } finally { PREVIEWS.release(); }
        });
        try {
            return load(client, url, preview, bodies);
        } catch (InterruptedException error) { throw error; }
        catch (Exception error) {
            // The animation may fail early while an independently useful first frame is still loading.
            try { first.get(35, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception ignored) { /* Preserve the original animation failure. */ }
            throw error;
        } finally {
            if (!first.isDone()) first.cancel(true);
            executor.shutdownNow();
        }
    }

    static byte[] load(HttpClient client, String url, Consumer<byte[]> preview,
            BiFunction<Integer, Consumer<byte[]>, HttpResponse.BodySubscriber<byte[]>> bodies) throws Exception {
        var first = request(client, url, "bytes=0-" + (FIRST_BYTES - 1), "", 0, preview, bodies);
        if (first.statusCode() == 200) return first.body(); // Origin ignored Range; streaming preview still works.
        if (first.statusCode() != 206) return full(client, url, preview, bodies);
        var match = CONTENT_RANGE.matcher(first.headers().firstValue("Content-Range").orElse(""));
        if (!match.matches()) return full(client, url, preview, bodies);
        int start = Integer.parseInt(match.group(1)), end = Integer.parseInt(match.group(2));
        long declared = Long.parseLong(match.group(3));
        if (start != 0 || end + 1 != first.body().length || declared < first.body().length
                || declared > MineAstrChatImages.downloadLimit(first.body())) throw new IOException("invalid image range length");
        int total = (int) declared;
        if (total == first.body().length) return first.body();
        String validator = first.headers().firstValue("ETag").filter(value -> !value.startsWith("W/")).orElseGet(
                () -> first.headers().firstValue("Last-Modified").orElse(""));
        // Only GIFs benefit from extra connections; stable validators prevent mixed asset revisions.
        if (!MineAstrGif.matches(first.body()) || validator.isBlank()) return full(client, url, preview, bodies);
        String downloadUrl = first.uri().toString();
        byte[] assembled = new byte[total];
        System.arraycopy(first.body(), 0, assembled, 0, first.body().length);
        var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MineAstr-GifRange-", 0).factory());
        var pending = new ArrayList<Future<byte[]>>();
        var offsets = new ArrayList<Integer>();
        boolean previewSent = MineAstrGifPreview.firstFrame(first.body(), first.body().length) != null;
        try {
            int size = Math.max(1, (total - first.body().length + 3) / 4);
            for (int offset = first.body().length; offset < total; offset += size) {
                int from = offset, to = Math.min(total - 1, from + size - 1);
                offsets.add(from);
                pending.add(executor.submit(() -> {
                    CONNECTIONS.acquire();
                    try {
                        var part = request(client, downloadUrl, "bytes=" + from + "-" + to, validator, to-from+1, data -> {}, bodies);
                        String expected = "bytes " + from + "-" + to + "/" + total;
                        if ((!validator.equals(part.headers().firstValue("ETag").orElse(""))
                                && !validator.equals(part.headers().firstValue("Last-Modified").orElse("")))
                                || part.statusCode() != 206 || !expected.equals(part.headers().firstValue("Content-Range").orElse(""))
                                || part.body().length != to-from+1) throw new IOException("GIF range response changed");
                        return part.body();
                    } finally { CONNECTIONS.release(); }
                }));
            }
            for (int index = 0; index < pending.size(); index++) {
                byte[] part = pending.get(index).get(20, TimeUnit.SECONDS);
                int offset = offsets.get(index);
                System.arraycopy(part, 0, assembled, offset, part.length);
                if (!previewSent) {
                    byte[] still = MineAstrGifPreview.firstFrame(assembled, offset + part.length);
                    if (still != null) { previewSent = true; preview.accept(still); }
                }
            }
            return assembled;
        } catch (InterruptedException error) { throw error; }
        catch (Exception error) {
            for (var task : pending) task.cancel(true);
            return full(client, url, preview, bodies);
        } finally {
            for (var task : pending) if (!task.isDone()) task.cancel(true);
            executor.shutdownNow();
        }
    }

    private static byte[] full(HttpClient client, String url, Consumer<byte[]> preview,
            BiFunction<Integer, Consumer<byte[]>, HttpResponse.BodySubscriber<byte[]>> bodies) throws Exception {
        var response = request(client, url, "", "", 0, preview, bodies);
        if (response.statusCode() != 200) throw new IOException("image HTTP " + response.statusCode());
        return response.body();
    }

    private static HttpResponse<byte[]> request(HttpClient client, String url, String range, String validator,
            int limit, Consumer<byte[]> preview,
            BiFunction<Integer, Consumer<byte[]>, HttpResponse.BodySubscriber<byte[]>> bodies) throws Exception {
        int seconds = previewUrl(url) == null ? 15 : (url.contains("preview=1") ? 35 : 120);
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds))
                .header("Accept-Encoding", "identity").GET();
        if (!range.isBlank()) builder.header("Range", range);
        if (!validator.isBlank()) builder.header("If-Range", validator);
        var future = client.sendAsync(builder.build(), response -> bodies.apply(limit,
                response.statusCode() == 200 || response.statusCode() == 206 ? preview : data -> {}));
        try { return future.get(seconds, TimeUnit.SECONDS); }
        finally { if (!future.isDone()) future.cancel(true); }
    }
}
