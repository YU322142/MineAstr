package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;

public final class MineAstrWebSocket {
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_INBOUND_WS_CHARS = 2 * 1024 * 1024;

    private static final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private static final AtomicBoolean connecting = new AtomicBoolean(false);
    private static final AtomicLong connectionGeneration = new AtomicLong();
    private static final StringBuilder inboundBuffer = new StringBuilder();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static volatile ScheduledExecutorService reconnectExecutor = createReconnectExecutor();
    private static volatile ScheduledFuture<?> reconnectTask;
    private static volatile boolean stopping;
    private static volatile MessageHandler handler;

    private MineAstrWebSocket() {
    }

    public static void connect(MessageHandler messageHandler) {
        handler = messageHandler;
        stopping = false;
        ensureReconnectExecutor();
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            MineAstr.LOGGER.info("MineAstr 已被配置禁用。");
            return;
        }
        connectNow();
    }

    public static void disconnect() {
        stopping = true;
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        cancelReconnect();
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        }
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        reconnectExecutor = null;
        handler = null;
    }

    public static boolean isConnected() {
        WebSocket socket = webSocket.get();
        return socket != null && !socket.isInputClosed() && !socket.isOutputClosed();
    }

    public static boolean isConnecting() {
        return connecting.get();
    }

    public static boolean reconnect() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return false;
        }
        cancelReconnect();
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        connectNow();
        return true;
    }

    public static WebSocket get() {
        return webSocket.get();
    }

    public static ScheduledExecutorService executor() {
        return reconnectExecutor;
    }

    public static void send(JsonObject payload) {
        WebSocket socket = webSocket.get();
        if (socket != null && !socket.isOutputClosed()) {
            String json = payload.toString();
            socket.sendText(json, true);
        }
    }

    private static void connectNow() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean() || isConnected() || !connecting.compareAndSet(false, true)) {
            return;
        }

        URI uri;
        try {
            uri = URI.create(MineAstrConfig.WEBSOCKET_URL.get());
        } catch (IllegalArgumentException exc) {
            connecting.set(false);
            MineAstr.LOGGER.error("MineAstr websocketUrl 无效：{}", MineAstrConfig.WEBSOCKET_URL.get(), exc);
            scheduleReconnect();
            return;
        }

        long generation = connectionGeneration.get();
        httpClient
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + MineAstrConfig.TOKEN.get())
                .buildAsync(uri, new Listener())
                .whenComplete((socket, throwable) -> {
                    if (stopping || generation != connectionGeneration.get()) {
                        if (socket != null) {
                            socket.abort();
                        }
                        return;
                    }
                    connecting.set(false);
                    if (throwable != null) {
                        MineAstr.LOGGER.warn("MineAstr 连接 AstrBot 失败：{}", throwable.getMessage());
                        scheduleReconnect();
                    } else if (socket.isInputClosed() || socket.isOutputClosed()) {
                        socket.abort();
                        MineAstr.LOGGER.warn("MineAstr 与 AstrBot 的 WebSocket 在连接完成前已关闭。");
                        scheduleReconnect();
                    } else {
                        WebSocket previous = webSocket.getAndSet(socket);
                        if (previous != null && previous != socket) {
                            previous.abort();
                        }
                        sendHello(socket);
                        MineAstr.LOGGER.info("MineAstr 已连接到 AstrBot：{}", uri);
                    }
                });
    }

    private static void sendHello(WebSocket socket) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "hello");
        payload.addProperty("protocol", PROTOCOL_VERSION);
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("mod_version", MineAstr.MOD_VERSION);
        payload.addProperty("minecraft_version", SharedConstants.getCurrentVersion().name());
        JsonArray capabilities = new JsonArray();
        capabilities.add("status");
        capabilities.add("players");
        capabilities.add("player_state");
        capabilities.add("inventory");
        capabilities.add("nearby_entities");
        capabilities.add("region_features");
        capabilities.add("command");
        capabilities.add("screenshot");
        capabilities.add("performance");
        capabilities.add("notify_player");
        capabilities.add("binding");
        capabilities.add("trusted_users");
        payload.add("query_capabilities", capabilities);
        JsonArray eventCapabilities = new JsonArray();
        eventCapabilities.add("player_join");
        eventCapabilities.add("player_leave");
        eventCapabilities.add("player_death");
        eventCapabilities.add("binding_code");
        eventCapabilities.add("player_login_check");
        payload.add("event_capabilities", eventCapabilities);
        socket.sendText(payload.toString(), true);
    }

    private static void scheduleReconnect() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return;
        }
        cancelReconnect();
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        int seconds = MineAstrConfig.RECONNECT_SECONDS.getAsInt();
        reconnectTask = executor.schedule(MineAstrWebSocket::connectNow, seconds, TimeUnit.SECONDS);
    }

    private static void cancelReconnect() {
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
            reconnectTask = null;
        }
    }

    private static void ensureReconnectExecutor() {
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = createReconnectExecutor();
        }
    }

    private static ScheduledExecutorService createReconnectExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mineastr-reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    public interface MessageHandler {
        void onMessage(WebSocket socket, JsonObject message);
        void onDisconnect();
    }

    private static final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (inboundBuffer.length() + data.length() > MAX_INBOUND_WS_CHARS) {
                inboundBuffer.setLength(0);
                MineAstr.LOGGER.warn("MineAstr 已关闭过大的 AstrBot WebSocket 消息：{} chars", data.length());
                abortActiveSocket(socket, "AstrBot WebSocket 消息超过大小上限。", true);
                return CompletableFuture.completedFuture(null);
            }
            inboundBuffer.append(data);
            if (last) {
                String message = inboundBuffer.toString();
                inboundBuffer.setLength(0);
                handleIncoming(socket, message);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            boolean activeSocketClosed = webSocket.compareAndSet(socket, null);
            MineAstr.LOGGER.info("MineAstr WebSocket 已关闭：{} {}", statusCode, reason);
            if (activeSocketClosed) {
                inboundBuffer.setLength(0);
                if (handler != null) {
                    handler.onDisconnect();
                }
                if (!stopping) {
                    scheduleReconnect();
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            MineAstr.LOGGER.warn("MineAstr WebSocket 错误：{}", error.getMessage());
            abortActiveSocket(socket, "WebSocket 错误：" + error.getMessage(), true);
        }

        private void handleIncoming(WebSocket socket, String message) {
            if (socket != webSocket.get()) {
                return;
            }
            try {
                JsonObject payload = JsonParser.parseString(message).getAsJsonObject();
                if (handler != null) {
                    handler.onMessage(socket, payload);
                }
            } catch (RuntimeException exc) {
                MineAstr.LOGGER.warn("MineAstr 解析 AstrBot 消息失败：{}", MineAstrProtocol.shortenForLog(message), exc);
            }
        }

        private void abortActiveSocket(WebSocket socket, String reason, boolean scheduleReconnect) {
            boolean wasActive = webSocket.compareAndSet(socket, null);
            if (wasActive) {
                MineAstr.LOGGER.warn("MineAstr 已中止 AstrBot WebSocket：{}", reason);
                socket.abort();
                inboundBuffer.setLength(0);
                if (handler != null) {
                    handler.onDisconnect();
                }
                if (!stopping && scheduleReconnect) {
                    MineAstrWebSocket.scheduleReconnect();
                }
            }
        }
    }
}
