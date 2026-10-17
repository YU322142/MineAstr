package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mineastr.MineAstrBridge.LoginCheckResult;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import static com.mineastr.MineAstrProtocolValues.getBoolean;
import static com.mineastr.MineAstrProtocolValues.getString;
import static com.mineastr.MineAstrProtocolValues.trimContent;
import static com.mineastr.MineAstrProtocolValues.trimFlatContent;

/**
 * 登录决策、绑定快照与白名单对账；不拥有网络连接或命令权限。
 * Own login decisions, binding snapshots and whitelist reconciliation, independent of transport and command trust.
 */
final class MineAstrBindingService {
    private final MineAstrBridgeContext context;

    private final ConcurrentMap<String, SyncedBinding> syncedBindings = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, PlayerIdentity> observedLoginIdentities = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, PendingLoginCheck> pendingLoginChecks = new ConcurrentHashMap<>();

    private volatile BindingWhitelistStore bindingWhitelistStore;

    private final Map<String, Long> pendingBindingIdentities = new HashMap<>();

    private boolean bindingReplacementPending;

    private long bindingRevision;

    private static final Random VERIFY_CODE_RANDOM = new java.security.SecureRandom();

    MineAstrBindingService(MineAstrBridgeContext context) {
        this.context = context;
    }

    CompletableFuture<LoginCheckResult> checkPlayerLogin(String playerName) {
        if (!MineAstrConfig.LOGIN_BINDING_CHECK_ENABLED.getAsBoolean()) {
            return CompletableFuture.completedFuture(new LoginCheckResult(true, "", "", ""));
        }
        if (MineAstrConfig.LOGIN_USE_LOCAL_BINDINGS.getAsBoolean()
                && bindingWhitelistStore != null && bindingWhitelistStore.allows(playerName)) {
            MineAstr.LOGGER.info("MineAstr 登录绑定校验：source=local_whitelist allowed=true");
            return CompletableFuture.completedFuture(new LoginCheckResult(true, "", "", ""));
        }
        WebSocket socket = context.socket();
        if (socket == null || socket.isOutputClosed()) {
            return CompletableFuture.completedFuture(loginCheckFallback("AstrBot 未连接"));
        }

        String messageId = UUID.randomUUID().toString();
        CompletableFuture<LoginCheckResult> future = new CompletableFuture<>();
        String normalizedName = trimFlatContent(playerName, 64);
        PendingLoginCheck pending = new PendingLoginCheck(socket, normalizedName, future);
        pendingLoginChecks.put(messageId, pending);

        JsonObject payload = context.eventEnvelope("player_login_check");
        payload.addProperty("message_id", messageId);
        payload.addProperty("player_name", normalizedName);
        context.sendJson(socket, payload);

        int timeoutSeconds = MineAstrConfig.LOGIN_CHECK_TIMEOUT_SECONDS.getAsInt();
        return future
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(error -> loginCheckFallback("等待 AstrBot 登录校验超时"))
                .whenComplete((result, error) -> pendingLoginChecks.remove(messageId, pending));
    }

    LoginCheckResult loginCheckFallback(String reason) {
        boolean allowed = MineAstrConfig.LOGIN_CHECK_FAIL_OPEN.getAsBoolean();
        MineAstr.LOGGER.warn("MineAstr 登录绑定校验失败：{}；策略={}", reason, allowed ? "fail-open" : "fail-closed");
        return new LoginCheckResult(
                allowed,
                allowed ? "" : "[MC] 无法连接 AstrBot 完成账号绑定校验，请稍后重试。",
                allowed ? "" : "disconnect.mineastr.login.unavailable",
                "");
    }

