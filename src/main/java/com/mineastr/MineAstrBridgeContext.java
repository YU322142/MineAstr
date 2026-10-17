package com.mineastr;

import com.google.gson.JsonObject;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务依赖的最小连接能力；不暴露其他领域的可变集合。
 * Narrow transport capabilities for services; never expose another domain's mutable collections.
 * Read lifecycle values at callback time, not when constructing a service.
 */
interface MineAstrBridgeContext {
    // 动态生命周期值：异步回调执行时重新读取。
    // Dynamic lifecycle: read again when an asynchronous callback runs.
    MinecraftServer server();
    WebSocket socket();
    long generation();
    boolean stopping();
    ScheduledExecutorService scheduler();
    boolean isConnected();

    // 游戏线程及玩家能力；不要通过接口传递领域内部集合。
    // Game-thread work and player capabilities, without exposing domain collections.
    MineAstrServerWorkQueue work();
    MineAstrBridge.TranslationPreference translationPreference(UUID player);
    boolean supportsScreenshot(UUID player);

    // 出站协议统一经过桥接入口，保持发送顺序和断连处理一致。
    // Route outbound protocol through the bridge for ordering and disconnect handling.
    void handleSendFailure(WebSocket socket, Throwable error);
    void forwardChat(ServerPlayer player, String text);
    void sendJson(WebSocket socket, JsonObject payload);
    CompletableFuture<Void> sendTextSerialized(WebSocket socket, String text);
    JsonObject queryEnvelope(String messageId, String query, boolean ok);
    JsonObject eventEnvelope(String event);
    void sendQueryResult(WebSocket socket, String messageId, String query, JsonObject data);
    void sendQueryError(WebSocket socket, String messageId, String query, String error);
}
