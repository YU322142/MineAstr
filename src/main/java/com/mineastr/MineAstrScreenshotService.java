package com.mineastr;

import com.google.gson.JsonObject;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import static com.mineastr.MineAstrLimits.SCREENSHOT_MAX_CHUNKS;
import static com.mineastr.MineAstrLimits.SCREENSHOT_TIMEOUT_SECONDS;
import static com.mineastr.MineAstrProtocolValues.getInt;
import static com.mineastr.MineAstrProtocolValues.getString;
import static com.mineastr.MineAstrProtocolValues.looksLikeJpeg;
import static com.mineastr.MineAstrProtocolValues.trimContent;
import static com.mineastr.MineAstrTargets.findTargetPlayer;

/**
 * 截图能力、请求、超时与分片重组；断线和离开时统一回收。
 * Own screenshot capabilities, requests, timeouts and chunk assembly; release on disconnect/logout.
 */
final class MineAstrScreenshotService {
    private final MineAstrBridgeContext context;

    private final ConcurrentMap<UUID, ClientCapability> clientCapabilities = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, PendingScreenshot> pendingScreenshots = new ConcurrentHashMap<>();

    private final ConcurrentMap<UUID, String> pendingScreenshotByPlayer = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ScreenshotAssembly> screenshotAssemblies = new ConcurrentHashMap<>();

    MineAstrScreenshotService(MineAstrBridgeContext context) {
        this.context = context;
    }

    void registerClientCapability(ServerPlayer player, boolean screenshotSupported, String clientModVersion) {
        if (!screenshotSupported) {
            clientCapabilities.remove(player.getUUID());
            return;
        }
        clientCapabilities.put(player.getUUID(), new ClientCapability(clientModVersion, System.currentTimeMillis()));
        MineAstr.LOGGER.debug("MineAstr 已记录客户端能力：{} {}", player.getGameProfile().getName(), clientModVersion);
    }

    void handleScreenshotQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            context.sendQueryError(socket, messageId, "screenshot", "未找到要截图的在线玩家。");
            return;
        }
        if (!clientCapabilities.containsKey(player.getUUID())) {
            context.sendQueryError(socket, messageId, "screenshot", "目标玩家未安装 MineAstr 客户端 Mod，或客户端尚未声明支持截图。");
            return;
        }
        String existingRequestId = pendingScreenshotByPlayer.putIfAbsent(player.getUUID(), messageId);
        if (existingRequestId != null) {
            context.sendQueryError(socket, messageId, "screenshot", "目标玩家已有一个截图请求正在处理中。");
            return;
        }
        if (pendingScreenshots.containsKey(messageId)) {
            pendingScreenshotByPlayer.remove(player.getUUID(), messageId);
            context.sendQueryError(socket, messageId, "screenshot", "同一个截图请求正在处理中。");
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

        PendingScreenshot pending = new PendingScreenshot(socket, messageId, player.getUUID(), player.getGameProfile().getName(), maxBytes);
        PendingScreenshot previous = pendingScreenshots.putIfAbsent(messageId, pending);
        if (previous != null) {
            pendingScreenshotByPlayer.remove(player.getUUID(), messageId);
            context.sendQueryError(socket, messageId, "screenshot", "同一个截图请求正在处理中。");
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

    void receiveScreenshotChunk(ServerPlayer player, MineAstrPayloads.ScreenshotChunk chunk) {
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
        data.addProperty("player_name", player.getGameProfile().getName());
        data.addProperty("status", "ok");
        data.addProperty("mime_type", assembly.mimeType);
        data.addProperty("width", assembly.width);
        data.addProperty("height", assembly.height);
        data.addProperty("bytes", imageBytes.length);
        data.addProperty("image_base64", Base64.getEncoder().encodeToString(imageBytes));
        data.addProperty("captured_at_ms", assembly.capturedAtMs);
        context.sendQueryResult(completed.socket, completed.messageId, "screenshot", data);
    }

    void receiveScreenshotError(ServerPlayer player, String code, String message, String requestId) {
        PendingScreenshot pending = pendingScreenshots.get(requestId);
        if (pending == null || !pending.playerUuid.equals(player.getUUID())) {
            return;
        }
        PendingScreenshot removed = pendingScreenshots.remove(requestId);
        pendingScreenshotByPlayer.remove(player.getUUID(), requestId);
        screenshotAssemblies.remove(requestId);
        if (removed != null) {
            removed.cancelTimeout();
            context.sendQueryError(removed.socket, removed.messageId, "screenshot", screenshotErrorMessage(code, message));
        }
    }

    ScheduledFuture<?> scheduleScreenshotTimeout(String requestId) {
        ScheduledExecutorService executor = context.scheduler();
        if (executor == null || executor.isShutdown()) {
            return null;
        }
        return executor.schedule(() -> failScreenshot(requestId, "等待玩家客户端截图超时。"), SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    void failScreenshot(String requestId, String error) {
        PendingScreenshot pending = pendingScreenshots.remove(requestId);
        screenshotAssemblies.remove(requestId);
        if (pending == null) {
            return;
        }
        pendingScreenshotByPlayer.remove(pending.playerUuid, requestId);
        pending.cancelTimeout();
        context.sendQueryError(pending.socket, pending.messageId, "screenshot", error);
    }

    void clearScreenshotState(String error) {
        clearPendingScreenshots(error);
        clientCapabilities.clear();
    }

    void clearPendingScreenshots(String error) {
        for (PendingScreenshot pending : pendingScreenshots.values()) {
            pending.cancelTimeout();
            context.sendQueryError(pending.socket, pending.messageId, "screenshot", error);
        }
        pendingScreenshots.clear();
        pendingScreenshotByPlayer.clear();
        screenshotAssemblies.clear();
    }

    static String screenshotErrorMessage(String code, String message) {
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

    private record ClientCapability(String modVersion, long seenAtMs) {
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

    boolean supports(UUID player) { return clientCapabilities.containsKey(player); }

    void unregisterPlayer(ServerPlayer player) {
        clientCapabilities.remove(player.getUUID());
        pendingScreenshots.values().removeIf(pending -> {
            if (!pending.playerUuid.equals(player.getUUID())) {
                return false;
            }
            pending.cancelTimeout();
            pendingScreenshotByPlayer.remove(pending.playerUuid, pending.messageId);
            context.sendQueryError(pending.socket, pending.messageId, "screenshot", "目标玩家已离开服务器。");
            screenshotAssemblies.remove(pending.messageId);
            return true;
        });
    }

}
