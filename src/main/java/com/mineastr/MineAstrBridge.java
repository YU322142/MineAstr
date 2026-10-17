package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import static com.mineastr.MineAstrChatText.sameSignText;
import static com.mineastr.MineAstrChatText.trimSignText;
import static com.mineastr.MineAstrLimits.MAX_BROADCAST_CONTENT_LENGTH;
import static com.mineastr.MineAstrLimits.MAX_BROADCAST_SENDER_LENGTH;
import static com.mineastr.MineAstrLimits.MAX_EVENT_TEXT_LENGTH;
import static com.mineastr.MineAstrLimits.MAX_INBOUND_WS_CHARS;
import static com.mineastr.MineAstrLimits.PROTOCOL_VERSION;
import static com.mineastr.MineAstrLimits.SCREENSHOT_TIMEOUT_SECONDS;
import static com.mineastr.MineAstrProtocolValues.getBoolean;
import static com.mineastr.MineAstrProtocolValues.getDouble;
import static com.mineastr.MineAstrProtocolValues.getInt;
import static com.mineastr.MineAstrProtocolValues.getString;
import static com.mineastr.MineAstrProtocolValues.isSupportedImage;
import static com.mineastr.MineAstrProtocolValues.safeErrorMessage;
import static com.mineastr.MineAstrProtocolValues.safePublicImageUrl;
import static com.mineastr.MineAstrProtocolValues.sha256;
import static com.mineastr.MineAstrProtocolValues.shortenForLog;
import static com.mineastr.MineAstrProtocolValues.startsWith;
import static com.mineastr.MineAstrProtocolValues.trimContent;
import static com.mineastr.MineAstrProtocolValues.trimFlatContent;
import static com.mineastr.MineAstrTargets.findExactPlayer;
import static com.mineastr.MineAstrTargets.findTargetLevel;
import static com.mineastr.MineAstrTargets.findTargetPlayer;
import static com.mineastr.MineAstrTargets.hasCoordinates;

/**
 * 连接生命周期与协议分发的协调入口；领域状态分别归各服务所有。
 * Coordinate transport lifecycle and protocol dispatch; domain state belongs to services.
 */
public final class MineAstrBridge implements WebSocket.Listener, MineAstrBridgeContext {
    static final Duration WEBSOCKET_CONNECT_TIMEOUT = MineAstrLimits.WEBSOCKET_CONNECT_TIMEOUT;
    static final int MAX_NATIVE_CHAT_TARGET_LANGUAGES = MineAstrLimits.MAX_NATIVE_CHAT_TARGET_LANGUAGES;
    static final int MAX_NATIVE_CHAT_PACKET_LENGTH = MineAstrLimits.MAX_NATIVE_CHAT_PACKET_LENGTH;
    static final int MIN_NATIVE_CHAT_TIMEOUT_MS = MineAstrLimits.MIN_NATIVE_CHAT_TIMEOUT_MS;
    static final int MAX_NATIVE_CHAT_TIMEOUT_MS = MineAstrLimits.MAX_NATIVE_CHAT_TIMEOUT_MS;
    static final int DEFAULT_NATIVE_CHAT_TIMEOUT_MS = MineAstrLimits.DEFAULT_NATIVE_CHAT_TIMEOUT_MS;
    private static final Gson GSON = new Gson();

    private final MineAstrCommandService commands = new MineAstrCommandService(this);
    private final MineAstrScreenshotService screenshots = new MineAstrScreenshotService(this);
    private final MineAstrBindingService bindings = new MineAstrBindingService(this);
    private final MineAstrSignService signs = new MineAstrSignService(this);
    private final MineAstrNativeChatService nativeChat = new MineAstrNativeChatService(this);

    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final Object outboundSendLock = new Object();
    private final Map<WebSocket, CompletableFuture<Void>> outboundSendTails = new HashMap<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicLong connectionGeneration = new AtomicLong();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final StringBuilder inboundBuffer = new StringBuilder();

    private final ConcurrentMap<UUID, TranslationPreference> translationPreferences = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, BotImagePreference> botImagePreferences = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, PendingImageTranslation> pendingImageTranslations = new ConcurrentHashMap<>();

    // 主线程专属队列；网络回调必须先经 MinecraftServer.execute 切回主线程。
    // Server-thread ownership: network callbacks must enter through server.execute.
    private final MineAstrServerWorkQueue serverWork = new MineAstrServerWorkQueue(8);

    public void tickServerWork() {
        if (server != null && !stopping) serverWork.tick();
    }

    private volatile MinecraftServer server;
    private volatile ScheduledExecutorService reconnectExecutor = createReconnectExecutor();
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean stopping;
    private volatile long startedAtMs;

