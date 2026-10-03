package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MineAstrRangeDownloadTest {
    @Test void animationFailureStillAllowsIndependentPreviewToFinish() throws Exception {
        byte[] bytes = image();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        String path = "/mineastr/media/" + "b".repeat(64) + ".source";
        var previews = new AtomicInteger();
        server.createContext(path, exchange -> {
            if (exchange.getRequestURI().getQuery().contains("preview=1")) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                byte[] first = MineAstrGifPreview.firstFrame(bytes, bytes.length);
                exchange.sendResponseHeaders(200, first.length); exchange.getResponseBody().write(first);
            } else { exchange.sendResponseHeaders(502, 0); }
            exchange.close();
        }); server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + path + "?expires=2000000000&signature=test";
            assertThrows(Exception.class, () -> MineAstrRangeDownload.loadProgressive(HttpClient.newHttpClient(), url,
                    data -> previews.incrementAndGet(), MineAstrChatImages.LimitedBody::new));
            assertEquals(1, previews.get());
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    @Test void signedProxyPreviewLoadsWhileFullAnimationIsBlocked() throws Exception {
        byte[] bytes = image();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        var fullStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var previewReady = new CountDownLatch(1);
        String path = "/mineastr/media/" + "a".repeat(64) + ".source";
        server.createContext(path, exchange -> {
            boolean preview = exchange.getRequestURI().getQuery().contains("preview=1");
            if (!preview) {
                fullStarted.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            byte[] content = preview ? MineAstrGifPreview.firstFrame(bytes, bytes.length) : bytes;
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content); exchange.close();
        }); server.start();
        var jobs = Executors.newVirtualThreadPerTaskExecutor();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + path + "?expires=2000000000&signature=test";
            var full = jobs.submit(() -> MineAstrRangeDownload.loadProgressive(HttpClient.newHttpClient(), url, data -> previewReady.countDown(), MineAstrChatImages.LimitedBody::new));
            assertTrue(fullStarted.await(2, TimeUnit.SECONDS));
            assertTrue(previewReady.await(2, TimeUnit.SECONDS));
            byte[] preview = MineAstrRangeDownload.loadPreview(HttpClient.newHttpClient(), url, MineAstrChatImages.LimitedBody::new);
            assertArrayEquals(MineAstrGifPreview.firstFrame(bytes, bytes.length), preview);
            assertFalse(full.isDone());
            release.countDown();
            assertArrayEquals(bytes, full.get(3, TimeUnit.SECONDS));
            assertNull(MineAstrRangeDownload.previewUrl("https://example.com/original.gif?signature=x"));
        } finally { release.countDown(); jobs.shutdownNow(); server.stop(0); executor.shutdownNow(); }
    }
    private byte[] image() {
        byte[] first = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
        return Arrays.copyOf(first, 800_000);
    }
    @Test void rangePartsDownloadInParallelAndFirstFramePrecedesCompletion() throws Exception {
        byte[] bytes = image();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        var overlapping = new CountDownLatch(2);
        var firstFrames = new AtomicInteger();
        server.createContext("/image", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            String[] bounds = range.substring(6).split("-");
            int start = Integer.parseInt(bounds[0]), end = Math.min(bytes.length-1, Integer.parseInt(bounds[1]));
            if (start > 0) {
                assertTrue(firstFrames.get() > 0);
                overlapping.countDown();
                try { assertTrue(overlapping.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.sendResponseHeaders(206, end-start+1);
            exchange.getResponseBody().write(bytes, start, end-start+1); exchange.close();
        }); server.start();
        try {
            byte[] result = MineAstrRangeDownload.load(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/image",
                    preview -> firstFrames.incrementAndGet(), MineAstrChatImages.LimitedBody::new);
            assertArrayEquals(bytes, result); assertEquals(0, overlapping.getCount()); assertTrue(firstFrames.get() > 0);
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    @Test void changedRangeValidatorFallsBackToWholeFileWithoutMixingVersions() throws Exception {
        byte[] bytes = image();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        var fallbacks = new AtomicInteger();
        server.createContext("/image", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int start = 0, end = bytes.length-1;
            if (range != null) {
                String[] bounds = range.substring(6).split("-"); start = Integer.parseInt(bounds[0]); end = Math.min(end, Integer.parseInt(bounds[1]));
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
                exchange.getResponseHeaders().set("ETag", start == 0 ? "\"v1\"" : "\"v2\"");
            } else fallbacks.incrementAndGet();
            exchange.sendResponseHeaders(range == null ? 200 : 206, end-start+1);
            try { exchange.getResponseBody().write(bytes, start, end-start+1); } finally { exchange.close(); }
        }); server.start();
        try {
            byte[] result = MineAstrRangeDownload.load(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/image",
                    preview -> {}, MineAstrChatImages.LimitedBody::new);
            assertArrayEquals(bytes, result); assertEquals(1, fallbacks.get());
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
