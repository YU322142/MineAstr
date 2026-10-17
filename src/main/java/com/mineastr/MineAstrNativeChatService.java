package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineastr.MineAstrBridge.TranslationPreference;
import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import static com.mineastr.MineAstrChatText.nativeChatTargetLanguages;
import static com.mineastr.MineAstrChatText.normalizeNativeChatLanguage;
import static com.mineastr.MineAstrChatText.sameSignText;
import static com.mineastr.MineAstrChatText.selectTranslation;
import static com.mineastr.MineAstrChatText.trimNativeChatPacketText;
import static com.mineastr.MineAstrLimits.DEFAULT_NATIVE_CHAT_TIMEOUT_MS;
import static com.mineastr.MineAstrLimits.MAX_BROADCAST_CONTENT_LENGTH;
import static com.mineastr.MineAstrLimits.MAX_BROADCAST_SENDER_LENGTH;
import static com.mineastr.MineAstrLimits.MAX_NATIVE_CHAT_RATE;
import static com.mineastr.MineAstrLimits.MAX_NATIVE_CHAT_TIMEOUT_MS;
import static com.mineastr.MineAstrLimits.MAX_PENDING_NATIVE_CHATS;
import static com.mineastr.MineAstrLimits.MIN_NATIVE_CHAT_TIMEOUT_MS;
import static com.mineastr.MineAstrLimits.NATIVE_CHAT_RATE_WINDOW_MS;
import static com.mineastr.MineAstrProtocolValues.getBoolean;
import static com.mineastr.MineAstrProtocolValues.getInt;
import static com.mineastr.MineAstrProtocolValues.getString;
import static com.mineastr.MineAstrProtocolValues.startsWith;
import static com.mineastr.MineAstrProtocolValues.trimContent;
import static com.mineastr.MineAstrProtocolValues.trimFlatContent;

/**
 * 原生聊天的即时预览、按序译文更新与预算派发；不改变网络等待机制。
 * Native-chat previews, ordered completions and budgeted dispatch; network waits remain asynchronous.
 */
final class MineAstrNativeChatService {
    private static final Gson GSON = new Gson();
    private final MineAstrBridgeContext context;

    private final ConcurrentMap<String, PendingNativeChat> pendingNativeChats = new ConcurrentHashMap<>();

    private final ConcurrentMap<UUID, ArrayDeque<Long>> nativeChatRateWindows = new ConcurrentHashMap<>();

    private final AtomicLong nativeChatSequence = new AtomicLong();

    private final Object nativeChatOrderLock = new Object();

    private final TreeMap<Long, NativeChatCompletion> completedNativeChats = new TreeMap<>();

    private final ArrayDeque<NativeChatCompletion> nativeChatDispatchQueue = new ArrayDeque<>();

    private long nextNativeChatSequence;

    private boolean nativeChatDispatchScheduled;

    private final AtomicReference<NativeChatPolicy> nativeChatPolicy =
            new AtomicReference<>(NativeChatPolicy.disabled());

    MineAstrNativeChatService(MineAstrBridgeContext context) {
        this.context = context;
    }

