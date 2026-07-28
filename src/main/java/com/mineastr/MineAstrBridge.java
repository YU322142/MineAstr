package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.management.ManagementFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;

public final class MineAstrBridge implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_INBOUND_WS_CHARS = 2 * 1024 * 1024;
    private static final int MAX_LOG_MESSAGE_CHARS = 200;
    private static final int MAX_BROADCAST_CONTENT_LENGTH = 2000;
    private static final int MAX_BROADCAST_SENDER_LENGTH = 64;
    private static final int SCREENSHOT_TIMEOUT_SECONDS = 30;
    private static final int SCREENSHOT_MAX_CHUNKS = 64;
    private static final int MAX_EVENT_TEXT_LENGTH = 512;
    private static final Random VERIFY_CODE_RANDOM = new java.security.SecureRandom();

    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final StringBuilder inboundBuffer = new StringBuilder();
    private final ConcurrentMap<UUID, ClientCapability> clientCapabilities = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, TranslationPreference> translationPreferences = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingScreenshot> pendingScreenshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> pendingScreenshotByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScreenshotAssembly> screenshotAssemblies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingLoginCheck> pendingLoginChecks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SyncedBinding> syncedBindings = new ConcurrentHashMap<>();

    private volatile MinecraftServer server;
    private volatile ScheduledExecutorService reconnectExecutor = createReconnectExecutor();
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean stopping;
    private volatile long startedAtMs;

    public void start(MinecraftServer server) {
        this.server = server;
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
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        cancelReconnect();
        clearScreenshotState("Minecraft 服务器正在停止。");
        clearPendingLoginChecks("Minecraft 服务器正在停止。");
        translationPreferences.clear();
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
        payload.addProperty("player_name", player.getGameProfile().name());
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
        payload.addProperty("player_name", player.getGameProfile().name());

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
        payload.addProperty("player_name", player.getGameProfile().name());
        if (deathMessage != null && !deathMessage.isBlank()) {
            payload.addProperty("death_message", deathMessage);
            payload.addProperty("reason", deathMessage);
        }
        sendJson(socket, payload);
    }

    public CompletableFuture<LoginCheckResult> checkPlayerLogin(String playerName) {
        if (!MineAstrConfig.LOGIN_BINDING_CHECK_ENABLED.getAsBoolean()) {
            return CompletableFuture.completedFuture(new LoginCheckResult(true, "", "", ""));
        }
        WebSocket socket = webSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            return CompletableFuture.completedFuture(loginCheckFallback("AstrBot 未连接"));
        }

        String messageId = UUID.randomUUID().toString();
        CompletableFuture<LoginCheckResult> future = new CompletableFuture<>();
        String normalizedName = trimFlatContent(playerName, 64);
        PendingLoginCheck pending = new PendingLoginCheck(socket, normalizedName, future);
        pendingLoginChecks.put(messageId, pending);

        JsonObject payload = eventEnvelope("player_login_check");
        payload.addProperty("message_id", messageId);
        payload.addProperty("player_name", normalizedName);
        sendJson(socket, payload);

        int timeoutSeconds = MineAstrConfig.LOGIN_CHECK_TIMEOUT_SECONDS.getAsInt();
        return future
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(error -> loginCheckFallback("等待 AstrBot 登录校验超时"))
                .whenComplete((result, error) -> pendingLoginChecks.remove(messageId, pending));
    }

    private LoginCheckResult loginCheckFallback(String reason) {
        boolean allowed = MineAstrConfig.LOGIN_CHECK_FAIL_OPEN.getAsBoolean();
        MineAstr.LOGGER.warn("MineAstr 登录绑定校验失败：{}；策略={}", reason, allowed ? "fail-open" : "fail-closed");
        return new LoginCheckResult(
                allowed,
                allowed ? "" : "[MC] 无法连接 AstrBot 完成账号绑定校验，请稍后重试。",
                allowed ? "" : "disconnect.mineastr.login.unavailable",
                "");
    }

    public void registerClientCapability(ServerPlayer player, boolean screenshotSupported, String clientModVersion) {
        if (!screenshotSupported) {
            clientCapabilities.remove(player.getUUID());
            return;
        }
        clientCapabilities.put(player.getUUID(), new ClientCapability(clientModVersion, System.currentTimeMillis()));
        MineAstr.LOGGER.debug("MineAstr 已记录客户端能力：{} {}", player.getGameProfile().name(), clientModVersion);
    }

    public void unregisterClientCapability(ServerPlayer player) {
        clientCapabilities.remove(player.getUUID());
        translationPreferences.remove(player.getUUID());
        pendingScreenshots.values().removeIf(pending -> {
            if (!pending.playerUuid.equals(player.getUUID())) {
                return false;
            }
            pending.cancelTimeout();
            pendingScreenshotByPlayer.remove(pending.playerUuid, pending.messageId);
            sendQueryError(pending.socket, pending.messageId, "screenshot", "目标玩家已离开服务器。");
            screenshotAssemblies.remove(pending.messageId);
            return true;
        });
    }

    public void registerTranslationPreference(
            ServerPlayer player, boolean translationsEnabled, boolean showOriginal) {
        translationPreferences.put(
                player.getUUID(),
                new TranslationPreference(translationsEnabled, showOriginal));
        MineAstr.LOGGER.debug(
                "MineAstr 已记录玩家 {} 的翻译显示偏好：enabled={} show_original={}",
                player.getGameProfile().name(),
                translationsEnabled,
                showOriginal);
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
        httpClient
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + MineAstrConfig.TOKEN.get())
                .buildAsync(uri, this)
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

    private void sendHello(WebSocket socket) {
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
        payload.add("query_capabilities", capabilities);
        JsonArray eventCapabilities = new JsonArray();
        eventCapabilities.add("player_join");
        eventCapabilities.add("player_leave");
        eventCapabilities.add("player_death");
        eventCapabilities.add("binding_code");
        eventCapabilities.add("player_login_check");
        payload.add("event_capabilities", eventCapabilities);
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
            clearPendingScreenshots("AstrBot WebSocket 已断开。");
            clearPendingLoginChecks("AstrBot WebSocket 已断开。");
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
            clearPendingScreenshots("AstrBot WebSocket 出错。");
            clearPendingLoginChecks("AstrBot WebSocket 出错。");
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

    private void handleEventResult(WebSocket socket, JsonObject payload) {
        String event = trimFlatContent(getString(payload, "event", ""), 64).toLowerCase(Locale.ROOT);
        if (!"player_login_check".equals(event)) {
            MineAstr.LOGGER.debug("MineAstr 已忽略未知事件响应：{}", event);
            return;
        }
        String messageId = trimFlatContent(getString(payload, "message_id", ""), 64);
        PendingLoginCheck pending = pendingLoginChecks.get(messageId);
        if (pending == null || pending.socket != socket) {
            MineAstr.LOGGER.warn("MineAstr 已忽略未知或来源不匹配的登录校验响应：{}", messageId);
            return;
        }
        if (!getBoolean(payload, "ok", false)) {
            pending.future.complete(loginCheckFallback("AstrBot 返回登录校验错误"));
            return;
        }

        boolean allowed = getBoolean(payload, "allowed", true);
        String message = trimContent(getString(payload, "message", ""), 1024);
        String messageKey = trimFlatContent(getString(payload, "message_key", ""), 128);
        if (!allowed && messageKey.isBlank() && isKnownUnboundMessage(message)) {
            messageKey = "disconnect.mineastr.login.not_bound";
        }
        String localizedCode = "";
        if (!allowed && MineAstrConfig.GENERATE_BINDING_CODE_ON_REJECT.getAsBoolean()) {
            String code = generateVerifyCode();
            JsonObject codeEvent = eventEnvelope("binding_code");
            codeEvent.addProperty("player_name", pending.playerName);
            codeEvent.addProperty("code", code);
            sendJson(socket, codeEvent);
            String codeMessage = MineAstrConfig.LOGIN_CODE_MESSAGE.get().replace("{code}", code);
            message = message.isBlank() ? "[MC] 该账号尚未绑定。" : message;
            if (MineAstrConfig.DEFAULT_LOGIN_CODE_MESSAGE.equals(MineAstrConfig.LOGIN_CODE_MESSAGE.get())) {
                localizedCode = code;
            } else {
                message += codeMessage;
            }
        }
        pending.future.complete(new LoginCheckResult(allowed, message, messageKey, localizedCode));
    }

    private static boolean isKnownUnboundMessage(String message) {
        return "[MC] 该游戏账号尚未在聊天平台绑定，请先使用 /mc bind <游戏名>。".equals(message)
                || "[MineAstr] 该游戏账号尚未在聊天平台绑定，请先使用 /mc bind <游戏名>。".equals(message)
                || "[MC] This game account is not bound. Use /mc bind <player name> on QQ/Discord first.".equals(message);
    }

    private static String generateVerifyCode() {
        int length = MineAstrConfig.VERIFY_CODE_LENGTH.getAsInt();
        StringBuilder code = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            code.append(VERIFY_CODE_RANDOM.nextInt(10));
        }
        return code.toString();
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
        String content = trimFlatContent(getString(payload, "content", ""), MAX_BROADCAST_CONTENT_LENGTH);
        if (senderName.isEmpty()) {
            senderName = trimFlatContent(MineAstrConfig.BOT_DISPLAY_NAME.get(), MAX_BROADCAST_SENDER_LENGTH);
        }
        if (content.isBlank()) {
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
                String translated = trimFlatContent(entry.getValue().getAsString(), MAX_BROADCAST_CONTENT_LENGTH);
                if (!translated.isBlank()) {
                    translations.addProperty(language, translated);
                }
            }
        }
        boolean defaultShowOriginal = getBoolean(payload, "show_original", false);
        String finalSenderName = senderName;
        currentServer.execute(() -> {
            MineAstr.LOGGER.info("[{}] {}", finalSenderName, content);
            for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
                player.sendSystemMessage(renderTranslatedChat(
                        player,
                        finalSenderName,
                        content,
                        translations,
                        defaultShowOriginal));
            }
        });
    }

    private Component renderTranslatedChat(
            ServerPlayer player,
            String senderName,
            String original,
            JsonObject translations,
            boolean defaultShowOriginal) {
        TranslationPreference preference = translationPreferences.get(player.getUUID());
        if (preference != null && !preference.translationsEnabled) {
            return Component.literal("[" + senderName + "] " + original);
        }
        String language = player.clientInformation().language().strip().replace('-', '_').toLowerCase(Locale.ROOT);
        String translated = selectTranslation(translations, language);
        if (translated.isBlank() || translated.equals(original)) {
            return Component.literal("[" + senderName + "] " + original);
        }
        var component = Component.literal("[" + senderName + "] " + translated);
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

    private static String selectTranslation(JsonObject translations, String language) {
        if (translations.has(language) && translations.get(language).isJsonPrimitive()) {
            return translations.get(language).getAsString();
        }
        int separator = language.indexOf('_');
        String family = separator > 0 ? language.substring(0, separator) : language;
        for (var entry : translations.entrySet()) {
            String candidate = entry.getKey();
            if ((candidate.equals(family) || candidate.startsWith(family + "_"))
                    && entry.getValue().isJsonPrimitive()) {
                return entry.getValue().getAsString();
            }
        }
        return "";
    }

    private void handleQuery(WebSocket socket, JsonObject payload) {
        String query = trimFlatContent(getString(payload, "query", ""), 64).toLowerCase(Locale.ROOT);
        String messageId = trimFlatContent(getString(payload, "message_id", UUID.randomUUID().toString()), 64);
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            sendQueryError(socket, messageId, query, "Minecraft 服务器尚未启动。");
            return;
        }
        currentServer.execute(() -> {
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
            level = player.level();
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
        int y = getInt(payload, "y", fallbackCenter.getY(), level.getMinY(), level.getMaxY() - 1);
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
        sendQueryResult(socket, messageId, "region_features", MineAstrTools.analyzeRegion(level, center, horizontalRadius, verticalRadius));
    }

    private void handleCommandQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_COMMAND_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "command", "服务端命令工具默认关闭；请由服务器管理员在配置中显式启用。");
            return;
        }
        String bridgeToken = MineAstrConfig.TOKEN.get().strip();
        if (bridgeToken.isEmpty() || "change-me".equalsIgnoreCase(bridgeToken)) {
            sendQueryError(socket, messageId, "command", "命令工具要求先把默认 token 改为安全随机字符串。");
            return;
        }
        Requester requester = Requester.from(payload);
        if (!isTrustedRequester(requester)) {
            MineAstr.LOGGER.warn("MineAstr 已拒绝不可信命令请求：requester={} command={}", requester.auditName(), shortenForLog(getString(payload, "command", "")));
            sendQueryError(socket, messageId, "command", "当前请求者不在 trustedCommandUsers 可信名单中。");
            return;
        }
        String command = normalizeCommand(getString(payload, "command", ""));
        if (command.isEmpty()) {
            sendQueryError(socket, messageId, "command", "命令不能为空。");
            return;
        }
        if (command.length() > MineAstrConfig.COMMAND_MAX_LENGTH.getAsInt()) {
            sendQueryError(socket, messageId, "command", "命令长度超过服务端限制。");
            return;
        }
        if (!isAllowedCommand(command)) {
            MineAstr.LOGGER.warn("MineAstr 已拒绝白名单外命令：requester={} command={}", requester.auditName(), command);
            sendQueryError(socket, messageId, "command", "命令未命中 allowedCommandRules 白名单。");
            return;
        }

        CommandCapture capture = new CommandCapture();
        CommandSourceStack source = currentServer.createCommandSourceStack()
                .withSource(capture)
                .withPermission(LevelBasedPermissionSet.forLevel(
                        PermissionLevel.byId(MineAstrConfig.COMMAND_PERMISSION_LEVEL.getAsInt())))
                .withCallback(capture::onResult);
        MineAstr.LOGGER.warn("MineAstr 正在执行受控 LLM 命令：requester={} command={}", requester.auditName(), command);
        currentServer.getCommands().performPrefixedCommand(source, command);

        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        data.addProperty("requester", requester.auditName());
        data.addProperty("success", capture.success);
        data.addProperty("result", capture.result);
        JsonArray output = new JsonArray();
        capture.messages.forEach(output::add);
        data.add("output", output);
        sendQueryResult(socket, messageId, "command", data);
    }

    private void handleScreenshotQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "screenshot", "未找到要截图的在线玩家。");
            return;
        }
        if (!clientCapabilities.containsKey(player.getUUID())) {
            sendQueryError(socket, messageId, "screenshot", "目标玩家未安装 MineAstr 客户端 Mod，或客户端尚未声明支持截图。");
            return;
        }
        String existingRequestId = pendingScreenshotByPlayer.putIfAbsent(player.getUUID(), messageId);
        if (existingRequestId != null) {
            sendQueryError(socket, messageId, "screenshot", "目标玩家已有一个截图请求正在处理中。");
            return;
        }
        if (pendingScreenshots.containsKey(messageId)) {
            pendingScreenshotByPlayer.remove(player.getUUID(), messageId);
            sendQueryError(socket, messageId, "screenshot", "同一个截图请求正在处理中。");
            return;
        }

        String reason = trimContent(getString(payload, "reason", "AstrBot 请求查看当前 Minecraft 画面。"), MineAstrPayloads.MAX_REASON_LENGTH);
        int maxWidth = getInt(payload, "max_width", 240, 64, 1024);
        int maxHeight = getInt(payload, "max_height", 135, 36, 1024);
        int maxBytes = getInt(payload, "max_bytes", 131072, 8192, 524288);
        String format = getString(payload, "format", "jpeg");
        if (!"jpeg".equalsIgnoreCase(format) && !"jpg".equalsIgnoreCase(format)) {
            format = "jpeg";
        }

        PendingScreenshot pending = new PendingScreenshot(socket, messageId, player.getUUID(), player.getGameProfile().name(), maxBytes);
        PendingScreenshot previous = pendingScreenshots.putIfAbsent(messageId, pending);
        if (previous != null) {
            pendingScreenshotByPlayer.remove(player.getUUID(), messageId);
            sendQueryError(socket, messageId, "screenshot", "同一个截图请求正在处理中。");
            return;
        }
        pending.timeout = scheduleScreenshotTimeout(messageId);
        try {
            if (!MineAstrNetwork.canSendScreenshotRequest(player)) {
                failScreenshot(messageId, "目标客户端当前不能接收 MineAstr 截图请求。");
                return;
            }
            MineAstrNetwork.sendScreenshotRequest(player, new MineAstrPayloads.ScreenshotRequest(
                    messageId,
                    reason,
                    maxWidth,
                    maxHeight,
                    maxBytes,
                    format));
        } catch (RuntimeException exc) {
            failScreenshot(messageId, "向玩家客户端发送截图请求失败：" + exc.getMessage());
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
        MineAstr.LOGGER.info("MineAstr 已向玩家 {} 发送来自 {} 的定向提醒。", player.getGameProfile().name(), sender);

        JsonObject data = new JsonObject();
        data.addProperty("player_uuid", player.getUUID().toString());
        data.addProperty("player_name", player.getGameProfile().name());
        data.addProperty("notified", true);
        sendQueryResult(socket, messageId, "notify_player", data);
    }

    private void handleBindingQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_BINDING_SYNC.getAsBoolean()) {
            sendQueryError(socket, messageId, "binding", "binding_sync_disabled");
            return;
        }
        String action = trimFlatContent(getString(payload, "action", ""), 16).toLowerCase(Locale.ROOT);
        String playerName = trimFlatContent(getString(payload, "player_name", ""), 64);
        String ownerKey = trimFlatContent(getString(payload, "owner_key", ""), 256);
        String ownerDisplay = trimFlatContent(getString(payload, "owner_display", ""), 128);
        if ("reset".equals(action)) {
            boolean whitelistChanged = false;
            if (MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) {
                for (SyncedBinding binding : List.copyOf(syncedBindings.values())) {
                    WhitelistSyncResult result = updateWhitelist(
                            currentServer, binding.identity, false);
                    if (!result.ok) {
                        sendQueryError(socket, messageId, "binding",
                                "whitelist_reset_failed:" + binding.playerName + ":" + result.error);
                        return;
                    }
                    whitelistChanged |= result.changed;
                }
            }
            syncedBindings.clear();
            MineAstr.LOGGER.warn("MineAstr 绑定同步：action=reset whitelist={}", whitelistChanged);
            JsonObject data = new JsonObject();
            data.addProperty("action", action);
            data.addProperty("cache_size", 0);
            data.addProperty("whitelist_changed", whitelistChanged);
            sendQueryResult(socket, messageId, "binding", data);
            return;
        }
        if (!("bind".equals(action) || "unbind".equals(action))) {
            sendQueryError(socket, messageId, "binding", "invalid_action");
            return;
        }
        if (!isSafeBindingPlayerName(playerName) || ownerKey.isBlank()) {
            sendQueryError(socket, messageId, "binding", "invalid_binding_identity");
            return;
        }

        String normalizedPlayer = playerName.toLowerCase(Locale.ROOT);
        SyncedBinding existing = syncedBindings.get(normalizedPlayer);
        if ("unbind".equals(action) && existing != null && !existing.ownerKey.equals(ownerKey)) {
            sendQueryError(socket, messageId, "binding", "binding_owner_mismatch");
            return;
        }
        NameAndId identity;
        if (existing != null) {
            identity = existing.identity;
        } else {
            try {
                identity = currentServer.services().nameToIdCache().get(playerName).orElse(null);
            } catch (RuntimeException exc) {
                MineAstr.LOGGER.warn("MineAstr 无法解析玩家身份：player={}", playerName, exc);
                sendQueryError(socket, messageId, "binding", "player_identity_lookup_failed");
                return;
            }
        }
        if (identity == null) {
            MineAstr.LOGGER.warn("MineAstr 无法解析玩家身份：player={} online_mode={}",
                    playerName, currentServer.usesAuthentication());
            sendQueryError(socket, messageId, "binding", "player_identity_not_found");
            return;
        }

        WhitelistSyncResult whitelistResult = WhitelistSyncResult.skipped();
        if (MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) {
            whitelistResult = updateWhitelist(currentServer, identity, "bind".equals(action));
            if (!whitelistResult.ok) {
                MineAstr.LOGGER.warn(
                        "MineAstr 原版白名单同步失败：action={} player={} uuid={} error={}",
                        action, identity.name(), identity.id(), whitelistResult.error);
                sendQueryError(socket, messageId, "binding", whitelistResult.error);
                return;
            }
        }

        if ("bind".equals(action)) {
            syncedBindings.put(normalizedPlayer, new SyncedBinding(playerName, ownerKey, ownerDisplay, identity));
        } else {
            syncedBindings.remove(normalizedPlayer);
        }

        MineAstr.LOGGER.info(
                "MineAstr 绑定同步成功：action={} player={} uuid={} owner={} whitelist_changed={} whitelist_verified={}",
                action, identity.name(), identity.id(), ownerKey, whitelistResult.changed, whitelistResult.verified);

        JsonObject data = new JsonObject();
        data.addProperty("action", action);
        data.addProperty("player_name", playerName);
        data.addProperty("owner_key", ownerKey);
        data.addProperty("cache_size", syncedBindings.size());
        data.addProperty("player_uuid", identity.id().toString());
        data.addProperty("whitelist_changed", whitelistResult.changed);
        data.addProperty("whitelist_verified", whitelistResult.verified);
        sendQueryResult(socket, messageId, "binding", data);
    }

    private static WhitelistSyncResult updateWhitelist(
            MinecraftServer currentServer, NameAndId identity, boolean shouldBePresent) {
        UserWhiteList whitelist = currentServer.getPlayerList().getWhiteList();
        boolean wasPresent = whitelist.isWhiteListed(identity);
        if (shouldBePresent && !wasPresent) {
            whitelist.add(new UserWhiteListEntry(identity));
        } else if (!shouldBePresent && wasPresent) {
            whitelist.remove(identity);
        }

        boolean isPresent = whitelist.isWhiteListed(identity);
        if (isPresent != shouldBePresent) {
            return new WhitelistSyncResult(
                    false, false, false, "whitelist_verification_failed");
        }
        try {
            // StoredUserList already saves during add/remove, but explicitly saving
            // here lets the bridge report an I/O failure instead of claiming success.
            whitelist.save();
        } catch (IOException exc) {
            MineAstr.LOGGER.warn("MineAstr 保存原版白名单失败：{} {}", identity.name(), identity.id(), exc);
            return new WhitelistSyncResult(
                    false, wasPresent != isPresent, true, "whitelist_save_failed");
        }
        return new WhitelistSyncResult(true, wasPresent != isPresent, true, "");
    }

    private static boolean isSafeBindingPlayerName(String playerName) {
        if (playerName == null || playerName.isEmpty() || playerName.length() > 64) {
            return false;
        }
        return playerName.codePoints().noneMatch(Character::isISOControl);
    }

    private JsonObject buildStatusData(MinecraftServer currentServer) {
        PlayerList playerList = currentServer.getPlayerList();
        JsonObject data = new JsonObject();
        data.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        data.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        data.addProperty("mod_version", MineAstr.MOD_VERSION);
        data.addProperty("minecraft_version", SharedConstants.getCurrentVersion().name());
        data.addProperty("dedicated", currentServer.isDedicatedServer());
        data.addProperty("player_count", playerList.getPlayerCount());
        data.addProperty("max_players", playerList.getMaxPlayers());
        data.addProperty("uptime_ms", Math.max(0L, System.currentTimeMillis() - startedAtMs));
        BlockPos spawn = currentServer.overworld().getRespawnData().pos();
        JsonObject spawnData = new JsonObject();
        spawnData.addProperty("dimension", Level.OVERWORLD.identifier().toString());
        spawnData.addProperty("x", spawn.getX());
        spawnData.addProperty("y", spawn.getY());
        spawnData.addProperty("z", spawn.getZ());
        data.add("world_spawn", spawnData);
        JsonArray names = new JsonArray();
        JsonArray screenshotCapableNames = new JsonArray();
        for (ServerPlayer player : playerList.getPlayers()) {
            names.add(player.getGameProfile().name());
            if (clientCapabilities.containsKey(player.getUUID())) {
                screenshotCapableNames.add(player.getGameProfile().name());
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
            playerData.addProperty("name", player.getGameProfile().name());
            playerData.addProperty("display_name", player.getDisplayName().getString());
            playerData.addProperty("screenshot_supported", clientCapabilities.containsKey(player.getUUID()));
            players.add(playerData);
        }
        data.add("players", players);
        return data;
    }

    public void receiveScreenshotChunk(ServerPlayer player, MineAstrPayloads.ScreenshotChunk chunk) {
        PendingScreenshot pending = pendingScreenshots.get(chunk.requestId());
        if (pending == null || !pending.playerUuid.equals(player.getUUID())) {
            MineAstr.LOGGER.debug("MineAstr 已忽略未知截图分片：{}", chunk.requestId());
            return;
        }
        if (chunk.totalChunks() <= 0 || chunk.totalChunks() > SCREENSHOT_MAX_CHUNKS) {
            failScreenshot(chunk.requestId(), "截图分片数量无效。");
            return;
        }
        if (chunk.totalBytes() <= 0 || chunk.totalBytes() > pending.maxBytes) {
            failScreenshot(chunk.requestId(), "截图大小超过服务端允许的限制。");
            return;
        }
        if (chunk.width() <= 0 || chunk.height() <= 0) {
            failScreenshot(chunk.requestId(), "截图尺寸无效。");
            return;
        }
        if (!"image/jpeg".equalsIgnoreCase(chunk.mimeType())) {
            failScreenshot(chunk.requestId(), "截图 MIME 类型无效。");
            return;
        }
        if (chunk.bytes() == null || chunk.bytes().length == 0 || chunk.bytes().length > MineAstrPayloads.MAX_CHUNK_BYTES) {
            failScreenshot(chunk.requestId(), "截图分片内容无效。");
            return;
        }
        if (chunk.index() < 0 || chunk.index() >= chunk.totalChunks()) {
            failScreenshot(chunk.requestId(), "截图分片序号无效。");
            return;
        }

        ScreenshotAssembly assembly = screenshotAssemblies.computeIfAbsent(
                chunk.requestId(),
                id -> new ScreenshotAssembly(chunk.totalChunks(), chunk.totalBytes(), chunk.width(), chunk.height(), chunk.mimeType(), chunk.capturedAtMs()));
        byte[] imageBytes;
        synchronized (assembly) {
            if (!assembly.accept(chunk)) {
                failScreenshot(chunk.requestId(), "截图分片元数据不一致。");
                return;
            }
            if (!assembly.isComplete()) {
                return;
            }
            try {
                imageBytes = assembly.join();
            } catch (RuntimeException exc) {
                failScreenshot(chunk.requestId(), "截图分片重组失败。");
                return;
            }
        }
        if (!looksLikeJpeg(imageBytes)) {
            failScreenshot(chunk.requestId(), "截图数据不是有效的 JPEG 图片。");
            return;
        }

        PendingScreenshot completed = pendingScreenshots.remove(chunk.requestId());
        pendingScreenshotByPlayer.remove(player.getUUID(), chunk.requestId());
        screenshotAssemblies.remove(chunk.requestId());
        if (completed == null) {
            return;
        }
        completed.cancelTimeout();

        JsonObject data = new JsonObject();
        data.addProperty("player_uuid", player.getUUID().toString());
        data.addProperty("player_name", player.getGameProfile().name());
        data.addProperty("status", "ok");
        data.addProperty("mime_type", assembly.mimeType);
        data.addProperty("width", assembly.width);
        data.addProperty("height", assembly.height);
        data.addProperty("bytes", imageBytes.length);
        data.addProperty("image_base64", Base64.getEncoder().encodeToString(imageBytes));
        data.addProperty("captured_at_ms", assembly.capturedAtMs);
        sendQueryResult(completed.socket, completed.messageId, "screenshot", data);
    }

    public void receiveScreenshotError(ServerPlayer player, String code, String message, String requestId) {
        PendingScreenshot pending = pendingScreenshots.get(requestId);
        if (pending == null || !pending.playerUuid.equals(player.getUUID())) {
            return;
        }
        PendingScreenshot removed = pendingScreenshots.remove(requestId);
        pendingScreenshotByPlayer.remove(player.getUUID(), requestId);
        screenshotAssemblies.remove(requestId);
        if (removed != null) {
            removed.cancelTimeout();
            sendQueryError(removed.socket, removed.messageId, "screenshot", screenshotErrorMessage(code, message));
        }
    }

    private void sendQueryResult(WebSocket socket, String messageId, String query, JsonObject data) {
        JsonObject payload = queryEnvelope(messageId, query, true);
        payload.add("data", data);
        sendJson(socket, payload);
    }

    private void sendQueryError(WebSocket socket, String messageId, String query, String error) {
        JsonObject payload = queryEnvelope(messageId, query, false);
        payload.addProperty("error", error);
        sendJson(socket, payload);
    }

    private JsonObject queryEnvelope(String messageId, String query, boolean ok) {
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

    private JsonObject eventEnvelope(String event) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "event");
        payload.addProperty("event", event);
        payload.addProperty("message_id", UUID.randomUUID().toString());
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        return payload;
    }

    private void sendJson(WebSocket socket, JsonObject payload) {
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        try {
            socket.sendText(GSON.toJson(payload), true).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    handleSendFailure(socket, throwable);
                }
            });
        } catch (RuntimeException exc) {
            handleSendFailure(socket, exc);
        }
    }

    private ScheduledFuture<?> scheduleScreenshotTimeout(String requestId) {
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown()) {
            return null;
        }
        return executor.schedule(() -> failScreenshot(requestId, "等待玩家客户端截图超时。"), SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void failScreenshot(String requestId, String error) {
        PendingScreenshot pending = pendingScreenshots.remove(requestId);
        screenshotAssemblies.remove(requestId);
        if (pending == null) {
            return;
        }
        pendingScreenshotByPlayer.remove(pending.playerUuid, requestId);
        pending.cancelTimeout();
        sendQueryError(pending.socket, pending.messageId, "screenshot", error);
    }

    private void clearScreenshotState(String error) {
        clearPendingScreenshots(error);
        clientCapabilities.clear();
    }

    private void clearPendingScreenshots(String error) {
        for (PendingScreenshot pending : pendingScreenshots.values()) {
            pending.cancelTimeout();
            sendQueryError(pending.socket, pending.messageId, "screenshot", error);
        }
        pendingScreenshots.clear();
        pendingScreenshotByPlayer.clear();
        screenshotAssemblies.clear();
    }

    private void clearPendingLoginChecks(String error) {
        if (pendingLoginChecks.isEmpty()) {
            return;
        }
        LoginCheckResult fallback = loginCheckFallback(error);
        pendingLoginChecks.values().forEach(pending -> pending.future.complete(fallback));
        pendingLoginChecks.clear();
    }

    private ServerPlayer findExactPlayer(MinecraftServer currentServer, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (player.getGameProfile().name().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }

    private ServerPlayer findTargetPlayer(MinecraftServer currentServer, JsonObject payload) {
        String playerUuid = getString(payload, "player_uuid", "").strip();
        String playerName = getString(payload, "player_name", "").strip();
        PlayerList playerList = currentServer.getPlayerList();
        if (!playerUuid.isEmpty()) {
            try {
                ServerPlayer player = playerList.getPlayer(UUID.fromString(playerUuid));
                if (player != null) {
                    return player;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!playerName.isEmpty()) {
            return findExactPlayer(currentServer, playerName);
        }
        if (playerUuid.isEmpty() && playerName.isEmpty() && playerList.getPlayerCount() == 1) {
            return playerList.getPlayers().getFirst();
        }
        return null;
    }

    private static ServerLevel findTargetLevel(MinecraftServer currentServer, JsonObject payload) {
        String dimensionText = trimFlatContent(getString(payload, "dimension", "minecraft:overworld"), 128);
        Identifier location = Identifier.tryParse(dimensionText);
        if (location == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return currentServer.getLevel(key);
    }

    private static boolean hasCoordinates(JsonObject payload) {
        return payload.has("x") && payload.has("y") && payload.has("z");
    }

    private static boolean isTrustedRequester(Requester requester) {
        if (requester.identities().isEmpty()) {
            return false;
        }
        for (String configured : MineAstrConfig.TRUSTED_COMMAND_USERS.get()) {
            String trusted = configured.strip().toLowerCase(Locale.ROOT);
            if (!trusted.isEmpty() && requester.identities().contains(trusted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        for (String configured : MineAstrConfig.ALLOWED_COMMAND_RULES.get()) {
            String rule = normalizeCommand(configured).toLowerCase(Locale.ROOT);
            if (rule.equals("*")) {
                return true;
            }
            if (rule.endsWith(" *")) {
                String prefix = rule.substring(0, rule.length() - 2).strip();
                if (!prefix.isEmpty() && (normalized.equals(prefix) || normalized.startsWith(prefix + " "))) {
                    return true;
                }
            } else if (normalized.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCommand(String rawCommand) {
        if (rawCommand == null) {
            return "";
        }
        String command = rawCommand.strip();
        while (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        for (int index = 0; index < command.length(); index++) {
            if (Character.isISOControl(command.charAt(index))) {
                return "";
            }
        }
        return command;
    }

    private static String screenshotErrorMessage(String code, String message) {
        String detail = message == null || message.isBlank() ? "客户端未提供详细原因。" : message;
        if ("denied".equals(code)) {
            return "玩家拒绝发送截图。";
        }
        if ("disabled".equals(code)) {
            return "客户端已在 MineAstr 配置中禁用截图发送。";
        }
        if ("not_in_game".equals(code)) {
            return "客户端尚未进入游戏，无法截图。";
        }
        return detail;
    }

    private static String getString(JsonObject payload, String key, String defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsString();
        } catch (RuntimeException exc) {
            return defaultValue;
        }
    }

    private static int getInt(JsonObject payload, String key, int defaultValue, int min, int max) {
        int value = defaultValue;
        if (payload.has(key) && !payload.get(key).isJsonNull()) {
            try {
                value = payload.get(key).getAsInt();
            } catch (RuntimeException ignored) {
                value = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double getDouble(JsonObject payload, String key, double defaultValue, double min, double max) {
        double value = defaultValue;
        if (payload.has(key) && !payload.get(key).isJsonNull()) {
            try {
                value = payload.get(key).getAsDouble();
            } catch (RuntimeException ignored) {
                value = defaultValue;
            }
        }
        if (!Double.isFinite(value)) {
            value = defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean getBoolean(JsonObject payload, String key, boolean defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static String trimContent(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replace("\r", "").strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    private static String trimFlatContent(String text, int maxLength) {
        String trimmed = trimContent(text, maxLength).replace('\n', ' ').strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    private void handleSendFailure(WebSocket socket, Throwable throwable) {
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
            clearPendingScreenshots(error);
            clearPendingLoginChecks(error);
            if (reconnect) {
                scheduleReconnect();
            }
        }
    }

    private static String shortenForLog(String message) {
        if (message == null) {
            return "";
        }
        String flattened = message.replace('\r', ' ').replace('\n', ' ');
        if (flattened.length() <= MAX_LOG_MESSAGE_CHARS) {
            return flattened;
        }
        return flattened.substring(0, MAX_LOG_MESSAGE_CHARS) + "...";
    }

    private static boolean looksLikeJpeg(byte[] imageBytes) {
        return imageBytes != null
                && imageBytes.length >= 4
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[imageBytes.length - 2] & 0xFF) == 0xFF
                && (imageBytes[imageBytes.length - 1] & 0xFF) == 0xD9;
    }

    private static String safeErrorMessage(Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return trimFlatContent(message, 256);
    }

    private record Requester(List<String> identities, String auditName) {
        private static Requester from(JsonObject payload) {
            List<String> identities = new ArrayList<>();
            String requesterId = trimFlatContent(getString(payload, "requester_id", ""), 128);
            addIdentity(identities, getString(payload, "requester_uuid", ""));
            addIdentity(identities, requesterId);
            addIdentity(identities, getString(payload, "requester_name", ""));
            String platform = trimFlatContent(getString(payload, "requester_platform", "unknown"), 64);
            if (!requesterId.isEmpty() && !platform.isEmpty() && !"unknown".equalsIgnoreCase(platform)) {
                addIdentity(identities, platform + ":" + requesterId);
            }
            String best = identities.isEmpty() ? "unknown@" + platform : identities.getFirst() + "@" + platform;
            return new Requester(List.copyOf(identities), best);
        }

        private static void addIdentity(List<String> identities, String value) {
            String normalized = trimFlatContent(value, 128).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !identities.contains(normalized)) {
                identities.add(normalized);
            }
        }
    }

    private static final class CommandCapture implements CommandSource {
        private static final int MAX_MESSAGES = 20;
        private final List<String> messages = new ArrayList<>();
        private boolean success;
        private int result;

        @Override
        public void sendSystemMessage(Component component) {
            if (messages.size() < MAX_MESSAGES) {
                messages.add(trimFlatContent(component.getString(), 512));
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private void onResult(boolean success, int result) {
            this.success = success;
            this.result = result;
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

    private record PendingLoginCheck(
            WebSocket socket,
            String playerName,
            CompletableFuture<LoginCheckResult> future) {
    }

    private record SyncedBinding(
            String playerName, String ownerKey, String ownerDisplay, NameAndId identity) {
    }

    private record WhitelistSyncResult(boolean ok, boolean changed, boolean verified, String error) {
        private static WhitelistSyncResult skipped() {
            return new WhitelistSyncResult(true, false, false, "");
        }
    }

    private record ClientCapability(String modVersion, long seenAtMs) {
    }

    private record TranslationPreference(boolean translationsEnabled, boolean showOriginal) {
    }

    private static final class PendingScreenshot {
        private final WebSocket socket;
        private final String messageId;
        private final UUID playerUuid;
        private final String playerName;
        private final int maxBytes;
        private volatile ScheduledFuture<?> timeout;

        private PendingScreenshot(WebSocket socket, String messageId, UUID playerUuid, String playerName, int maxBytes) {
            this.socket = socket;
            this.messageId = messageId;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.maxBytes = maxBytes;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeout;
            if (task != null) {
                task.cancel(false);
                timeout = null;
            }
        }
    }

    private static final class ScreenshotAssembly {
        private final int totalChunks;
        private final int totalBytes;
        private final int width;
        private final int height;
        private final String mimeType;
        private final long capturedAtMs;
        private final byte[][] chunks;
        private int received;
        private int receivedBytes;

        private ScreenshotAssembly(int totalChunks, int totalBytes, int width, int height, String mimeType, long capturedAtMs) {
            this.totalChunks = totalChunks;
            this.totalBytes = totalBytes;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType;
            this.capturedAtMs = capturedAtMs;
            this.chunks = new byte[totalChunks][];
        }

        private boolean accept(MineAstrPayloads.ScreenshotChunk chunk) {
            if (chunk.totalChunks() != totalChunks || chunk.totalBytes() != totalBytes || chunk.width() != width || chunk.height() != height) {
                return false;
            }
            if (!mimeType.equalsIgnoreCase(chunk.mimeType())) {
                return false;
            }
            if (chunk.bytes().length == 0 || chunk.bytes().length > MineAstrPayloads.MAX_CHUNK_BYTES) {
                return false;
            }
            if (chunks[chunk.index()] == null) {
                if (receivedBytes + chunk.bytes().length > totalBytes) {
                    return false;
                }
                chunks[chunk.index()] = chunk.bytes();
                received++;
                receivedBytes += chunk.bytes().length;
            }
            return true;
        }

        private boolean isComplete() {
            return received == totalChunks && receivedBytes == totalBytes;
        }

        private byte[] join() {
            byte[] output = new byte[totalBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    throw new IllegalStateException("截图分片缺失。");
                }
                System.arraycopy(chunk, 0, output, offset, chunk.length);
                offset += chunk.length;
            }
            return output;
        }
    }
}
