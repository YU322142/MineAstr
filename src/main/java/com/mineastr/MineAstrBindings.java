package com.mineastr;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

public final class MineAstrBindings {
    private static final ConcurrentMap<String, SyncedBinding> syncedBindings = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, NameAndId> observedLoginIdentities = new ConcurrentHashMap<>();

    private MineAstrBindings() {
    }

    public static void handleBindingQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_BINDING_SYNC.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "binding", "binding_sync_disabled");
            return;
        }
        String action = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "action", ""), 16).toLowerCase(Locale.ROOT);
        String playerName = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "player_name", ""), 64);
        String ownerKey = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "owner_key", ""), 256);
        String ownerDisplay = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "owner_display", ""), 128);

        if ("reset".equals(action)) {
            boolean whitelistChanged = false;
            if (MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) {
                for (SyncedBinding binding : List.copyOf(syncedBindings.values())) {
                    WhitelistSyncResult result = updateWhitelist(server, binding.identity, false, false);
                    if (!result.ok) {
                        MineAstrProtocol.sendQueryError(socket, messageId, "binding",
                                "whitelist_reset_failed:" + binding.playerName + ":" + result.error);
                        return;
                    }
                    whitelistChanged |= result.changed;
                }
            }
            syncedBindings.clear();
            MineAstr.LOGGER.warn("MineAstr 绑定同步：action=reset whitelist=", whitelistChanged);
            JsonObject data = new JsonObject();
            data.addProperty("action", action);
            data.addProperty("cache_size", 0);
            data.addProperty("whitelist_changed", whitelistChanged);
            MineAstrProtocol.sendQueryResult(socket, messageId, "binding", data);
            return;
        }

        if (!("bind".equals(action) || "unbind".equals(action))) {
            MineAstrProtocol.sendQueryError(socket, messageId, "binding", "invalid_action");
            return;
        }
        if (!isSafeName(playerName) || ownerKey.isBlank()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "binding", "invalid_binding_identity");
            return;
        }

        String normalizedPlayer = playerName.toLowerCase(Locale.ROOT);
        SyncedBinding existing = syncedBindings.get(normalizedPlayer);
        if ("unbind".equals(action) && existing != null && !existing.ownerKey.equals(ownerKey)) {
            MineAstrProtocol.sendQueryError(socket, messageId, "binding", "binding_owner_mismatch");
            return;
        }

        NameAndId identity;
        String identitySource;
        if (existing != null) {
            identity = existing.identity;
            identitySource = "synced_binding";
        } else {
            identity = observedLoginIdentities.get(normalizedPlayer);
            if (identity != null) {
                identitySource = "observed_login";
            } else if (!server.usesAuthentication()) {
                identity = NameAndId.createOffline(playerName);
                identitySource = "offline_mode";
            } else {
                try {
                    identity = server.services().nameToIdCache().get(playerName).orElse(null);
                } catch (RuntimeException exc) {
                    MineAstr.LOGGER.warn("MineAstr 无法解析玩家身份：player={}", playerName, exc);
                    MineAstrProtocol.sendQueryError(socket, messageId, "binding", "player_identity_lookup_failed");
                    return;
                }
                identitySource = "authenticated_profile";
            }
        }
        if (identity == null) {
            MineAstr.LOGGER.warn("MineAstr 无法解析玩家身份：player={} online_mode={}",
                    playerName, server.usesAuthentication());
            MineAstrProtocol.sendQueryError(socket, messageId, "binding", "player_identity_not_found");
            return;
        }

        WhitelistSyncResult whitelistResult = WhitelistSyncResult.skipped();
        if (MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) {
            whitelistResult = updateWhitelist(server, identity, "bind".equals(action), "bind".equals(action));
            if (!whitelistResult.ok) {
                MineAstr.LOGGER.warn(
                        "MineAstr 原版白名单同步失败：action={} player={} uuid={} error={}",
                        action, identity.name(), identity.id(), whitelistResult.error);
                MineAstrProtocol.sendQueryError(socket, messageId, "binding", whitelistResult.error);
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
        data.addProperty("identity_source", identitySource);
        data.addProperty("whitelist_changed", whitelistResult.changed);
        data.addProperty("whitelist_verified", whitelistResult.verified);
        MineAstrProtocol.sendQueryResult(socket, messageId, "binding", data);
    }

    public static void reconcileLogin(NameAndId loginIdentity, MinecraftServer server) {
        if (loginIdentity == null || loginIdentity.name() == null || loginIdentity.name().isBlank()) {
            return;
        }
        String normalizedPlayer = loginIdentity.name().toLowerCase(Locale.ROOT);
        observedLoginIdentities.put(normalizedPlayer, loginIdentity);

        if (server == null
                || !MineAstrConfig.ENABLE_BINDING_SYNC.getAsBoolean()
                || !MineAstrConfig.BINDING_SYNC_WHITELIST.getAsBoolean()) {
            return;
        }
        SyncedBinding binding = syncedBindings.get(normalizedPlayer);
        if (binding == null) {
            return;
        }

        boolean identityChanged = !binding.identity.id().equals(loginIdentity.id())
                || !binding.identity.name().equals(loginIdentity.name());
        WhitelistSyncResult result = updateWhitelist(server, loginIdentity, true, true);
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
        if (identityChanged || result.changed) {
            MineAstr.LOGGER.info(
                    "MineAstr 已按本次登录身份修正原版白名单：player={} old_uuid={} login_uuid={} changed={} verified={}",
                    loginIdentity.name(), binding.identity.id(), loginIdentity.id(), result.changed, result.verified);
        }
    }

    public static void clearAll() {
        syncedBindings.clear();
        observedLoginIdentities.clear();
    }

    private static WhitelistSyncResult updateWhitelist(
            MinecraftServer server,
            NameAndId identity,
            boolean shouldBePresent,
            boolean removeConflictingNameEntries) {
        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        boolean conflictRemoved = false;
        if (shouldBePresent && removeConflictingNameEntries) {
            for (UserWhiteListEntry entry : List.copyOf(whitelist.getEntries())) {
                NameAndId other = entry.getUser();
                if (other != null
                        && !other.id().equals(identity.id())
                        && other.name().equalsIgnoreCase(identity.name())) {
                    conflictRemoved |= whitelist.remove(other);
                }
            }
        }

        boolean wasPresent = whitelist.isWhiteListed(identity);
        if (shouldBePresent && !wasPresent) {
            whitelist.add(new UserWhiteListEntry(identity));
        } else if (!shouldBePresent && wasPresent) {
            whitelist.remove(identity);
        }

        boolean isPresent = whitelist.isWhiteListed(identity);
        if (isPresent != shouldBePresent) {
            return new WhitelistSyncResult(
                    false, conflictRemoved, false, "whitelist_verification_failed");
        }
        try {
            whitelist.save();
        } catch (IOException exc) {
            MineAstr.LOGGER.warn("MineAstr 保存原版白名单失败：{} {}", identity.name(), identity.id(), exc);
            return new WhitelistSyncResult(
                    false,
                    conflictRemoved || wasPresent != isPresent,
                    true,
                    "whitelist_save_failed");
        }
        return new WhitelistSyncResult(
                true, conflictRemoved || wasPresent != isPresent, true, "");
    }

    public static boolean isSafeName(String playerName) {
        if (playerName == null || playerName.isEmpty() || playerName.length() > 64) {
            return false;
        }
        return playerName.codePoints().noneMatch(Character::isISOControl);
    }

    public record SyncedBinding(String playerName, String ownerKey, String ownerDisplay, NameAndId identity) {
    }

    public record WhitelistSyncResult(boolean ok, boolean changed, boolean verified, String error) {
        public static WhitelistSyncResult skipped() {
            return new WhitelistSyncResult(true, false, false, "");
        }
    }
}