    void handleEventResult(WebSocket socket, JsonObject payload) {
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

        if (!payload.has("allowed") || !payload.get("allowed").isJsonPrimitive()
                || !payload.get("allowed").getAsJsonPrimitive().isBoolean()) {
            pending.future.complete(loginCheckFallback("AstrBot 未返回有效的登录校验决定"));
            return;
        }
        boolean allowed = payload.get("allowed").getAsBoolean();
        String message = trimContent(getString(payload, "message", ""), 1024);
        String messageKey = trimFlatContent(getString(payload, "message_key", ""), 128);
        if (!allowed && messageKey.isBlank() && isKnownUnboundMessage(message)) {
            messageKey = "disconnect.mineastr.login.not_bound";
        }
        String localizedCode = "";
        if (!allowed && MineAstrConfig.GENERATE_BINDING_CODE_ON_REJECT.getAsBoolean()) {
            String code = generateVerifyCode();
            JsonObject codeEvent = context.eventEnvelope("binding_code");
            codeEvent.addProperty("player_name", pending.playerName);
            codeEvent.addProperty("code", code);
            context.sendJson(socket, codeEvent);
            boolean astrbotProvidedCodePrompt = containsBindingCodePlaceholder(message);
            if (astrbotProvidedCodePrompt) {
                // AstrBot 自定义的拒绝消息是完整绑定提示时，以它为准。
                // 这样既能替换 {code}，也不会再额外追加一份 Mod 默认提示。
                message = replaceBindingCodePlaceholders(message, code);
                messageKey = "";
            } else if (MineAstrConfig.DEFAULT_LOGIN_CODE_MESSAGE.equals(MineAstrConfig.LOGIN_CODE_MESSAGE.get())) {
                message = message.isBlank() ? "[MC] 该账号尚未绑定。" : message;
                localizedCode = code;
            } else {
                String codeMessage = replaceBindingCodePlaceholders(
                        MineAstrConfig.LOGIN_CODE_MESSAGE.get(), code);
                message = (message.isBlank() ? "[MC] 该账号尚未绑定。" : message) + codeMessage;
                messageKey = "";
            }
        }
        String boundOwner = trimFlatContent(getString(payload, "owner_key", ""), 256);
        if (allowed && !boundOwner.isBlank() && context.server() != null) {
            MinecraftServer current = context.server();
            current.execute(() -> {
                PlayerIdentity identity = observedLoginIdentities.get(pending.playerName.toLowerCase(Locale.ROOT));
                if (identity == null || bindingReplacementPending || context.server() != current) return;
                syncedBindings.put(pending.playerName.toLowerCase(Locale.ROOT),
                        new SyncedBinding(pending.playerName, boundOwner, "", identity));
                if (MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) updateWhitelist(current, identity, true, true);
                persistSyncedBindings();
            });
        }
        pending.future.complete(new LoginCheckResult(allowed, message, messageKey, localizedCode));
    }

    static boolean containsBindingCodePlaceholder(String message) {
        return message != null
                && (message.contains("{code}") || message.contains("<验证码>"));
    }

    static String replaceBindingCodePlaceholders(String message, String code) {
        if (message == null || message.isBlank()) {
            return message == null ? "" : message;
        }
        return message.replace("{code}", code).replace("<验证码>", code);
    }

    static boolean isKnownUnboundMessage(String message) {
        return "[MC] 该游戏账号尚未在聊天平台绑定，请先使用 /mc bind <游戏名>。".equals(message)
                || "[MineAstr] 该游戏账号尚未在聊天平台绑定，请先使用 /mc bind <游戏名>。".equals(message)
                || "[MC] This game account is not bound. Use /mc bind <player name> on QQ/Discord first.".equals(message);
    }