    /**
     * Queues a per-player native-chat translation request.
     *
     * <p>The caller may cancel the NeoForge chat event only when this method
     * returns {@code true}. Until AstrBot explicitly enables the policy for
     * the active WebSocket, normal signed vanilla chat remains untouched.</p>
     */
    public boolean queueNativeChatTranslation(ServerPlayer player, Component decoratedMessage) {
        MinecraftServer currentServer = context.server();
        if (currentServer == null || context.stopping() || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return false;
        }
        String original = trimContent(
                decoratedMessage == null ? "" : decoratedMessage.getString(),
                MAX_BROADCAST_CONTENT_LENGTH);
        String requestContent = trimContent(
                original,
                MineAstrConfig.MAX_MESSAGE_LENGTH.getAsInt());
        if (original.isBlank() || requestContent.isBlank()) {
            return false;
        }
        List<UUID> recipients = new ArrayList<>();
        List<String> rawLanguages = new ArrayList<>();
        for (ServerPlayer target : currentServer.getPlayerList().getPlayers()) {
            recipients.add(target.getUUID());
            TranslationPreference preference = context.translationPreference(target.getUUID());
            if (preference == null || preference.translationsEnabled()) {
                rawLanguages.add(target.clientInformation().language());
            }
        }
        List<String> targetLanguages = nativeChatTargetLanguages(rawLanguages);
        String playerName = trimFlatContent(player.getGameProfile().getName(), MAX_BROADCAST_SENDER_LENGTH);
        ChatType.Bound chatType = ChatType.bind(
                ChatType.CHAT,
                currentServer.registryAccess(),
                Component.literal(playerName));
        WebSocket socket = context.socket();
        NativeChatPolicy policy = nativeChatPolicy.get();
        String fallbackReason = null;
        if (!policy.enabled
                || policy.socket != socket
                || socket == null
                || socket.isInputClosed()
                || socket.isOutputClosed()) {
            fallbackReason = "translation_unavailable";
        } else if (pendingNativeChats.size() >= MAX_PENDING_NATIVE_CHATS) {
            fallbackReason = "translation_queue_full";
        } else if (targetLanguages.isEmpty()) {
            fallbackReason = "no_target_locale";
        } else if (!allowNativeChatRate(player.getUUID())) {
            MineAstr.LOGGER.debug(
                    "MineAstr native chat rate limit reached for {}; preserving ordered original when needed",
                    player.getGameProfile().getName());
            fallbackReason = "translation_rate_limited";
        }
        if (fallbackReason != null) {
            return queueOrderedNativeFallbackIfNeeded(
                    socket,
                    player,
                    playerName,
                    original,
                    recipients,
                    chatType,
                    fallbackReason);
        }

        long sequence = reserveNativeChatSequence(false);
        if (sequence < 0L) {
            return false;
        }
        String messageId = UUID.randomUUID().toString();
        PendingNativeChat pending = new PendingNativeChat(
                socket,
                messageId,
                sequence,
                player.getUUID(),
                playerName,
                original,
                recipients,
                chatType);
        if (pendingNativeChats.putIfAbsent(messageId, pending) != null) {
            scheduleNativeChatBroadcast(pending, Map.of(), false, "request_id_collision");
            return true;
        }
        previewNativeChat(pending);
        pending.timeout = scheduleNativeChatTimeout(messageId, policy.timeoutMs);
        if (pending.timeout == null) {
            if (pendingNativeChats.remove(messageId, pending)) {
                scheduleNativeChatBroadcast(pending, Map.of(), false, "timeout_scheduler_unavailable");
            }
            return true;
        }
        if (context.socket() != socket
                || nativeChatPolicy.get() != policy
                || !policy.enabled) {
            if (pendingNativeChats.remove(messageId, pending)) {
                pending.cancelTimeout();
                scheduleNativeChatBroadcast(pending, Map.of(), false, "native_chat_policy_changed");
            }
            return true;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "chat");
        payload.addProperty("message_id", messageId);
        payload.addProperty("native_chat", true);
        payload.addProperty("native_chat_id", messageId);
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", playerName);
        payload.addProperty("content", requestContent);
        payload.addProperty("native_original_content", original);
        JsonArray languages = new JsonArray();
        targetLanguages.forEach(languages::add);
        payload.add("target_languages", languages);

        try {
            context.sendTextSerialized(socket, GSON.toJson(payload)).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    failPendingNativeChat(messageId, socket, "send_failed");
                    context.handleSendFailure(socket, throwable);
                }
            });
        } catch (RuntimeException exc) {
            if (pendingNativeChats.remove(messageId, pending)) {
                pending.cancelTimeout();
                scheduleNativeChatBroadcast(pending, Map.of(), false, "send_failed");
                context.handleSendFailure(socket, exc);
                return true;
            }
            context.handleSendFailure(socket, exc);
            return true;
        }
        return true;
    }

    long reserveNativeChatSequence(boolean requireExistingWork) {
        synchronized (nativeChatOrderLock) {
            long incomplete = Math.max(0L, nativeChatSequence.get() - nextNativeChatSequence);
            long outstanding = incomplete
                    + nativeChatDispatchQueue.size()
                    + (nativeChatDispatchScheduled ? 1L : 0L);
            if ((requireExistingWork && outstanding == 0L)
                    || outstanding >= MAX_PENDING_NATIVE_CHATS) {
                return -1L;
            }
            return nativeChatSequence.getAndIncrement();
        }
    }

    boolean queueOrderedNativeFallbackIfNeeded(
            WebSocket socket,
            ServerPlayer player,
            String playerName,
            String original,
            List<UUID> recipients,
            ChatType.Bound chatType,
            String reason) {
        long sequence = reserveNativeChatSequence(true);
        if (sequence < 0L) {
            return false;
        }
        PendingNativeChat pending = new PendingNativeChat(
                socket,
                UUID.randomUUID().toString(),
                sequence,
                player.getUUID(),
                playerName,
                original,
                recipients,
                chatType);
        previewNativeChat(pending);
        scheduleNativeChatBroadcast(pending, Map.of(), false, reason);
        return true;
    }

    void handleNativeChatPolicy(WebSocket socket, JsonObject payload) {
        if (socket != context.socket()) {
            MineAstr.LOGGER.debug("MineAstr ignored a native chat policy from an inactive WebSocket.");
            return;
        }
        boolean enabled = getBoolean(payload, "enabled", false);
        int timeoutMs = getInt(
                payload,
                "timeout_ms",
                DEFAULT_NATIVE_CHAT_TIMEOUT_MS,
                MIN_NATIVE_CHAT_TIMEOUT_MS,
                MAX_NATIVE_CHAT_TIMEOUT_MS);
        NativeChatPolicy previousPolicy = nativeChatPolicy.get();
        if (previousPolicy.socket() == socket
                && previousPolicy.enabled() == enabled
                && previousPolicy.timeoutMs() == timeoutMs) {
            MineAstr.LOGGER.debug(
                    "MineAstr native Minecraft chat translation policy unchanged: enabled={} timeout={}ms",
                    enabled,
                    timeoutMs);
            return;
        }
        nativeChatPolicy.set(new NativeChatPolicy(socket, enabled, timeoutMs));
        if (!enabled) {
            clearPendingNativeChats(socket, "policy_disabled");
        }
        MineAstr.LOGGER.info(
                "MineAstr native Minecraft chat translation policy: enabled={} timeout={}ms",
                enabled,
                timeoutMs);
    }

    void handleNativeChatTranslationResult(WebSocket socket, JsonObject payload) {
        String messageId = trimFlatContent(getString(payload, "native_chat_id", ""), 64);
        if (messageId.isBlank()) {
            messageId = trimFlatContent(getString(payload, "message_id", ""), 64);
        }
        PendingNativeChat pending = pendingNativeChats.get(messageId);
        if (pending == null || pending.socket != socket) {
            MineAstr.LOGGER.debug(
                    "MineAstr ignored a late, duplicate, or source-mismatched native chat result: {}",
                    messageId);
            return;
        }
        if (!pendingNativeChats.remove(messageId, pending)) {
            return;
        }
        pending.cancelTimeout();

        Map<String, String> translations = new HashMap<>();
        if (getBoolean(payload, "ok", true)
                && payload.has("translations")
                && payload.get("translations").isJsonObject()) {
            for (var entry : payload.getAsJsonObject("translations").entrySet()) {
                String language = normalizeNativeChatLanguage(entry.getKey());
                if (language.isBlank()
                        || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) {
                    continue;
                }
                String translated = trimFlatContent(
                        entry.getValue().getAsString(), MAX_BROADCAST_CONTENT_LENGTH);
                if (!translated.isBlank()) {
                    translations.put(language, translated);
                }
            }
        }
        scheduleNativeChatBroadcast(
                pending,
                Map.copyOf(translations),
                getBoolean(payload, "show_original", false),
                translations.isEmpty() ? "no_translation" : "translated");
    }

    Component renderNativeTranslatedChat(
            ServerPlayer player,
            PendingNativeChat pending,
            Map<String, String> translations,
            boolean defaultShowOriginal) {
        TranslationPreference preference = context.translationPreference(player.getUUID());
        String language = normalizeNativeChatLanguage(player.clientInformation().language());
        String translated = preference != null && !preference.translationsEnabled()
                ? ""
                : selectTranslation(translations, language);
        if (translated.isBlank() || sameSignText(pending.content, translated)) {
            return nativeChatComponent(pending.playerName, pending.content);
        }

        var component = nativeChatComponent(pending.playerName, translated);
        boolean showOriginal = preference == null ? defaultShowOriginal : preference.showOriginal();
        if (showOriginal) {
            String fallback = language.startsWith("zh_") ? "[原文] " : "[Original] ";
            component.append("\n");
            component.append(Component.translatableWithFallback(
                    "message.mineastr.original_prefix", fallback));
            component.append(Component.literal(pending.content));
        }
        return component;
    }

    static net.minecraft.network.chat.MutableComponent nativeChatComponent(
            String playerName, String content) {
        return Component.translatable(
                "chat.type.text",
                Component.literal(playerName),
                Component.literal(content));
    }

    ScheduledFuture<?> scheduleNativeChatTimeout(String messageId, int timeoutMs) {
        ScheduledExecutorService executor = context.scheduler();
        if (executor == null || executor.isShutdown()) {
            return null;
        }
        return executor.schedule(
                () -> failPendingNativeChat(messageId, null, "translation_timeout"),
                Math.max(MIN_NATIVE_CHAT_TIMEOUT_MS, Math.min(MAX_NATIVE_CHAT_TIMEOUT_MS, timeoutMs)),
                TimeUnit.MILLISECONDS);
    }

    void failPendingNativeChat(String messageId, WebSocket expectedSocket, String reason) {
        PendingNativeChat pending = pendingNativeChats.get(messageId);
        if (pending == null || (expectedSocket != null && pending.socket != expectedSocket)) {
            return;
        }
        if (!pendingNativeChats.remove(messageId, pending)) {
            return;
        }
        pending.cancelTimeout();
        scheduleNativeChatBroadcast(pending, Map.of(), false, reason);
    }

    void clearPendingNativeChats(WebSocket expectedSocket, String reason) {
        for (var entry : pendingNativeChats.entrySet()) {
            PendingNativeChat pending = entry.getValue();
            if ((expectedSocket == null || pending.socket == expectedSocket)
                    && pendingNativeChats.remove(entry.getKey(), pending)) {
                pending.cancelTimeout();
                scheduleNativeChatBroadcast(pending, Map.of(), false, reason);
            }
        }
    }

    void scheduleNativeChatBroadcast(
            PendingNativeChat pending,
            Map<String, String> translations,
            boolean defaultShowOriginal,
            String completionReason) {
        if (context.server() == null) {
            return;
        }
        List<NativeChatCompletion> ready = new ArrayList<>();
        synchronized (nativeChatOrderLock) {
            completedNativeChats.put(
                    pending.sequence,
                    new NativeChatCompletion(pending, Map.copyOf(translations), defaultShowOriginal, completionReason));
            drainNativeChatCompletions(ready);
            enqueueNativeChatCompletionsLocked(ready);
        }
    }

    void drainNativeChatCompletions(List<NativeChatCompletion> ready) {
        while (true) {
            NativeChatCompletion completion = completedNativeChats.remove(nextNativeChatSequence);
            if (completion == null) {
                return;
            }
            ready.add(completion);
            nextNativeChatSequence++;
        }
    }

    void enqueueNativeChatCompletionsLocked(List<NativeChatCompletion> ready) {
        nativeChatDispatchQueue.addAll(ready);
        if (nativeChatDispatchQueue.isEmpty() || nativeChatDispatchScheduled) {
            return;
        }
        MinecraftServer currentServer = context.server();
        if (currentServer == null) {
            nativeChatDispatchQueue.clear();
            return;
        }
        nativeChatDispatchScheduled = true;
        currentServer.execute(this::drainNativeChatDispatchQueue);
    }

    void drainNativeChatDispatchQueue() {
        var budget = new MineAstrDispatchBudget(System::nanoTime, 8, 2_000_000L);
        try {
            while (budget.allowNext()) {
                NativeChatCompletion completion;
                synchronized (nativeChatOrderLock) {
                    completion = nativeChatDispatchQueue.pollFirst();
                    if (completion == null) {
                        return;
                    }
                }
                try {
                    broadcastNativeChatNow(completion);
                } catch (RuntimeException exc) {
                    MineAstr.LOGGER.error(
                            "MineAstr failed to broadcast native chat {}; continuing ordered dispatch",
                            completion.pending.messageId,
                            exc);
                }
            }
        } finally {
            synchronized (nativeChatOrderLock) {
                nativeChatDispatchScheduled = false;
                if (!nativeChatDispatchQueue.isEmpty() && !continueNativeDispatchLater()) {
                    enqueueNativeChatCompletionsLocked(List.of());
                }
            }
        }
    }

    /** Called with nativeChatOrderLock held; the same ordered queue resumes on a later main-thread slice. */
    private boolean continueNativeDispatchLater() {
        ScheduledExecutorService executor = context.scheduler();
        MinecraftServer current = context.server();
        if (context.stopping() || current == null || executor == null || executor.isShutdown()) return false;
        nativeChatDispatchScheduled = true;
        try {
            executor.schedule(() -> {
                if (!context.stopping() && context.server() == current) current.execute(this::drainNativeChatDispatchQueue);
            }, 50, TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException error) {
            nativeChatDispatchScheduled = false;
            return false;
        }
    }

    boolean allowNativeChatRate(UUID playerUuid) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> timestamps = nativeChatRateWindows.computeIfAbsent(
                playerUuid,
                ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty()
                    && now - timestamps.peekFirst() >= NATIVE_CHAT_RATE_WINDOW_MS) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= MAX_NATIVE_CHAT_RATE) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    void previewNativeChat(PendingNativeChat pending) {
        MinecraftServer current = context.server();
        if (current == null) return;
        var payload = new MineAstrPayloads.NativeChat(UUID.fromString(pending.messageId), pending.playerUuid, pending.playerName, pending.content, false);
        for (UUID id : pending.recipientUuids) {
            ServerPlayer target = current.getPlayerList().getPlayer(id);
            if (target != null && MineAstrNetwork.canSendNativeChat(target)) {
                try {
                    MineAstrNetwork.sendNativeChat(target, payload);
                    pending.previewRecipients.add(id);
                } catch (RuntimeException error) {
                    MineAstr.LOGGER.warn("MineAstr native chat preview failed; retaining completion fallback: {}", error.getClass().getSimpleName());
                }
            }
        }
        MineAstr.LOGGER.debug("MineAstr native chat preview {} recipients={}", pending.messageId, pending.previewRecipients.size());
    }

    void broadcastNativeChatNow(NativeChatCompletion completion) {
        MinecraftServer currentServer = context.server();
        if (currentServer == null) {
            return;
        }
        PendingNativeChat pending = completion.pending;
        MineAstr.LOGGER.info(
                "<{}> {} [MineAstr native chat: {}]",
                pending.playerName,
                pending.content,
                completion.reason);
        for (UUID recipientUuid : pending.recipientUuids) {
            ServerPlayer target = currentServer.getPlayerList().getPlayer(recipientUuid);
            if (target == null) {
                continue;
            }
            String displayed = renderNativeTranslatedChatText(
                    target,
                    pending,
                    completion.translations,
                    completion.defaultShowOriginal);
            if (pending.previewRecipients.contains(recipientUuid) && MineAstrNetwork.canSendNativeChat(target)) {
                MineAstrNetwork.sendNativeChat(target, new MineAstrPayloads.NativeChat(
                        UUID.fromString(pending.messageId), pending.playerUuid, pending.playerName, displayed, true));
                continue;
            }
            PlayerChatMessage message = PlayerChatMessage.unsigned(
                    pending.playerUuid,
                    trimNativeChatPacketText(pending.content))
                    .withUnsignedContent(Component.literal(displayed));
            target.sendChatMessage(
                    OutgoingChatMessage.create(message),
                    false,
                    pending.chatType);
        }
        MineAstr.LOGGER.debug(
                "MineAstr completed native chat {} via {} (translations={}, elapsedMs={})",
                pending.messageId,
                completion.reason,
                completion.translations.keySet(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.createdAtNanos));
    }

    String renderNativeTranslatedChatText(
            ServerPlayer player,
            PendingNativeChat pending,
            Map<String, String> translations,
            boolean defaultShowOriginal) {
        TranslationPreference preference = context.translationPreference(player.getUUID());
        String language = normalizeNativeChatLanguage(player.clientInformation().language());
        String translated = preference != null && !preference.translationsEnabled()
                ? ""
                : selectTranslation(translations, language);
        String displayed = translated.isBlank() || sameSignText(pending.content, translated)
                ? pending.content
                : translated;
        if (!displayed.equals(pending.content)
                && (preference == null ? defaultShowOriginal : preference.showOriginal())) {
            displayed += "\n" + (language.startsWith("zh_") ? "[原文] " : "[Original] ")
                    + pending.content;
        }
        return displayed;
    }

    void disableNativeChatPolicy(WebSocket expectedSocket) {
        nativeChatPolicy.updateAndGet(policy ->
                expectedSocket == null || policy.socket == expectedSocket
                        ? NativeChatPolicy.disabled()
                        : policy);
    }

    private record NativeChatPolicy(WebSocket socket, boolean enabled, int timeoutMs) {
        private static NativeChatPolicy disabled() {
            return new NativeChatPolicy(null, false, DEFAULT_NATIVE_CHAT_TIMEOUT_MS);
        }
    }

    private record NativeChatCompletion(
            PendingNativeChat pending,
            Map<String, String> translations,
            boolean defaultShowOriginal,
            String reason) {
    }

    private static final class PendingNativeChat {
        private final WebSocket socket;
        private final String messageId;
        private final long sequence;
        private final UUID playerUuid;
        private final String playerName;
        private final String content;
        private final List<UUID> recipientUuids;
        private final ChatType.Bound chatType;
        private final Set<UUID> previewRecipients = new java.util.HashSet<>();
        private final long createdAtNanos = System.nanoTime();
        private volatile ScheduledFuture<?> timeout;

        private PendingNativeChat(
                WebSocket socket,
                String messageId,
                long sequence,
                UUID playerUuid,
                String playerName,
                String content,
                List<UUID> recipientUuids,
                ChatType.Bound chatType) {
            this.socket = socket;
            this.messageId = messageId;
            this.sequence = sequence;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.content = content;
            this.recipientUuids = List.copyOf(recipientUuids);
            this.chatType = chatType;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeout;
            if (task != null) {
                task.cancel(false);
                timeout = null;
            }
        }
    }

    void resetOrder(boolean resetSequence) {
        synchronized (nativeChatOrderLock) {
            if (resetSequence) {
                nativeChatSequence.set(0L);
                nextNativeChatSequence = 0L;
            }
            completedNativeChats.clear();
            nativeChatDispatchQueue.clear();
            nativeChatDispatchScheduled = false;
        }
        nativeChatRateWindows.clear();
    }

    void unregisterPlayer(UUID player) {
        nativeChatRateWindows.remove(player);
    }

}
