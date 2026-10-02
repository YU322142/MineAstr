package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MineAstrConnectionTest {
    @Test
    void stalledUpgradeTimesOutAndTheNextConnectionCanSucceed() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            Thread endpoint = new Thread(() -> {
                try {
                    // Accept TCP and read the request, but never answer the first Upgrade.
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        readWebSocketKey(socket);
                        while (socket.getInputStream().read() != -1) {
                        }
                    }
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        String key = readWebSocketKey(socket);
                        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                        .getBytes(StandardCharsets.US_ASCII)));
                        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        while (socket.getInputStream().read() != -1) {
                        }
                    }
                } catch (Exception ignored) {
                    // The assertions on the client futures report any endpoint failure.
                }
            }, "MineAstr-test-endpoint");
            endpoint.setDaemon(true);
            endpoint.start();
            URI uri = URI.create("ws://localhost:" + server.getLocalPort() + "/ws");
            var first = MineAstrBridge.connectWebSocket(
                    client, uri, "test", new WebSocket.Listener() {}, Duration.ofMillis(250));
            ExecutionException timeout = assertThrows(ExecutionException.class, () -> first.get(4, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, timeout.getCause().getCause());
            var next = MineAstrBridge.connectWebSocket(
                    client, uri, "test", new WebSocket.Listener() {}, Duration.ofSeconds(2));
            WebSocket connected = next.get(4, TimeUnit.SECONDS);
            assertTrue(!connected.isInputClosed() && !connected.isOutputClosed());
            connected.abort();
            endpoint.join(4000);
        }
    }

    @Test
    void invalidHeadersCompleteAsFailuresInsteadOfLeavingConnectingSet() {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var attempt = MineAstrBridge.connectWebSocket(client, URI.create("ws://localhost/ws"),
                    "invalid\nheader", new WebSocket.Listener() {}, Duration.ofSeconds(1));
            ExecutionException error = assertThrows(ExecutionException.class, () -> attempt.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, error.getCause().getCause());
            assertEquals("WebSocket connection failed: IllegalArgumentException", error.getCause().getMessage());
        }
    }

    @Test
    void imagePlaceholderCleanupPreservesTranslatedQuoteLineBreaks() {
        assertEquals("↪ Alice: translated quote\ntranslated answer",
                MineAstrBridge.sanitizeBotContent("↪ Alice: translated quote\ntranslated answer"));
        assertEquals("answer", MineAstrBridge.sanitizeBotContent("[图片] /opt/AstrBot/data/temp/image.png\nanswer"));
        assertEquals("answer", MineAstrBridge.sanitizeBotContent("[图片] C:\\temp\\image.png answer"));
        assertEquals("", MineAstrBridge.sanitizeBotContent("[图片] file:///opt/image.png"));
    }

    private static String readWebSocketKey(java.net.Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String key = "";
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) {
                key = line.substring(18).strip();
            }
        }
        return key;
    }
}