    static String generateVerifyCode() {
        int length = MineAstrConfig.VERIFY_CODE_LENGTH.getAsInt();
        StringBuilder code = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            code.append(VERIFY_CODE_RANDOM.nextInt(10));
        }
        return code.toString();
    }

    void handleBindingQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        handleBindingQuery(socket, messageId, payload, currentServer, null, false);
    }

    void handleBindingQuery(WebSocket socket, String messageId, JsonObject payload,
            MinecraftServer currentServer, PlayerIdentity resolvedIdentity, boolean identityResolved) {
        if (bindingReplacementPending) {
            context.sendQueryError(socket, messageId, "binding", "binding_sync_busy");
            return;
        }
        if (!MineAstrConfig.ENABLE_BINDING_SYNC.getAsBoolean()) {
            context.sendQueryError(socket, messageId, "binding", "binding_sync_disabled");
            return;
        }
        String action = trimFlatContent(getString(payload, "action", ""), 16).toLowerCase(Locale.ROOT);
        String playerName = trimFlatContent(getString(payload, "player_name", ""), 64);
        String ownerKey = trimFlatContent(getString(payload, "owner_key", ""), 256);
        String ownerDisplay = trimFlatContent(getString(payload, "owner_display", ""), 128);
        if ("replace".equals(action)) {
            handleBindingReplacement(socket, messageId, payload, currentServer);
            return;
        }
        if ("reset".equals(action)) {
            JsonObject replacement = new JsonObject();
            replacement.addProperty("action", "reset");
            replacement.add("bindings", new JsonArray());
            handleBindingReplacement(socket, messageId, replacement, currentServer);
            return;
        }
        if (!("bind".equals(action) || "unbind".equals(action))) {
            context.sendQueryError(socket, messageId, "binding", "invalid_action");
            return;
        }
        if (!isSafeBindingPlayerName(playerName) || ownerKey.isBlank()) {
            context.sendQueryError(socket, messageId, "binding", "invalid_binding_identity");
            return;
        }

        String normalizedPlayer = playerName.toLowerCase(Locale.ROOT);
        SyncedBinding existing = syncedBindings.get(normalizedPlayer);
        if ("unbind".equals(action) && existing != null && !existing.ownerKey.equals(ownerKey)) {
            context.sendQueryError(socket, messageId, "binding", "binding_owner_mismatch");
            return;
        }
        PlayerIdentity identity;
        String identitySource;
        if (existing != null) {
            identity = existing.identity;
            identitySource = "synced_binding";
        } else {
            identity = observedLoginIdentities.get(normalizedPlayer);
            if (identity != null) {
                identitySource = "observed_login";
            } else if (!currentServer.usesAuthentication()) {
                // The Mojang name cache may return an online UUID even though an
                // offline-mode server authenticates this connection with an offline
                // UUID. Proxy and Floodgate UUIDs are reconciled from the real login
                // identity immediately before vanilla performs its whitelist check.
                identity = PlayerIdentity.createOffline(playerName);
                identitySource = "offline_mode";
            } else {
                if (!identityResolved) {
                    long generation = context.generation();
                    long revision = bindingRevision;
                    if (pendingBindingIdentities.size() >= 16
                            || pendingBindingIdentities.putIfAbsent(normalizedPlayer, generation) != null) {
                        context.sendQueryError(socket, messageId, "binding", "binding_identity_lookup_busy");
                        return;
                    }
                    try {
                        // 缓存未命中时可能访问 Mojang；绝不在 Tick 线程等待。
                        // Cache misses may contact Mojang; never wait on the tick thread.
                        currentServer.getProfileCache().getAsync(playerName).whenComplete((profile, error) ->
                                currentServer.execute(() -> {
                                    pendingBindingIdentities.remove(normalizedPlayer, generation);
                                    if (context.stopping() || context.server() != currentServer || socket != context.socket()
                                            || generation != context.generation()) return;
                                    if (revision != bindingRevision) {
                                        context.sendQueryError(socket, messageId, "binding", "binding_state_changed");
                                        return;
                                    }
                                    if (error != null) {
                                        context.sendQueryError(socket, messageId, "binding", "player_identity_lookup_failed");
                                        return;
                                    }
                                    handleBindingQuery(socket, messageId, payload, currentServer,
                                            profile.map(PlayerIdentity::fromProfile).orElse(null), true);
                                }));
                    } catch (RuntimeException error) {
                        pendingBindingIdentities.remove(normalizedPlayer, generation);
                        context.sendQueryError(socket, messageId, "binding", "player_identity_lookup_failed");
                    }
                    return;
                }
                identity = resolvedIdentity;
                identitySource = "authenticated_profile";
            }
        }
        if (identity == null) {
            MineAstr.LOGGER.warn("MineAstr 无法解析玩家身份：player={} online_mode={}",
                    playerName, currentServer.usesAuthentication());
            context.sendQueryError(socket, messageId, "binding", "player_identity_not_found");
            return;
        }

        WhitelistSyncResult whitelistResult = WhitelistSyncResult.skipped();
        if (MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) {
            whitelistResult = updateWhitelist(
                    currentServer, identity, "bind".equals(action), "bind".equals(action));
            if (!whitelistResult.ok) {
                MineAstr.LOGGER.warn(
                        "MineAstr 原版白名单同步失败：action={} player={} uuid={} error={}",
                        action, identity.name(), identity.id(), whitelistResult.error);
                context.sendQueryError(socket, messageId, "binding", whitelistResult.error);
                return;
            }
        }

        if ("bind".equals(action)) {
            syncedBindings.put(normalizedPlayer, new SyncedBinding(playerName, ownerKey, ownerDisplay, identity));
        } else {
            syncedBindings.remove(normalizedPlayer);
        }

        bindingRevision++;
        persistSyncedBindings();
        MineAstr.LOGGER.info(
                "MineAstr 绑定同步成功：action={} player={} uuid={} owner={} whitelist_changed={} whitelist_verified={}",
                action, identity.name(), identity.id(), ownerKey, whitelistResult.changed, whitelistResult.verified);

        JsonObject data = new JsonObject();
        data.addProperty("action", action);
        data.addProperty("player_name", playerName);
        data.addProperty("owner_key", ownerKey);
        data.addProperty("cache_size", syncedBindings.size());
        data.addProperty("player_uuid", identity.id().toString());
        data.addProperty("identity_source", identitySource);
        data.addProperty("whitelist_changed", whitelistResult.changed);
        data.addProperty("whitelist_verified", whitelistResult.verified);
        context.sendQueryResult(socket, messageId, "binding", data);
    }

    void persistSyncedBindings() {
        if (bindingWhitelistStore == null) return;
        try {
            bindingWhitelistStore.replace(syncedBindings.values().stream().map(binding ->
                    new BindingWhitelistStore.Entry(binding.playerName, binding.ownerKey, binding.ownerDisplay, binding.identity.id())).toList());
        } catch (IOException error) {
            // Never keep a revoked local grant merely because disk persistence failed.
            bindingWhitelistStore.invalidate();
            MineAstr.LOGGER.error("MineAstr 无法持久化绑定白名单，已暂停本地放行", error);
        }
    }

    void handleBindingReplacement(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        try {
            if (!payload.has("bindings") || !payload.get("bindings").isJsonArray()) throw new IOException("invalid_binding_snapshot");
            JsonArray rows = payload.getAsJsonArray("bindings");
            if (rows.size() > BindingWhitelistStore.MAX_ENTRIES) throw new IOException("binding_snapshot_limit");
            Map<String, SyncedBinding> replacement = new LinkedHashMap<>();
            MineAstrWhitelist whitelist = new MineAstrWhitelist(currentServer.getPlayerList().getWhiteList());
            for (JsonElement row : rows) {
                if (!row.isJsonObject()) throw new IOException("invalid_binding_identity");
                JsonObject object = row.getAsJsonObject();
                String name = getString(object, "player_name", "");
                String owner = getString(object, "owner_key", "");
                String display = getString(object, "owner_display", "");
                if (!isSafeBindingPlayerName(name) || owner.isBlank() || owner.length() > 256 || display.length() > 128)
                    throw new IOException("invalid_binding_identity");
                String key = name.toLowerCase(Locale.ROOT);
                SyncedBinding existing = syncedBindings.get(key);
                PlayerIdentity identity = observedLoginIdentities.get(key);
                if (identity == null && existing != null) identity = existing.identity;
                if (identity == null) identity = PlayerIdentity.fromProfile(whitelist.find(name));
                // Unseen online/proxy identities are reconciled from the authenticated login profile.
                if (identity == null) identity = PlayerIdentity.createOffline(name);
                if (replacement.putIfAbsent(key, new SyncedBinding(name, owner, display, identity)) != null)
                    throw new IOException("duplicate_binding_identity");
            }
            if (bindingWhitelistStore == null) throw new IOException("binding_store_unavailable");
            boolean syncWhitelist = MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean();
            List<SyncedBinding> grants = new ArrayList<>(replacement.values());
            List<SyncedBinding> revocations = syncedBindings.entrySet().stream()
                    .filter(entry -> !replacement.containsKey(entry.getKey()))
                    .map(Map.Entry::getValue).toList();
            long generation = context.generation();
            bindingReplacementPending = true;
            boolean accepted = context.work().submit(() -> {
                if (context.stopping() || context.server() != currentServer || socket != context.socket()
                        || generation != context.generation()) {
                    bindingReplacementPending = false;
                    return true;
                }
                boolean whitelistChanged = false;
                if (syncWhitelist) {
                    MineAstrWhitelist.Result update = whitelist.reconcile(
                            grants.stream().map(binding -> binding.identity.profile()).toList(),
                            revocations.stream().map(binding -> binding.identity.profile()).toList());
                    if (!update.ok()) throw new IllegalStateException(update.error());
                    whitelistChanged = update.changed();
                }
                try {
                    // 完整落盘后才发布新缓存；never publish a partially persisted snapshot.
                    bindingWhitelistStore.replace(replacement.values().stream().map(binding ->
                            new BindingWhitelistStore.Entry(binding.playerName, binding.ownerKey,
                                    binding.ownerDisplay, binding.identity.id())).toList());
                    syncedBindings.clear();
                    syncedBindings.putAll(replacement);
                    bindingReplacementPending = false;
                    JsonObject result = new JsonObject();
                    result.addProperty("applied", replacement.size());
                    result.addProperty("cache_size", replacement.size());
                    result.addProperty("whitelist_changed", whitelistChanged);
                    result.addProperty("action", getString(payload, "action", "replace"));
                    context.sendQueryResult(socket, messageId, "binding", result);
                } catch (IOException error) {
                    throw new IllegalStateException("binding_snapshot_persistence_failed", error);
                }
                return true;
            }, error -> {
                bindingReplacementPending = false;
                bindingWhitelistStore.invalidate();
                MineAstr.LOGGER.warn("MineAstr 绑定快照同步失败，暂停本地放行", error);
                context.sendQueryError(socket, messageId, "binding", "binding_snapshot_failed");
            });
            if (accepted) {
                bindingRevision++;
                // 名单变化期间停止本地缓存放行，避免分批撤销时沿用旧授权。
                // Suspend cached grants while changed snapshots are being reconciled.
                if (!replacement.equals(syncedBindings)) bindingWhitelistStore.invalidate();
            } else {
                bindingReplacementPending = false;
                context.sendQueryError(socket, messageId, "binding", "server_work_queue_full");
            }
        } catch (IOException | RuntimeException error) {
            MineAstr.LOGGER.warn("MineAstr 绑定白名单同步失败，保留上次成功缓存：{}", error.getMessage());
            context.sendQueryError(socket, messageId, "binding", "binding_snapshot_failed");
        }
    }

    /**
     * Reconciles a bound player against the exact identity vanilla is about to
     * check. This avoids guessing UUIDs for offline mode, proxies and Floodgate.
     */
    public void reconcileLoginWhitelistIdentity(GameProfile profile) {
        PlayerIdentity loginIdentity = PlayerIdentity.fromProfile(profile);
        if (loginIdentity == null || loginIdentity.name() == null || loginIdentity.name().isBlank()) {
            return;
        }
        String normalizedPlayer = loginIdentity.name().toLowerCase(Locale.ROOT);
        observedLoginIdentities.put(normalizedPlayer, loginIdentity);

        MinecraftServer currentServer = context.server();
        if (currentServer == null
                || !MineAstrConfig.ENABLE_BINDING_SYNC.getAsBoolean()
                || !MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) {
            return;
        }
        SyncedBinding binding = syncedBindings.get(normalizedPlayer);
        if (binding == null || bindingReplacementPending) {
            return;
        }

        boolean identityChanged = !binding.identity.id().equals(loginIdentity.id())
                || !binding.identity.name().equals(loginIdentity.name());
        WhitelistSyncResult result = updateWhitelist(
                currentServer, loginIdentity, true, true);
        if (!result.ok) {
            MineAstr.LOGGER.warn(
                    "MineAstr 登录白名单身份对账失败：player={} expected_uuid={} login_uuid={} error={}",
                    loginIdentity.name(), binding.identity.id(), loginIdentity.id(), result.error);
            return;
        }
        if (identityChanged) {
            syncedBindings.put(normalizedPlayer, new SyncedBinding(
                    loginIdentity.name(), binding.ownerKey, binding.ownerDisplay, loginIdentity));
        }
        if (identityChanged) persistSyncedBindings();
        if (identityChanged || result.changed) {
            MineAstr.LOGGER.info(
                    "MineAstr 已按本次登录身份修正原版白名单：player={} old_uuid={} login_uuid={} changed={} verified={}",
                    loginIdentity.name(), binding.identity.id(), loginIdentity.id(), result.changed, result.verified);
        }
    }

    static WhitelistSyncResult updateWhitelist(
            MinecraftServer currentServer, PlayerIdentity identity, boolean shouldBePresent) {
        return updateWhitelist(currentServer, identity, shouldBePresent, false);
    }

    static WhitelistSyncResult updateWhitelist(
            MinecraftServer currentServer,
            PlayerIdentity identity,
            boolean shouldBePresent,
            boolean removeConflictingNameEntries) {
        MineAstrWhitelist.Result result = new MineAstrWhitelist(currentServer.getPlayerList().getWhiteList())
                .update(identity.profile(), shouldBePresent, removeConflictingNameEntries);
        return new WhitelistSyncResult(result.ok(), result.changed(), result.verified(), result.error());
    }

    static boolean isSafeBindingPlayerName(String playerName) {
        if (playerName == null || playerName.isEmpty() || playerName.length() > 64) {
            return false;
        }
        return playerName.codePoints().noneMatch(Character::isISOControl);
    }

    void clearPendingLoginChecks(String error) {
        if (pendingLoginChecks.isEmpty()) {
            return;
        }
        LoginCheckResult fallback = loginCheckFallback(error);
        pendingLoginChecks.values().forEach(pending -> pending.future.complete(fallback));
        pendingLoginChecks.clear();
    }

    private record PendingLoginCheck(
            WebSocket socket,
            String playerName,
            CompletableFuture<LoginCheckResult> future) {
    }

    private record SyncedBinding(
            String playerName, String ownerKey, String ownerDisplay, PlayerIdentity identity) {
    }

    private record PlayerIdentity(UUID id, String name) {
        private static PlayerIdentity fromProfile(GameProfile profile) {
            if (profile == null || profile.getId() == null || profile.getName() == null) {
                return null;
            }
            return new PlayerIdentity(profile.getId(), profile.getName());
        }

        private static PlayerIdentity createOffline(String playerName) {
            if (playerName == null || playerName.isBlank()) {
                return null;
            }
            UUID uuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
            return new PlayerIdentity(uuid, playerName);
        }

        private GameProfile profile() {
            return new GameProfile(id, name);
        }
    }

    private record WhitelistSyncResult(boolean ok, boolean changed, boolean verified, String error) {
        private static WhitelistSyncResult skipped() {
            return new WhitelistSyncResult(true, false, false, "");
        }
    }

    void start(MinecraftServer currentServer) {
        pendingBindingIdentities.clear();
        bindingReplacementPending = false;
        bindingRevision++;
        syncedBindings.clear();
        String bindingScope = UUID.nameUUIDFromBytes((MineAstrConfig.SERVER_ID.get() + "\n"
                + MineAstrConfig.WEBSOCKET_URL.get() + "\n" + MineAstrConfig.TOKEN.get()).getBytes(StandardCharsets.UTF_8)).toString();
        bindingWhitelistStore = new BindingWhitelistStore(
                currentServer.getWorldPath(LevelResource.ROOT).resolve("data/mineastr_binding_whitelist.json"), bindingScope);
        try {
            bindingWhitelistStore.load();
            for (BindingWhitelistStore.Entry entry : bindingWhitelistStore.snapshot()) {
                syncedBindings.put(entry.playerName().toLowerCase(Locale.ROOT), new SyncedBinding(
                        entry.playerName(), entry.ownerKey(), entry.ownerDisplay(), new PlayerIdentity(entry.uuid(), entry.playerName())));
            }
            MineAstr.LOGGER.info("MineAstr 已加载本地绑定白名单：count={}", syncedBindings.size());
        } catch (IOException error) { MineAstr.LOGGER.warn("MineAstr 本地绑定白名单加载失败，未知玩家仍需远端校验", error); }
    }

    void resetSessionState() {
        pendingBindingIdentities.clear();
        bindingReplacementPending = false;
        observedLoginIdentities.clear();
    }

}
