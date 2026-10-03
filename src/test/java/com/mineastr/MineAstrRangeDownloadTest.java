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
