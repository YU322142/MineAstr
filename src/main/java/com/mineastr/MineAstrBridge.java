package com.mineastr;

import com.google.gson.JsonObject;
import java.net.http.WebSocket;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

public final class MineAstrBridge implements MineAstrWebSocket.MessageHandler {
    private static final Random VERIFY_CODE_RANDOM = new java.security.SecureRandom();

    private final ConcurrentMap<String, PendingLoginCheck> pendingLoginChecks = new ConcurrentHashMap<>();

    private volatile MinecraftServer server;
    private volatile long startedAtMs;

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public void start(MinecraftServer server) {
        this.server = server;
        this.startedAtMs = System.currentTimeMillis();
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            MineAstr.LOGGER.info("MineAstr 已被配置禁用。");
            return;
        }
        MineAstrWebSocket.connect(this);
    }

    public void stop() {
        clearPendingLoginChecks("Minecraft 服务器正在停止。");
        MineAstrScreenshots.clearAll("Minecraft 服务器正在停止。");
        MineAstrCommands.clearAll();
        MineAstrBindings.clearAll();
        MineAstrWebSocket.disconnect();
        server = null;
    }

    public boolean isConnected() {
        return MineAstrWebSocket.isConnected();
    }

    public boolean isStarted() {
        return server != null;
    }

    public boolean isConnecting() {
        return MineAstrWebSocket.isConnecting();
    }

    public boolean reconnect() {
        if (server == null || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return false;
        }
        clearPendingLoginChecks("WebSocket 重连。");
        return MineAstrWebSocket.reconnect();
    }

    public void forwardChat(ServerPlayer player, String rawText) {
        if (server == null || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return;
        }
        WebSocket socket = MineAstrWebSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            MineAstr.LOGGER.debug("MineAstr 未连接，已丢弃本条 Minecraft 聊天。");
            return;
        }
        MineAstrEvents.forwardChat(player, rawText, socket);
    }

    public void forwardPlayerJoin(ServerPlayer player) {
        WebSocket socket = MineAstrWebSocket.get();
        if (socket != null && !socket.isOutputClosed()) {
            MineAstrEvents.forwardJoin(player, socket);
        }
    }

    public void forwardPlayerLeave(ServerPlayer player) {
        WebSocket socket = MineAstrWebSocket.get();
        if (socket != null && !socket.isOutputClosed()) {
            MineAstrEvents.forwardLeave(player, socket);
        }
    }

    public void forwardPlayerDeath(ServerPlayer player, DamageSource damageSource) {
        WebSocket socket = MineAstrWebSocket.get();
        if (socket != null && !socket.isOutputClosed()) {
            MineAstrEvents.forwardDeath(player, damageSource, socket);
        }
    }

    public CompletableFuture<LoginCheckResult> checkPlayerLogin(String playerName) {
        if (!MineAstrConfig.LOGIN_BINDING_CHECK_ENABLED.getAsBoolean()) {
            return CompletableFuture.completedFuture(new LoginCheckResult(true, "", "", ""));
        }
        WebSocket socket = MineAstrWebSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            return CompletableFuture.completedFuture(loginCheckFallback("AstrBot 未连接"));
        }

        String messageId = UUID.randomUUID().toString();
        CompletableFuture<LoginCheckResult> future = new CompletableFuture<>();
        String normalizedName = MineAstrProtocol.trimFlatContent(playerName, 64);
        PendingLoginCheck pending = new PendingLoginCheck(socket, normalizedName, future);
        pendingLoginChecks.put(messageId, pending);

        JsonObject payload = MineAstrProtocol.eventEnvelope("player_login_check");
        payload.addProperty("message_id", messageId);
        payload.addProperty("player_name", normalizedName);
        MineAstrWebSocket.send(payload);

        int timeoutSeconds = MineAstrConfig.LOGIN_CHECK_TIMEOUT_SECONDS.getAsInt();
        return future
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(error -> loginCheckFallback("等待 AstrBot 登录校验超时"))
                .whenComplete((result, error) -> pendingLoginChecks.remove(messageId, pending));
    }

    @Override
    public void onMessage(WebSocket socket, JsonObject payload) {
        String type = MineAstrProtocol.getString(payload, "type", "");
        if ("chat".equals(type)) {
            if (server != null) {
                MineAstrChat.broadcast(payload, server);
            }
        } else if ("query".equals(type)) {
            if (server != null) {
                MineAstrQueryHandlers.dispatch(socket, payload, server, MineAstrWebSocket.executor());
            }
        } else if ("event_result".equals(type)) {
            handleEventResult(socket, payload);
        } else if ("pong".equals(type)) {
            MineAstr.LOGGER.debug("MineAstr 已收到 AstrBot 的 pong。");
        } else if ("error".equals(type)) {
            String error = MineAstrProtocol.getString(payload, "message", "unknown");
            MineAstr.LOGGER.warn("MineAstr 收到 AstrBot 错误：{}", error);
        } else {
            MineAstr.LOGGER.debug("MineAstr 已忽略不支持的 AstrBot 消息类型：{}", type);
        }
    }

    @Override
    public void onDisconnect() {
        clearPendingLoginChecks("AstrBot WebSocket 已断开。");
        MineAstrScreenshots.clearAll("AstrBot WebSocket 已断开。");
        MineAstrCommands.clearAll();
    }

    private void handleEventResult(WebSocket socket, JsonObject payload) {
        String event = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "event", ""), 64).toLowerCase(Locale.ROOT);
        if (!"player_login_check".equals(event)) {
            MineAstr.LOGGER.debug("MineAstr 已忽略未知事件响应：{}", event);
            return;
        }
        String messageId = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "message_id", ""), 64);
        PendingLoginCheck pending = pendingLoginChecks.get(messageId);
        if (pending == null || pending.socket != socket) {
            MineAstr.LOGGER.warn("MineAstr 已忽略未知或来源不匹配的登录校验响应：{}", messageId);
            return;
        }
        if (!MineAstrProtocol.getBoolean(payload, "ok", false)) {
            pending.future.complete(loginCheckFallback("AstrBot 返回登录校验错误"));
            return;
        }

        boolean allowed = MineAstrProtocol.getBoolean(payload, "allowed", true);
        String message = MineAstrProtocol.trimContent(MineAstrProtocol.getString(payload, "message", ""), 1024);
        String messageKey = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "message_key", ""), 128);
        if (!allowed && messageKey.isBlank() && isKnownUnboundMessage(message)) {
            messageKey = "disconnect.mineastr.login.not_bound";
        }
        String localizedCode = "";
        if (!allowed && MineAstrConfig.GENERATE_BINDING_CODE_ON_REJECT.getAsBoolean()) {
            String code = generateVerifyCode();
            JsonObject codeEvent = MineAstrProtocol.eventEnvelope("binding_code");
            codeEvent.addProperty("player_name", pending.playerName);
            codeEvent.addProperty("code", code);
            MineAstrWebSocket.send(codeEvent);
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

    private LoginCheckResult loginCheckFallback(String reason) {
        boolean allowed = MineAstrConfig.LOGIN_CHECK_FAIL_OPEN.getAsBoolean();
        MineAstr.LOGGER.warn("MineAstr 登录绑定校验失败：{}；策略={}", reason, allowed ? "fail-open" : "fail-closed");
        return new LoginCheckResult(
                allowed,
                allowed ? "" : "[MC] 无法连接 AstrBot 完成账号绑定校验，请稍后重试。",
                allowed ? "" : "disconnect.mineastr.login.unavailable",
                "");
    }

    private void clearPendingLoginChecks(String reason) {
        for (PendingLoginCheck pending : pendingLoginChecks.values()) {
            pending.future.complete(loginCheckFallback(reason));
        }
        pendingLoginChecks.clear();
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

    public record LoginCheckResult(boolean allowed, String message, String messageKey, String localizedCode) {
    }

    private record PendingLoginCheck(WebSocket socket, String playerName, CompletableFuture<LoginCheckResult> future) {
    }
}