    public void start(MinecraftServer server) {
        serverWork.clear();
        this.server = server;
        nativeChat.resetOrder(true);
        signs.start(server);
        bindings.start(server);
        this.stopping = false;
        this.startedAtMs = System.currentTimeMillis();
        ensureReconnectExecutor();
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            MineAstr.LOGGER.info("MineAstr 已被配置禁用。");
            return;
        }
        connectNow();
    }

    public void stop() {
        stopping = true;
        serverWork.clear();
        bindings.resetSessionState();
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        cancelReconnect();
        clearScreenshotState("Minecraft 服务器正在停止。");
        clearPendingLoginChecks("Minecraft 服务器正在停止。");
        disableNativeChatPolicy(null);
        clearPendingNativeChats(null, "server_stopping");
        nativeChat.resetOrder(false);
        commands.clear();
        signs.stop();
        clearPendingImageTranslations("Minecraft 服务器正在停止。");
        translationPreferences.clear();
        botImagePreferences.clear();
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        }
        server = null;
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        reconnectExecutor = null;
    }

    public boolean isConnected() {
        WebSocket socket = webSocket.get();
        return socket != null && !socket.isInputClosed() && !socket.isOutputClosed();
    }

    public boolean isStarted() {
        return server != null && !stopping;
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public boolean reconnect() {
        if (server == null || stopping || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return false;
        }
        cancelReconnect();
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        disableNativeChatPolicy(null);
        clearPendingNativeChats(null, "manual_reconnect");
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        connectNow();
        return true;
    }

    public void forwardChat(ServerPlayer player, String rawText) {
        if (server == null || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return;
        }
        WebSocket socket = webSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            MineAstr.LOGGER.debug("MineAstr 未连接，已丢弃本条 Minecraft 聊天。");
            return;
        }
        String content = trimContent(rawText, MineAstrConfig.MAX_MESSAGE_LENGTH.getAsInt());
        if (content.isEmpty()) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "chat");
        payload.addProperty("message_id", UUID.randomUUID().toString());
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().getName());
        payload.addProperty("content", content);
        sendJson(socket, payload);
    }

    public void forwardPlayerJoin(ServerPlayer player) {
        sendPlayerEvent("player_join", player, null);
    }

    public void forwardPlayerLeave(ServerPlayer player) {
        sendPlayerEvent("player_leave", player, null);
    }

    public void forwardPlayerDeath(ServerPlayer player, DamageSource damageSource) {
        WebSocket socket = webSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        JsonObject payload = eventEnvelope("player_death");
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().getName());

        String deathMessage = trimFlatContent(
                player.getCombatTracker().getDeathMessage().getString(),
                MAX_EVENT_TEXT_LENGTH);
        if (!deathMessage.isBlank()) {
            payload.addProperty("death_message", deathMessage);
            payload.addProperty("reason", deathMessage);
        }
        String deathType = trimFlatContent(damageSource.getMsgId(), 128);
        if (!deathType.isBlank()) {
            payload.addProperty("death_type", deathType);
        }
        if (damageSource.getEntity() != null) {
            payload.addProperty(
                    "attacker",
                    trimFlatContent(damageSource.getEntity().getDisplayName().getString(), MAX_EVENT_TEXT_LENGTH));
        }
        if (damageSource.getDirectEntity() != null) {
            payload.addProperty(
                    "direct_entity",
                    trimFlatContent(damageSource.getDirectEntity().getDisplayName().getString(), MAX_EVENT_TEXT_LENGTH));
        }
        var weapon = damageSource.getWeaponItem();
        if (weapon != null && !weapon.isEmpty()) {
            payload.addProperty(
                    "weapon",
                    trimFlatContent(weapon.getDisplayName().getString(), MAX_EVENT_TEXT_LENGTH));
        }
        sendJson(socket, payload);
    }

    private void sendPlayerEvent(String event, ServerPlayer player, String deathMessage) {
        WebSocket socket = webSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        JsonObject payload = eventEnvelope(event);
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().getName());
        if (deathMessage != null && !deathMessage.isBlank()) {
            payload.addProperty("death_message", deathMessage);
            payload.addProperty("reason", deathMessage);
        }
        sendJson(socket, payload);
    }

    public void unregisterClientCapability(ServerPlayer player) {
        screenshots.unregisterPlayer(player);
        nativeChat.unregisterPlayer(player.getUUID());
        translationPreferences.remove(player.getUUID());
        botImagePreferences.remove(player.getUUID());

    }

    public void registerTranslationPreference(
            ServerPlayer player, boolean translationsEnabled, boolean showOriginal) {
        translationPreferences.put(
                player.getUUID(),
                new TranslationPreference(translationsEnabled, showOriginal));
        MineAstr.LOGGER.debug(
                "MineAstr 已记录玩家 {} 的翻译显示偏好：enabled={} show_original={}",
                player.getGameProfile().getName(),
                translationsEnabled,
                showOriginal);
    }

    public void registerBotImagePreference(
            ServerPlayer player, boolean enabled, boolean chatImageAvailable) {
        botImagePreferences.put(
                player.getUUID(),
                new BotImagePreference(enabled, chatImageAvailable));
        MineAstr.LOGGER.debug(
                "MineAstr 已记录玩家 {} 的 Bot 图片偏好：enabled={} chatimage={}",
                player.getGameProfile().getName(),
                enabled,
                chatImageAvailable);
    }

    /** 由玩家命令直接设置图片接收偏好（丢包备选方案），保留已记录的 chatImageAvailable 状态。 */
    public void setPlayerImageReception(ServerPlayer player, boolean enabled) {
        BotImagePreference existing = botImagePreferences.get(player.getUUID());
        boolean chatImageAvailable = existing != null && existing.chatImageAvailable();
        botImagePreferences.put(player.getUUID(), new BotImagePreference(enabled, chatImageAvailable));
    }

    /**
     * Public server-side entry point for external client mods that want
     * AstrBot to translate text found in an image. The image is forwarded
     * over the existing MineAstr WebSocket and the result is returned to the
     * requesting client only.
     */
    public void handleImageTranslationQuery(
            ServerPlayer player,
            MineAstrPayloads.ImageTranslationQuery query) {
        if (player == null || query == null) {
            return;
        }
        if (query.imageBytes() == null
                || query.imageBytes().length == 0
                || query.imageBytes().length > MineAstrPayloads.MAX_IMAGE_TRANSLATION_BYTES) {
            sendImageTranslationFailure(player, query.requestId(), "image_too_large_or_empty");
            return;
        }
        if (!MineAstrNetwork.canSendImageTranslationResult(player)) {
            return;
        }
        WebSocket socket = webSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            sendImageTranslationFailure(player, query.requestId(), "astrbot_unavailable");
            return;
        }
        String requestId = trimFlatContent(query.requestId(), 64);
        if (requestId.isBlank() || pendingImageTranslations.containsKey(requestId)) {
            sendImageTranslationFailure(player, requestId, "duplicate_request");
            return;
        }
        String mimeType = trimFlatContent(query.mimeType(), MineAstrPayloads.MAX_MIME_LENGTH);
        if (mimeType.isBlank()) {
            mimeType = "image/jpeg";
        }
        String targetLanguages = trimFlatContent(query.targetLanguages(), 256);
        String context = trimContent(query.context(), MineAstrPayloads.MAX_IMAGE_TRANSLATION_CONTEXT_LENGTH);
        String prompt = trimContent(query.prompt(), MineAstrPayloads.MAX_IMAGE_TRANSLATION_PROMPT_LENGTH);
        PendingImageTranslation pending = new PendingImageTranslation(
                socket,
                requestId,
                player.getUUID());
        if (pendingImageTranslations.putIfAbsent(requestId, pending) != null) {
            sendImageTranslationFailure(player, requestId, "duplicate_request");
            return;
        }
        pending.timeout = scheduleImageTranslationTimeout(requestId);

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "image_translate_request");
        payload.addProperty("message_id", requestId);
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().getName());
        payload.addProperty("mime_type", mimeType);
        payload.addProperty("target_languages", targetLanguages);
        payload.addProperty("context", context);
        payload.addProperty("prompt", prompt);
        payload.addProperty("image_base64", Base64.getEncoder().encodeToString(query.imageBytes()));
        sendJson(socket, payload);
    }

    private void sendImageTranslationFailure(ServerPlayer player, String requestId, String error) {
        if (player == null) {
            return;
        }
        MineAstrNetwork.sendImageTranslationResult(
                player,
                new MineAstrPayloads.ImageTranslationResult(
                        trimFlatContent(requestId, 64),
                        "",
                        "",
                        Map.of(),
                        false,
                        false,
                        trimFlatContent(error, MineAstrPayloads.MAX_ERROR_LENGTH)));
    }

    private void connectNow() {
        if (server == null || stopping || !MineAstrConfig.ENABLED.getAsBoolean() || isConnected() || !connecting.compareAndSet(false, true)) {
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
        MineAstr.LOGGER.debug("MineAstr 开始异步连接：generation={} handshakeTimeout={}s",
                generation, WEBSOCKET_CONNECT_TIMEOUT.toSeconds());
        connectWebSocket(httpClient, uri, MineAstrConfig.TOKEN.get(), this, WEBSOCKET_CONNECT_TIMEOUT)
                .whenComplete((socket, throwable) -> {
                    if (stopping || generation != connectionGeneration.get()) {
                        if (socket != null) {
                            socket.abort();
                        }
                        return;
                    }
                    connecting.set(false);
                    if (throwable != null) {
                        MineAstr.LOGGER.warn("MineAstr 连接 AstrBot 失败：{}（握手超时上限={}s，稍后重试）",
                                throwable.getMessage(), WEBSOCKET_CONNECT_TIMEOUT.toSeconds());
                        scheduleReconnect();
                    } else if (socket.isInputClosed() || socket.isOutputClosed()) {
                        socket.abort();
                        MineAstr.LOGGER.warn("MineAstr 与 AstrBot 的 WebSocket 在连接完成前已关闭。");
                        scheduleReconnect();
                    } else {
                        WebSocket previous = webSocket.getAndSet(socket);
                        if (previous != null && previous != socket) {
                            disableNativeChatPolicy(previous);
                            clearPendingNativeChats(previous, "connection_replaced");
                            previous.abort();
                        }
                        nativeChat.disableNativeChatPolicy(null);
                        sendHello(socket);
                        MineAstr.LOGGER.info("MineAstr 已连接到 AstrBot：{}", uri);
                    }
                });
    }

    static CompletableFuture<WebSocket> connectWebSocket(
            HttpClient client, URI uri, String token, WebSocket.Listener listener, Duration timeout) {
        try {
            // HttpClient's TCP timeout does not bound the HTTP Upgrade handshake.
            return client.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .buildAsync(uri, listener)
                    .handle((socket, error) -> {
                        if (error != null) {
                            throw new CompletionException(safeConnectionFailure(error));
                        }
                        return socket;
                    });
        } catch (RuntimeException error) {
            // Complete through the same callback so connecting is always cleared.
            // Do not put a rejected Authorization header's value in the log message.
            return CompletableFuture.failedFuture(safeConnectionFailure(error));
        }
    }

    private static IllegalStateException safeConnectionFailure(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        // JDK may reject a header either synchronously or through the returned future.
        return new IllegalStateException(
                "WebSocket connection failed: " + error.getClass().getSimpleName(), error);
    }

    private void sendHello(WebSocket socket) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "hello");
        payload.addProperty("protocol", PROTOCOL_VERSION);
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("mod_version", MineAstr.MOD_VERSION);
        payload.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
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
        JsonArray chatCapabilities = new JsonArray();
        chatCapabilities.add("native_chat_translation");
        payload.add("chat_capabilities", chatCapabilities);
        sendJson(socket, payload);
    }

    private void scheduleReconnect() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return;
        }
        cancelReconnect();
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        int seconds = MineAstrConfig.RECONNECT_SECONDS.getAsInt();
        reconnectTask = executor.schedule(this::connectNow, seconds, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
            reconnectTask = null;
        }
    }

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
            disableNativeChatPolicy(socket);
            clearPendingNativeChats(socket, "connection_closed");
            clearPendingScreenshots("AstrBot WebSocket 已断开。");
            clearPendingLoginChecks("AstrBot WebSocket 已断开。");
            clearPendingImageTranslations("AstrBot WebSocket 已断开。");
            commands.clear();
                    scheduleReconnect();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        boolean activeSocketFailed = webSocket.compareAndSet(socket, null);
        MineAstr.LOGGER.warn("MineAstr WebSocket 出错：{}", error.getMessage());
        if (activeSocketFailed) {
            inboundBuffer.setLength(0);
            disableNativeChatPolicy(socket);
            clearPendingNativeChats(socket, "connection_error");
            clearPendingScreenshots("AstrBot WebSocket 出错。");
            clearPendingLoginChecks("AstrBot WebSocket 出错。");
            clearPendingImageTranslations("AstrBot WebSocket 出错。");
            commands.clear();
                    scheduleReconnect();
        }
    }

    private void handleIncoming(WebSocket socket, String message) {
        JsonObject payload;
        try {
            JsonElement element = JsonParser.parseString(message);
            if (!element.isJsonObject()) {
                MineAstr.LOGGER.warn("MineAstr 已忽略来自 AstrBot 的非对象 JSON：{}", shortenForLog(message));
                return;
            }
            payload = element.getAsJsonObject();
        } catch (RuntimeException exc) {
            MineAstr.LOGGER.warn("MineAstr 已忽略来自 AstrBot 的无效 JSON：{}", shortenForLog(message));
            return;
        }

        String type = getString(payload, "type", "");
        if ("chat".equals(type)) {
            handleChat(payload);
        } else if ("native_chat_policy".equals(type)) {
            handleNativeChatPolicy(socket, payload);
        } else if ("native_chat_translate_result".equals(type)) {
            handleNativeChatTranslationResult(socket, payload);
        } else if ("sign_translate_result".equals(type)) {
            handleSignTranslationResult(socket, payload);
        } else if ("image_translate_result".equals(type)) {
            handleImageTranslationResult(socket, payload);
        } else if ("query".equals(type)) {
            handleQuery(socket, payload);
        } else if ("event_result".equals(type)) {
            handleEventResult(socket, payload);
        } else if ("pong".equals(type)) {
            MineAstr.LOGGER.debug("MineAstr 已收到 AstrBot 的 pong。");
        } else if ("error".equals(type)) {
            String error = getString(payload, "message", "unknown");
            MineAstr.LOGGER.warn("MineAstr 收到 AstrBot 错误：{}", error);
        } else {
            MineAstr.LOGGER.debug("MineAstr 已忽略不支持的 AstrBot 消息类型：{}", type);
        }
    }

    private static ScheduledExecutorService createReconnectExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "MineAstr-Reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void ensureReconnectExecutor() {
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            reconnectExecutor = createReconnectExecutor();
        }
    }

    private void handleChat(JsonObject payload) {
        String senderName = trimFlatContent(getString(payload, "sender_name", MineAstrConfig.BOT_DISPLAY_NAME.get()), MAX_BROADCAST_SENDER_LENGTH);
        String content = sanitizeBotContent(
                trimContent(getString(payload, "content", ""), MAX_BROADCAST_CONTENT_LENGTH));
        if (senderName.isEmpty()) {
            senderName = trimFlatContent(MineAstrConfig.BOT_DISPLAY_NAME.get(), MAX_BROADCAST_SENDER_LENGTH);
        }
        List<BotImage> images = parseBotImages(payload);
        if (content.isBlank() && images.isEmpty()) {
            return;
        }
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        JsonObject translations = new JsonObject();
        if (payload.has("translations") && payload.get("translations").isJsonObject()) {
            for (var entry : payload.getAsJsonObject("translations").entrySet()) {
                String language = entry.getKey().strip().replace('-', '_').toLowerCase(Locale.ROOT);
                if (!language.matches("[a-z0-9_]{2,16}") || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) {
                    continue;
                }
                String translated = sanitizeBotContent(
                        trimContent(entry.getValue().getAsString(), MAX_BROADCAST_CONTENT_LENGTH));
                if (!translated.isBlank()) {
                    translations.addProperty(language, translated);
                }
            }
        }
        boolean defaultShowOriginal = getBoolean(payload, "show_original", false);
        String finalSenderName = senderName;
        String finalContent = content;
        String senderPlatform = normalizeChatPlatform(getString(payload, "sender_platform", "minecraft"));
        currentServer.execute(() -> {
            MineAstr.LOGGER.info(
                    "[{}] {}{}",
                    finalSenderName,
                    finalContent,
                    images.isEmpty() ? "" : " [图片×" + images.size() + "]");
            for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
                boolean receivesImages = canReceiveBotImages(player);
                // 拒收纯图片时整条跳过；兼容插件附带的旧版文字占位符。
                // Skip image-only messages for opt-outs, including the plugin's legacy text marker.
                if (!receivesImages && !images.isEmpty()
                        && (finalContent.isBlank() || "[图片]".equals(finalContent.strip()))) {
                    continue;
                }
                boolean inline = MineAstrNetwork.canSendChatPresentation(player);
                if (inline) {
                    List<MineAstrPayloads.ImageRef> refs = receivesImages
                            ? images.stream().map(image -> new MineAstrPayloads.ImageRef(UUID.randomUUID().toString(), image.name)).toList()
                            : List.of();
                    String body = renderChatBody(player, finalContent, translations, defaultShowOriginal).getString();
                    MineAstrNetwork.sendChatPresentation(player,
                            new MineAstrPayloads.ChatPresentation(senderPlatform, finalSenderName, body, refs));
                    for (int index = 0; index < refs.size(); index++) {
                        sendBotImage(player, finalSenderName, images.get(index), refs.get(index).id());
                    }
                    continue;
                }
                if (!finalContent.isBlank()) {
                    player.sendSystemMessage(renderTranslatedChat(
                            player,
                            finalSenderName,
                            finalContent,
                            translations,
                            defaultShowOriginal));
                }
                if (receivesImages) {
                    images.forEach(image -> sendBotImage(player, finalSenderName, image));
                }
            }
        });
    }

    private List<BotImage> parseBotImages(JsonObject payload) {
        if (!MineAstrConfig.ENABLE_BOT_IMAGE_MESSAGES.getAsBoolean()
                || !payload.has("media")
                || !payload.get("media").isJsonArray()) {
            return List.of();
        }
        List<BotImage> images = new ArrayList<>();
        for (JsonElement item : payload.getAsJsonArray("media")) {
            if (images.size() >= 8) {
                break;
            }
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject media = item.getAsJsonObject();
            if (!"image".equalsIgnoreCase(trimFlatContent(getString(media, "type", "image"), 16))) {
                continue;
            }
            String name = trimFlatContent(
                    getString(media, "name", "image"),
                    MineAstrPayloads.MAX_BOT_IMAGE_NAME_LENGTH);
            if (name.isBlank()) {
                name = "image";
            }
            String sourceUrl = safePublicImageUrl(getString(media, "url", ""));
            String mimeType = trimFlatContent(
                    getString(media, "mime_type", ""),
                    MineAstrPayloads.MAX_MIME_LENGTH).toLowerCase(Locale.ROOT);
            byte[] bytes = new byte[0];
            String encoded = media.has("data_base64")
                            && media.get("data_base64").isJsonPrimitive()
                            && media.get("data_base64").getAsJsonPrimitive().isString()
                    ? media.get("data_base64").getAsString().strip()
                    : "";
            if (!encoded.isBlank()) {
                int maxEncodedLength = ((MineAstrPayloads.MAX_BOT_IMAGE_BYTES + 2) / 3) * 4 + 8;
                if (encoded.length() > maxEncodedLength) {
                    MineAstr.LOGGER.warn("MineAstr 忽略超过内联上限的 Bot 图片：{}", name);
                    continue;
                }
                try {
                    bytes = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException exc) {
                    MineAstr.LOGGER.warn("MineAstr 忽略 Base64 无效的 Bot 图片：{}", name);
                    continue;
                }
                if (bytes.length == 0 || bytes.length > MineAstrPayloads.MAX_BOT_IMAGE_BYTES) {
                    continue;
                }
                int declaredSize = getInt(
                        media, "size", bytes.length, 0, MineAstrPayloads.MAX_BOT_IMAGE_BYTES);
                if (declaredSize > 0 && declaredSize != bytes.length) {
                    MineAstr.LOGGER.warn("MineAstr 忽略长度声明不一致的 Bot 图片：{}", name);
                    continue;
                }
                if (!isSupportedImage(bytes, mimeType)) {
                    MineAstr.LOGGER.warn("MineAstr 忽略格式无效的 Bot 图片：{}", name);
                    continue;
                }
                String actualSha = sha256(bytes);
                String declaredSha = trimFlatContent(getString(media, "sha256", ""), 64)
                        .toLowerCase(Locale.ROOT);
                if (!declaredSha.isBlank()
                        && (!declaredSha.matches("[0-9a-f]{64}") || !declaredSha.equals(actualSha))) {
                    MineAstr.LOGGER.warn("MineAstr 忽略哈希不匹配的 Bot 图片：{}", name);
                    continue;
                }
                images.add(new BotImage(name, mimeType, sourceUrl, actualSha, bytes));
            } else if (!sourceUrl.isBlank()) {
                images.add(new BotImage(name, mimeType, sourceUrl, "", bytes));
            }
        }
        return List.copyOf(images);
    }

    private boolean canReceiveBotImages(ServerPlayer player) {
        BotImagePreference preference = botImagePreferences.get(player.getUUID());
        return MineAstrConfig.ENABLE_BOT_IMAGE_MESSAGES.getAsBoolean()
                && preference != null
                && preference.enabled
                && (preference.chatImageAvailable || MineAstrNetwork.canSendChatPresentation(player))
                && MineAstrNetwork.canSendBotImageChunk(player);
    }

    private static void sendBotImage(ServerPlayer player, String senderName, BotImage image) {
        sendBotImage(player, senderName, image, UUID.randomUUID().toString());
    }

    private static void sendBotImage(ServerPlayer player, String senderName, BotImage image, String messageId) {
        if (image.bytes.length == 0) {
            MineAstrNetwork.sendBotImageChunk(
                    player,
                    new MineAstrPayloads.BotImageChunk(
                            messageId,
                            senderName,
                            image.name,
                            image.mimeType,
                            image.sourceUrl,
                            image.sha256,
                            0,
                            0,
                            0,
                            new byte[0]));
            return;
        }
        int totalChunks = (image.bytes.length + MineAstrPayloads.MAX_CHUNK_BYTES - 1)
                / MineAstrPayloads.MAX_CHUNK_BYTES;
        if (totalChunks < 1 || totalChunks > MineAstrPayloads.MAX_BOT_IMAGE_CHUNKS) {
            return;
        }
        for (int index = 0; index < totalChunks; index++) {
            int start = index * MineAstrPayloads.MAX_CHUNK_BYTES;
            int end = Math.min(image.bytes.length, start + MineAstrPayloads.MAX_CHUNK_BYTES);
            MineAstrNetwork.sendBotImageChunk(
                    player,
                    new MineAstrPayloads.BotImageChunk(
                            messageId,
                            senderName,
                            image.name,
                            image.mimeType,
                            image.sourceUrl,
                            image.sha256,
                            index,
                            totalChunks,
                            image.bytes.length,
                            Arrays.copyOfRange(image.bytes, start, end)));
        }
    }

    private void handleImageTranslationResult(WebSocket socket, JsonObject payload) {
        String requestId = trimFlatContent(getString(payload, "message_id", ""), 64);
        PendingImageTranslation pending = pendingImageTranslations.get(requestId);
        if (pending == null || pending.socket != socket) {
            MineAstr.LOGGER.warn("MineAstr 忽略未知或来源不匹配的图片翻译响应：{}", requestId);
            return;
        }
        if (!pendingImageTranslations.remove(requestId, pending)) {
            MineAstr.LOGGER.debug("MineAstr 已忽略重复的图片翻译响应：{}", requestId);
            return;
        }
        pending.cancelTimeout();
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        boolean ok = getBoolean(payload, "ok", false);
        String sourceLanguage = trimFlatContent(getString(payload, "source_language", ""), 32);
        String sourceText = trimSignText(getString(payload, "source_text", ""));
        Map<String, String> translations = new HashMap<>();
        if (ok && payload.has("translations") && payload.get("translations").isJsonObject()) {
            for (var entry : payload.getAsJsonObject("translations").entrySet()) {
                String language = entry.getKey().strip().replace('-', '_').toLowerCase(Locale.ROOT);
                if (!language.matches("[a-z0-9_]{2,16}")
                        || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) {
                    continue;
                }
                String translated = trimSignText(entry.getValue().getAsString());
                if (!translated.isBlank()) {
                    translations.put(language, translated);
                }
            }
        }
        boolean showOriginal = getBoolean(payload, "show_original", false);
        String error = trimFlatContent(getString(payload, "error", ""), MineAstrPayloads.MAX_ERROR_LENGTH);
        boolean usable = ok && (!translations.isEmpty() || !sourceText.isBlank());
        currentServer.execute(() -> {
            ServerPlayer player = currentServer.getPlayerList().getPlayer(pending.playerUuid);
            if (player == null) {
                return;
            }
            MineAstrNetwork.sendImageTranslationResult(
                    player,
                    new MineAstrPayloads.ImageTranslationResult(
                            pending.requestId,
                            sourceLanguage,
                            sourceText,
                            translations,
                            showOriginal,
                            usable,
                            usable ? "" : (error.isBlank() ? "translation_failed" : error)));
        });
    }

    private Component renderTranslatedChat(ServerPlayer player, String senderName, String original,
            JsonObject translations, boolean defaultShowOriginal) {
        return Component.literal("[" + senderName + "] ").append(
                renderChatBody(player, original, translations, defaultShowOriginal));
    }

    private Component renderChatBody(
            ServerPlayer player,
            String original,
            JsonObject translations,
            boolean defaultShowOriginal) {
        TranslationPreference preference = translationPreferences.get(player.getUUID());
        if (preference != null && !preference.translationsEnabled) {
            return Component.literal(original);
        }
        String language = player.clientInformation().language().strip().replace('-', '_').toLowerCase(Locale.ROOT);
        String translated = MineAstrChatText.selectTranslation(translations, language);
        if (translated.isBlank() || sameSignText(original, translated)) {
            return Component.literal(original);
        }
        var component = Component.literal(translated);
        boolean showOriginal = preference == null ? defaultShowOriginal : preference.showOriginal;
        if (showOriginal) {
            String fallback = language.startsWith("zh_") ? "[原文] " : "[Original] ";
            component.append("\n");
            component.append(Component.translatableWithFallback(
                    "message.mineastr.original_prefix", fallback));
            component.append(Component.literal(original));
        }
        return component;
    }

    private void handleQuery(WebSocket socket, JsonObject payload) {
        String query = trimFlatContent(getString(payload, "query", ""), 64).toLowerCase(Locale.ROOT);
        String messageId = trimFlatContent(getString(payload, "message_id", UUID.randomUUID().toString()), 64);
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            sendQueryError(socket, messageId, query, "Minecraft 服务器尚未启动。");
            return;
        }
        long generation = connectionGeneration.get();
        currentServer.execute(() -> {
            if (stopping || server != currentServer || socket != webSocket.get()
                    || generation != connectionGeneration.get()) return;
            try {
                switch (query) {
                    case "status" -> sendQueryResult(socket, messageId, query, buildStatusData(currentServer));
                    case "players" -> sendQueryResult(socket, messageId, query, buildPlayersData(currentServer));
                    case "player_state" -> handlePlayerStateQuery(socket, messageId, payload, currentServer);
                    case "inventory" -> handleInventoryQuery(socket, messageId, payload, currentServer);
                    case "nearby_entities" -> handleNearbyEntitiesQuery(socket, messageId, payload, currentServer);
                    case "region_features" -> handleRegionQuery(socket, messageId, payload, currentServer);
                    case "command" -> handleCommandQuery(socket, messageId, payload, currentServer);
                    case "screenshot" -> handleScreenshotQuery(socket, messageId, payload, currentServer);
                    case "performance" -> sendQueryResult(socket, messageId, query, buildPerformanceData(currentServer));
                    case "notify_player" -> handleNotifyPlayerQuery(socket, messageId, payload, currentServer);
                    case "binding" -> handleBindingQuery(socket, messageId, payload, currentServer);
                    case "trusted_users" -> handleTrustedUsersQuery(socket, messageId, payload);
                    default -> sendQueryError(socket, messageId, query, "不支持的查询类型：" + query);
                }
            } catch (RuntimeException exc) {
                MineAstr.LOGGER.warn("MineAstr 查询 {} 处理失败：{}", query, exc.getMessage());
                sendQueryError(socket, messageId, query, "查询处理失败：" + safeErrorMessage(exc));
            }
        });
    }

    private void handlePlayerStateQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_PLAYER_STATE_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "player_state", "服务端已禁用玩家状态工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "player_state", "未找到目标在线玩家。");
            return;
        }
        sendQueryResult(socket, messageId, "player_state", MineAstrTools.buildPlayerState(player));
    }

    private void handleInventoryQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_INVENTORY_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "inventory", "服务端已禁用背包查询工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "inventory", "未找到目标在线玩家。");
            return;
        }
        boolean includeEnderChest = getBoolean(payload, "include_ender_chest", false);
        sendQueryResult(socket, messageId, "inventory", MineAstrTools.buildInventory(player, includeEnderChest));
    }

    private void handleNearbyEntitiesQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_NEARBY_ENTITIES_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "nearby_entities", "服务端已禁用附近实体工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "nearby_entities", "未找到目标在线玩家。");
            return;
        }
        double radius = getDouble(payload, "radius", 12.0, 1.0, 32.0);
        sendQueryResult(socket, messageId, "nearby_entities", MineAstrTools.buildNearbyEntities(player, radius));
    }

    private void handleRegionQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_REGION_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "region_features", "服务端已禁用区域特征工具。");
            return;
        }
        boolean coordinateMode = hasCoordinates(payload);
        ServerPlayer player = coordinateMode ? null : findTargetPlayer(currentServer, payload);
        ServerLevel level;
        BlockPos fallbackCenter;
        if (player != null) {
            level = player.serverLevel();
            fallbackCenter = player.blockPosition();
        } else {
            level = findTargetLevel(currentServer, payload);
            if (level == null || !coordinateMode) {
                sendQueryError(socket, messageId, "region_features", "请指定在线玩家，或提供有效的 dimension、x、y、z。");
                return;
            }
            fallbackCenter = new BlockPos(0, level.getSeaLevel(), 0);
        }

        int x = getInt(payload, "x", fallbackCenter.getX(), -30_000_000, 30_000_000);
        int y = getInt(payload, "y", fallbackCenter.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int z = getInt(payload, "z", fallbackCenter.getZ(), -30_000_000, 30_000_000);
        int horizontalRadius = getInt(payload, "horizontal_radius", 8, 1, 24);
        int verticalRadius = getInt(payload, "vertical_radius", 6, 1, 16);
        long volume = (horizontalRadius * 2L + 1L) * (verticalRadius * 2L + 1L) * (horizontalRadius * 2L + 1L);
        int maxBlocks = MineAstrConfig.REGION_MAX_BLOCKS.getAsInt();
        if (volume > maxBlocks) {
            sendQueryError(socket, messageId, "region_features", "请求区域过大：" + volume + " 方块，服务端上限为 " + maxBlocks + "。");
            return;
        }
        BlockPos center = new BlockPos(x, y, z);
        if (!level.hasChunk(x >> 4, z >> 4)) {
            sendQueryError(socket, messageId, "region_features", "目标中心所在区块尚未加载；为避免卡服，MineAstr 不会强制加载新区块。");
            return;
        }
        MineAstrRegionScan scan = new MineAstrRegionScan(level, center, horizontalRadius, verticalRadius);
        long generation = connectionGeneration.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        if (!serverWork.submit(() -> {
            if (server != currentServer || stopping || socket != webSocket.get()
                    || generation != connectionGeneration.get()) return true;
            if (System.nanoTime() >= deadline) {
                sendQueryError(socket, messageId, "region_features", "region_scan_timeout");
                return true;
            }
            if (!scan.step()) return false;
            sendQueryResult(socket, messageId, "region_features", scan.result());
            return true;
        }, error -> sendQueryError(socket, messageId, "region_features", "region_scan_failed"))) {
            sendQueryError(socket, messageId, "region_features", "server_work_queue_full");
        }
    }

    private JsonObject buildPerformanceData(MinecraftServer currentServer) {
        double mspt = currentServer.getAverageTickTimeNanos() / 1_000_000.0;
        double tps = mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();

        JsonObject data = new JsonObject();
        data.addProperty("tps", Math.round(tps * 100.0) / 100.0);
        data.addProperty("mspt", Math.round(mspt * 100.0) / 100.0);
        data.addProperty("memory_used_mb", usedBytes / (1024L * 1024L));
        data.addProperty("memory_max_mb", runtime.maxMemory() / (1024L * 1024L));
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
            double cpuLoad = extended.getCpuLoad();
            if (cpuLoad >= 0.0) {
                data.addProperty("cpu_percent", Math.round(cpuLoad * 10_000.0) / 100.0);
            }
            double processCpuLoad = extended.getProcessCpuLoad();
            if (processCpuLoad >= 0.0) {
                data.addProperty("process_cpu_percent", Math.round(processCpuLoad * 10_000.0) / 100.0);
            }
        }
        return data;
    }

    private void handleNotifyPlayerQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_PLAYER_NOTIFICATIONS.getAsBoolean()) {
            sendQueryError(socket, messageId, "notify_player", "player_notifications_disabled");
            return;
        }
        String requestedName = trimFlatContent(getString(payload, "player_name", ""), 64);
        ServerPlayer player = findExactPlayer(currentServer, requestedName);
        if (player == null) {
            sendQueryError(socket, messageId, "notify_player", "player_not_online");
            return;
        }
        String sender = trimFlatContent(getString(payload, "sender_name", "AstrBot"), 64);
        String message = trimFlatContent(
                getString(payload, "message", ""), MineAstrConfig.NOTIFICATION_MAX_LENGTH.getAsInt());
        if (message.isBlank()) {
            sendQueryError(socket, messageId, "notify_player", "empty_message");
            return;
        }

        String fallback = player.clientInformation().language().toLowerCase(Locale.ROOT).startsWith("zh_")
                ? "[提醒/" + sender + "] " + message
                : "[Notice/" + sender + "] " + message;
        Component text = Component.translatableWithFallback(
                "message.mineastr.player_notification",
                fallback,
                sender,
                message);
        player.sendSystemMessage(text);
        if (MineAstrConfig.NOTIFY_ACTION_BAR.getAsBoolean()) {
            player.displayClientMessage(text, true);
        }
        if (MineAstrConfig.NOTIFY_TITLE.getAsBoolean()) {
            player.connection.send(new ClientboundSetTitleTextPacket(text));
        }
        if (MineAstrConfig.NOTIFY_SOUND.getAsBoolean()) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        MineAstr.LOGGER.info("MineAstr 已向玩家 {} 发送来自 {} 的定向提醒。", player.getGameProfile().getName(), sender);

        JsonObject data = new JsonObject();
        data.addProperty("player_uuid", player.getUUID().toString());
        data.addProperty("player_name", player.getGameProfile().getName());
        data.addProperty("notified", true);
        sendQueryResult(socket, messageId, "notify_player", data);
    }

    private JsonObject buildStatusData(MinecraftServer currentServer) {
        PlayerList playerList = currentServer.getPlayerList();
        JsonObject data = new JsonObject();
        data.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        data.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        data.addProperty("mod_version", MineAstr.MOD_VERSION);
        data.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
        data.addProperty("dedicated", currentServer.isDedicatedServer());
        data.addProperty("player_count", playerList.getPlayerCount());
        data.addProperty("max_players", playerList.getMaxPlayers());
        data.addProperty("uptime_ms", Math.max(0L, System.currentTimeMillis() - startedAtMs));
        BlockPos spawn = currentServer.overworld().getSharedSpawnPos();
        JsonObject spawnData = new JsonObject();
        spawnData.addProperty("dimension", Level.OVERWORLD.location().toString());
        spawnData.addProperty("x", spawn.getX());
        spawnData.addProperty("y", spawn.getY());
        spawnData.addProperty("z", spawn.getZ());
        data.add("world_spawn", spawnData);
        JsonArray names = new JsonArray();
        JsonArray screenshotCapableNames = new JsonArray();
        for (ServerPlayer player : playerList.getPlayers()) {
            names.add(player.getGameProfile().getName());
            if (screenshots.supports(player.getUUID())) {
                screenshotCapableNames.add(player.getGameProfile().getName());
            }
        }
        data.add("online_player_names", names);
        data.add("screenshot_capable_player_names", screenshotCapableNames);
        return data;
    }

    private JsonObject buildPlayersData(MinecraftServer currentServer) {
        PlayerList playerList = currentServer.getPlayerList();
        JsonObject data = new JsonObject();
        data.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        data.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        data.addProperty("player_count", playerList.getPlayerCount());
        data.addProperty("max_players", playerList.getMaxPlayers());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : playerList.getPlayers()) {
            JsonObject playerData = new JsonObject();
            playerData.addProperty("uuid", player.getUUID().toString());
            playerData.addProperty("name", player.getGameProfile().getName());
            playerData.addProperty("display_name", player.getDisplayName().getString());
            playerData.addProperty("screenshot_supported", screenshots.supports(player.getUUID()));
            players.add(playerData);
        }
        data.add("players", players);
        return data;
    }

    public void sendQueryResult(WebSocket socket, String messageId, String query, JsonObject data) {
        JsonObject payload = queryEnvelope(messageId, query, true);
        payload.add("data", data);
        sendJson(socket, payload);
    }

    public void sendQueryError(WebSocket socket, String messageId, String query, String error) {
        JsonObject payload = queryEnvelope(messageId, query, false);
        payload.addProperty("error", error);
        sendJson(socket, payload);
    }

    public JsonObject queryEnvelope(String messageId, String query, boolean ok) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "query_result");
        payload.addProperty("message_id", messageId);
        payload.addProperty("query", query);
        payload.addProperty("ok", ok);
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        return payload;
    }

    public JsonObject eventEnvelope(String event) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "event");
        payload.addProperty("event", event);
        payload.addProperty("message_id", UUID.randomUUID().toString());
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        return payload;
    }

    public void sendJson(WebSocket socket, JsonObject payload) {
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        sendTextSerialized(socket, GSON.toJson(payload)).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                handleSendFailure(socket, throwable);
            }
        });
    }

    public CompletableFuture<Void> sendTextSerialized(WebSocket socket, String text) {
        synchronized (outboundSendLock) {
            CompletableFuture<Void> previous = outboundSendTails.get(socket);
            CompletableFuture<Void> start = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((ignored, error) -> null);
            CompletableFuture<Void> current = start.thenCompose(ignored -> {
                if (socket == null || socket.isOutputClosed()) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("WebSocket output is closed"));
                }
                try {
                    return socket.sendText(text, true).thenApply(ignoredSocket -> null);
                } catch (Throwable error) {
                    return CompletableFuture.failedFuture(error);
                }
            });
            outboundSendTails.put(socket, current);
            current.whenComplete((ignored, error) -> {
                synchronized (outboundSendLock) {
                    if (outboundSendTails.get(socket) == current) {
                        outboundSendTails.remove(socket);
                    }
                }
            });
            return current;
        }
    }

    private ScheduledFuture<?> scheduleImageTranslationTimeout(String requestId) {
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown()) {
            return null;
        }
        return executor.schedule(
                () -> failImageTranslation(requestId, "image_translation_timeout"),
                SCREENSHOT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
    }

    private void failImageTranslation(String requestId, String error) {
        PendingImageTranslation pending = pendingImageTranslations.remove(requestId);
        if (pending == null) {
            return;
        }
        pending.cancelTimeout();
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        currentServer.execute(() -> {
            ServerPlayer player = currentServer.getPlayerList().getPlayer(pending.playerUuid);
            if (player != null) {
                sendImageTranslationFailure(player, pending.requestId, error);
            }
        });
    }

    private void clearPendingImageTranslations(String error) {
        for (PendingImageTranslation pending : pendingImageTranslations.values()) {
            pending.cancelTimeout();
            MinecraftServer currentServer = server;
            if (currentServer != null) {
                currentServer.execute(() -> {
                    ServerPlayer player = currentServer.getPlayerList().getPlayer(pending.playerUuid);
                    if (player != null) {
                        sendImageTranslationFailure(player, pending.requestId, error);
                    }
                });
            }
        }
        pendingImageTranslations.clear();
    }

    public void handleSendFailure(WebSocket socket, Throwable throwable) {
        MineAstr.LOGGER.warn("MineAstr 发送 WebSocket 数据失败：{}", throwable.getMessage());
        abortActiveSocket(socket, "WebSocket 发送失败。", true);
    }

    private void abortActiveSocket(WebSocket socket, String error, boolean reconnect) {
        boolean activeSocketFailed = webSocket.compareAndSet(socket, null);
        try {
            socket.abort();
        } catch (RuntimeException ignored) {
        }
        if (activeSocketFailed) {
            disableNativeChatPolicy(socket);
            clearPendingNativeChats(socket, error);
            clearPendingScreenshots(error);
            clearPendingLoginChecks(error);
            commands.clear();
                    if (reconnect) {
                scheduleReconnect();
            }
        }
    }

    public record LoginCheckResult(boolean allowed, String message, String messageKey, String code) {
        public Component component() {
            var component = messageKey == null || messageKey.isBlank()
                    ? Component.literal(message == null ? "" : message)
                    : Component.translatableWithFallback(messageKey, message == null ? "" : message);
            if (code != null && !code.isBlank()) {
                String fallback = MineAstrConfig.DEFAULT_LOGIN_CODE_MESSAGE.replace("{code}", code);
                component.append(Component.translatableWithFallback(
                        "disconnect.mineastr.login.binding_code",
                        fallback,
                        code,
                        code));
            }
            return component;
        }
    }

    public record SignTranslationAdminView(
            BlockPos pos,
            boolean front,
            String source,
            SignTranslationStore.Entry cacheEntry) {
    }

    private static final class PendingImageTranslation {
        private final WebSocket socket;
        private final String requestId;
        private final UUID playerUuid;
        private volatile ScheduledFuture<?> timeout;

        private PendingImageTranslation(
                WebSocket socket,
                String requestId,
                UUID playerUuid) {
            this.socket = socket;
            this.requestId = requestId;
            this.playerUuid = playerUuid;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeout;
            if (task != null) {
                task.cancel(false);
                timeout = null;
            }
        }
    }

    record TranslationPreference(boolean translationsEnabled, boolean showOriginal) {
    }

    private record BotImagePreference(boolean enabled, boolean chatImageAvailable) {
    }

    private record BotImage(
            String name,
            String mimeType,
            String sourceUrl,
            String sha256,
            byte[] bytes) {
    }

    @Override
    public boolean supportsScreenshot(UUID player) {
        return screenshots.supports(player);
    }

    @Override
    public MinecraftServer server() {
        return server;
    }

    @Override
    public WebSocket socket() {
        return webSocket.get();
    }

    @Override
    public long generation() {
        return connectionGeneration.get();
    }

    @Override
    public boolean stopping() {
        return stopping;
    }

    @Override
    public ScheduledExecutorService scheduler() {
        return reconnectExecutor;
    }

    @Override
    public MineAstrServerWorkQueue work() {
        return serverWork;
    }

    @Override
    public TranslationPreference translationPreference(UUID player) {
        return translationPreferences.get(player);
    }


    long currentSignTranslationCacheRevision() {
        return signs.currentSignTranslationCacheRevision();
    }

    boolean isCurrentSignTranslationCacheRevision(long revision) {
        return signs.isCurrentSignTranslationCacheRevision(revision);
    }

    private void handleCommandQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        commands.handleCommandQuery(socket, messageId, payload, currentServer);
    }

    private void handleTrustedUsersQuery(
            WebSocket socket, String messageId, JsonObject payload) {
        commands.handleTrustedUsersQuery(socket, messageId, payload);
    }

    public void registerClientCapability(ServerPlayer player, boolean screenshotSupported, String clientModVersion) {
        screenshots.registerClientCapability(player, screenshotSupported, clientModVersion);
    }

    private void handleScreenshotQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        screenshots.handleScreenshotQuery(socket, messageId, payload, currentServer);
    }

    public void receiveScreenshotChunk(ServerPlayer player, MineAstrPayloads.ScreenshotChunk chunk) {
        screenshots.receiveScreenshotChunk(player, chunk);
    }

    public void receiveScreenshotError(ServerPlayer player, String code, String message, String requestId) {
        screenshots.receiveScreenshotError(player, code, message, requestId);
    }

    private void clearScreenshotState(String error) {
        screenshots.clearScreenshotState(error);
    }

    private void clearPendingScreenshots(String error) {
        screenshots.clearPendingScreenshots(error);
    }

    public CompletableFuture<LoginCheckResult> checkPlayerLogin(String playerName) {
        return bindings.checkPlayerLogin(playerName);
    }

    private void handleEventResult(WebSocket socket, JsonObject payload) {
        bindings.handleEventResult(socket, payload);
    }

    private void handleBindingQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        bindings.handleBindingQuery(socket, messageId, payload, currentServer);
    }

    /**
     * Reconciles a bound player against the exact identity vanilla is about to
     * check. This avoids guessing UUIDs for offline mode, proxies and Floodgate.
     */
    public void reconcileLoginWhitelistIdentity(GameProfile profile) {
        bindings.reconcileLoginWhitelistIdentity(profile);
    }

    private void clearPendingLoginChecks(String error) {
        bindings.clearPendingLoginChecks(error);
    }

    /** Returns cache details for the sign side currently under the player's crosshair. */
    public SignTranslationAdminView inspectTargetedSignTranslation(ServerPlayer player) {
        return signs.inspectTargetedSignTranslation(player);
    }

    /** Stores a locale-specific administrator override for the targeted sign side. */
    public boolean setTargetedSignTranslation(
            ServerPlayer player,
            String language,
            String translation) {
        return signs.setTargetedSignTranslation(player, language, translation);
    }

    /**
     * Clears either one locale or the whole current-side entry.
     * Returns -1 when no sign is targeted, 0 when nothing matched, and 1 when removed.
     */
    public int clearTargetedSignTranslation(ServerPlayer player, String language) {
        return signs.clearTargetedSignTranslation(player, language);
    }

    public int clearAllSignTranslations() {
        return signs.clearAllSignTranslations();
    }

    public int signTranslationCacheSize() {
        return signs.signTranslationCacheSize();
    }

    public void handleSignTranslationQuery(
            ServerPlayer player,
            MineAstrPayloads.SignTranslationQuery query) {
        signs.handleSignTranslationQuery(player, query);
    }

    public void translateSign(ServerPlayer player, SignBlockEntity sign, boolean front) {
        signs.translateSign(player, sign, front);
    }

    private void handleSignTranslationResult(WebSocket socket, JsonObject payload) {
        signs.handleSignTranslationResult(socket, payload);
    }

    /**
     * Queues a per-player native-chat translation request.
     *
     * <p>The caller may cancel the NeoForge chat event only when this method
     * returns {@code true}. Until AstrBot explicitly enables the policy for
     * the active WebSocket, normal signed vanilla chat remains untouched.</p>
     */
    public boolean queueNativeChatTranslation(ServerPlayer player, Component decoratedMessage) {
        return nativeChat.queueNativeChatTranslation(player, decoratedMessage);
    }

    private void handleNativeChatPolicy(WebSocket socket, JsonObject payload) {
        nativeChat.handleNativeChatPolicy(socket, payload);
    }

    private void handleNativeChatTranslationResult(WebSocket socket, JsonObject payload) {
        nativeChat.handleNativeChatTranslationResult(socket, payload);
    }

    private void clearPendingNativeChats(WebSocket expectedSocket, String reason) {
        nativeChat.clearPendingNativeChats(expectedSocket, reason);
    }

    private void disableNativeChatPolicy(WebSocket expectedSocket) {
        nativeChat.disableNativeChatPolicy(expectedSocket);
    }

    static String sanitizeBotContent(String content) {
        return MineAstrProtocolValues.sanitizeBotContent(content);
    }

    static String selectTranslation(Map<String, String> translations, String language) {
        return MineAstrChatText.selectTranslation(translations, language);
    }

    static List<String> nativeChatTargetLanguages(Iterable<String> rawLanguages) {
        return MineAstrChatText.nativeChatTargetLanguages(rawLanguages);
    }

    static String normalizeNativeChatLanguage(String rawLanguage) {
        return MineAstrChatText.normalizeNativeChatLanguage(rawLanguage);
    }

    static String trimNativeChatPacketText(String value) {
        return MineAstrChatText.trimNativeChatPacketText(value);
    }

    static String normalizeChatPlatform(String platform) {
        return MineAstrChatText.normalizeChatPlatform(platform);
    }
}
