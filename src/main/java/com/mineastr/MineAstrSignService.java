package com.mineastr;

import com.google.gson.JsonObject;
import com.mineastr.MineAstrBridge.SignTranslationAdminView;
import com.mineastr.MineAstrBridge.TranslationPreference;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static com.mineastr.MineAstrChatText.sameSignText;
import static com.mineastr.MineAstrChatText.selectTranslation;
import static com.mineastr.MineAstrChatText.trimSignText;
import static com.mineastr.MineAstrLimits.SIGN_ADMIN_TARGET_DISTANCE;
import static com.mineastr.MineAstrProtocolValues.getBoolean;
import static com.mineastr.MineAstrProtocolValues.getString;
import static com.mineastr.MineAstrProtocolValues.sha256;
import static com.mineastr.MineAstrProtocolValues.startsWith;
import static com.mineastr.MineAstrProtocolValues.trimFlatContent;

/**
 * 告示牌翻译、人工覆盖与缓存修订；旧异步结果不能复活已清理缓存。
 * Sign translation, manual overrides and cache revisions; stale replies must not restore cleared entries.
 */
final class MineAstrSignService {
    private final MineAstrBridgeContext context;

    private final ConcurrentMap<String, PendingSignTranslation> pendingSignTranslations = new ConcurrentHashMap<>();

    private volatile SignTranslationStore signTranslationStore = new SignTranslationStore();

    private final AtomicLong signTranslationCacheRevision = new AtomicLong();

    private static final Map<String, String> SKIPPED_SIGN_TRANSLATION_MARKER =
            Map.of("mineastr_skip", "1");

    MineAstrSignService(MineAstrBridgeContext context) {
        this.context = context;
    }

    /** Returns cache details for the sign side currently under the player's crosshair. */
    public SignTranslationAdminView inspectTargetedSignTranslation(ServerPlayer player) {
        TargetedSign target = targetedSign(player);
        if (target == null) {
            return null;
        }
        return adminView(target);
    }

    /** Stores a locale-specific administrator override for the targeted sign side. */
    public boolean setTargetedSignTranslation(
            ServerPlayer player,
            String language,
            String translation) {
        TargetedSign target = targetedSign(player);
        String normalizedLanguage = SignTranslationStore.normalizeLanguage(language);
        String normalizedTranslation = trimSignText(translation);
        if (target == null || normalizedLanguage.isBlank() || normalizedTranslation.isBlank()) {
            return false;
        }
        boolean showOriginal = signTranslationStore.find(target.signId(), target.fingerprint())
                .map(SignTranslationStore.Entry::showOriginal)
                .orElse(false);
        boolean stored = signTranslationStore.putManual(
                target.signId(),
                target.fingerprint(),
                target.source(),
                normalizedLanguage,
                normalizedTranslation,
                showOriginal);
        if (stored) {
            invalidateSignTranslationCaches();
        }
        return stored;
    }

    /**
     * Clears either one locale or the whole current-side entry.
     * Returns -1 when no sign is targeted, 0 when nothing matched, and 1 when removed.
     */
    public int clearTargetedSignTranslation(ServerPlayer player, String language) {
        TargetedSign target = targetedSign(player);
        if (target == null) {
            return -1;
        }
        boolean hadPending = pendingSignTranslations.values().stream()
                .anyMatch(pending -> pending.signId().equals(target.signId())
                        && pending.fingerprint().equals(target.fingerprint()));
        boolean removed = language == null
                ? signTranslationStore.remove(target.signId())
                : signTranslationStore.removeLanguage(
                        target.signId(), target.fingerprint(), language);
        invalidateSignTranslationCaches();
        return removed || hadPending ? 1 : 0;
    }

    int clearAllSignTranslations() {
        int removed = signTranslationStore.clearAll();
        invalidateSignTranslationCaches();
        return removed;
    }

    int signTranslationCacheSize() {
        return signTranslationStore.size();
    }

    SignTranslationAdminView adminView(TargetedSign target) {
        return new SignTranslationAdminView(
                target.pos(),
                target.front(),
                target.source(),
                signTranslationStore.find(target.signId(), target.fingerprint()).orElse(null));
    }

    TargetedSign targetedSign(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        HitResult hit = player.pick(SIGN_ADMIN_TARGET_DISTANCE, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        BlockEntity entity = level.getBlockEntity(blockHit.getBlockPos());
        if (!(level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof SignBlock)
                || !(entity instanceof SignBlockEntity sign)) {
            return null;
        }
        boolean front = resolveSignSide(sign, sign.isFacingFrontText(player));
        String source = signSource(sign, front);
        if (source.isBlank()) {
            return null;
        }
        String id = signId(level, sign.getBlockPos(), front);
        return new TargetedSign(sign.getBlockPos(), front, id, sha256(source), source);
    }

    void invalidateSignTranslationCaches() {
        long revision = signTranslationCacheRevision.incrementAndGet();
        MinecraftServer currentServer = context.server();
        if (currentServer == null) {
            return;
        }
        for (ServerPlayer onlinePlayer : currentServer.getPlayerList().getPlayers()) {
            MineAstrNetwork.sendSignTranslationCacheReset(onlinePlayer, revision);
        }
    }

    long currentSignTranslationCacheRevision() {
        return signTranslationCacheRevision.get();
    }

    boolean isCurrentSignTranslationCacheRevision(long revision) {
        return revision == signTranslationCacheRevision.get();
    }

    void handleSignTranslationQuery(
            ServerPlayer player,
            MineAstrPayloads.SignTranslationQuery query) {
        if (player == null || query == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockEntity entity = level.getBlockEntity(query.pos());
        if (!(level.getBlockState(query.pos()).getBlock() instanceof SignBlock)
                || !(entity instanceof SignBlockEntity sign)) {
            return;
        }
        boolean front = resolveSignSide(sign, query.front());
        MineAstr.LOGGER.debug(
                "MineAstr received sign translation query: player={} pos={} front={}",
                player.getGameProfile().getName(),
                query.pos(),
                front);
        requestSignTranslation(player, sign, front, true);
    }

    void translateSign(ServerPlayer player, SignBlockEntity sign, boolean front) {
        if (player == null || sign == null) {
            return;
        }
        front = resolveSignSide(sign, front);
        /*
         * Do not return early for clients that advertised the screenshot
         * capability. Older versions used that capability as a proxy for the
         * sign overlay and therefore skipped the only server-side trigger
         * when the client-side crosshair query was unavailable. A right-click
         * is a reliable fallback trigger; modern clients still receive the
         * result as an overlay, while legacy clients receive the old chat
         * fallback.
         */
        boolean clientOverlay = context.supportsScreenshot(player.getUUID())
                && MineAstrNetwork.canSendSignTranslationResult(player);
        requestSignTranslation(player, sign, front, clientOverlay);
    }

    void requestSignTranslation(
            ServerPlayer player,
            SignBlockEntity sign,
            boolean front,
            boolean clientOverlay) {
        if (!(sign.getLevel() instanceof ServerLevel level)) {
            return;
        }
        String source = signSource(sign, front);
        if (source.isBlank()) {
            return;
        }
        String signId = signId(level, sign.getBlockPos(), front);
        String fingerprint = sha256(source);
        var cached = signTranslationStore.find(signId, fingerprint);
        if (cached.isPresent()
                && (cached.get().skipTranslation() || !cached.get().translations().isEmpty())) {
            Map<String, String> clientTranslations = cached.get().skipTranslation()
                    ? SKIPPED_SIGN_TRANSLATION_MARKER
                    : cached.get().translations();
            if (clientOverlay) {
                sendSignTranslationResult(
                        player,
                        sign.getBlockPos(),
                        front,
                        fingerprint,
                        clientTranslations,
                        cached.get().showOriginal(),
                        true);
            } else {
                if (!cached.get().skipTranslation()) {
                    sendTranslatedSign(player, source, cached.get().translations(), cached.get().showOriginal());
                }
            }
            return;
        }
        if (cached.isPresent()) {
            MineAstr.LOGGER.debug(
                    "MineAstr ignoring empty sign translation cache and retrying: sign={} player={}",
                    signId,
                    player.getGameProfile().getName());
            signTranslationStore.remove(signId, fingerprint);
        }

        WebSocket socket = context.socket();
        if (socket == null || socket.isOutputClosed()) {
            MineAstr.LOGGER.debug(
                    "MineAstr sign translation skipped because the AstrBot WebSocket is unavailable: sign={} player={}",
                    signId,
                    player.getGameProfile().getName());
            if (clientOverlay) {
                sendSignTranslationResult(
                        player, sign.getBlockPos(), front, fingerprint, Map.of(), false, false);
            } else {
                sendTranslatedSign(player, source, Map.of(), false);
            }
            return;
        }
        for (PendingSignTranslation pending : pendingSignTranslations.values()) {
            if (pending.socket() == socket
                    && pending.playerUuid().equals(player.getUUID())
                    && pending.pos().equals(sign.getBlockPos())
                    && pending.front() == front
                    && pending.fingerprint().equals(fingerprint)) {
                return;
            }
        }
        String messageId = UUID.randomUUID().toString();
        PendingSignTranslation pending = new PendingSignTranslation(
                socket,
                messageId,
                player.getUUID(),
                sign.getBlockPos(),
                front,
                signId,
                fingerprint,
                source,
                clientOverlay,
                currentSignTranslationCacheRevision());
        if (pendingSignTranslations.putIfAbsent(messageId, pending) != null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "sign_translate_request");
        payload.addProperty("message_id", messageId);
        payload.addProperty("sign_id", signId);
        payload.addProperty("source_fingerprint", fingerprint);
        payload.addProperty("text", source);
        MineAstr.LOGGER.debug(
                "MineAstr requesting sign translation: sign={} player={} overlay={}",
                signId,
                player.getGameProfile().getName(),
                clientOverlay);
        context.sendJson(socket, payload);
    }

    void sendSignTranslationResult(
            ServerPlayer player,
            BlockPos pos,
            boolean front,
            String fingerprint,
            Map<String, String> translations,
            boolean showOriginal,
            boolean ok) {
        MineAstrNetwork.sendSignTranslationResult(
                player,
                new MineAstrPayloads.SignTranslationResult(
                        pos,
                        front,
                        fingerprint,
                        translations == null ? Map.of() : Map.copyOf(translations),
                        showOriginal,
                        ok));
    }

    void sendTranslatedSign(
            ServerPlayer player,
            String original,
            Map<String, String> translations,
            boolean defaultShowOriginal) {
        TranslationPreference preference = context.translationPreference(player.getUUID());
        if (preference != null && !preference.translationsEnabled()) {
            player.sendSystemMessage(Component.literal(signLabel(player) + original));
            return;
        }
        String language = player.clientInformation().language().strip()
                .replace('-', '_').toLowerCase(Locale.ROOT);
        String translated = selectTranslation(translations, language);
        if (translated.isBlank() || sameSignText(original, translated)) {
            player.sendSystemMessage(Component.literal(signLabel(player) + original));
            return;
        }
        var component = Component.literal(signLabel(player) + translated);
        boolean showOriginal = preference == null ? defaultShowOriginal : preference.showOriginal();
        if (showOriginal) {
            String fallback = language.startsWith("zh_") ? "[原文] " : "[Original] ";
            component.append("\n");
            component.append(Component.literal(fallback + original));
        }
        player.sendSystemMessage(component);
    }

    static String signLabel(ServerPlayer player) {
        String language = player.clientInformation().language().strip()
                .replace('-', '_').toLowerCase(Locale.ROOT);
        return language.startsWith("zh_") ? "[告示牌] " : "[Sign] ";
    }

    static String signSource(SignBlockEntity sign, boolean front) {
        Component[] messages = sign.getText(front).getMessages(false);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < messages.length; index++) {
            if (index > 0) {
                builder.append('\n');
            }
            String line = messages[index] == null ? "" : messages[index].getString();
            builder.append(line.replace('\r', ' ').replace('\u0000', ' '));
        }
        return builder.toString().strip();
    }

    /**
     * Modern Minecraft keeps independent front_text/back_text values on every vanilla
     * standing, wall, and hanging sign. Prefer the side selected by the
     * player's facing, but transparently use the other side when data packs
     * or commands only populated one side.
     */
    private static boolean resolveSignSide(SignBlockEntity sign, boolean preferredFront) {
        if (!signSource(sign, preferredFront).isBlank()) {
            return preferredFront;
        }
        if (!signSource(sign, !preferredFront).isBlank()) {
            return !preferredFront;
        }
        return preferredFront;
    }

    static String signId(ServerLevel level, BlockPos pos, boolean front) {
        return level.dimension().location() + "/"
                + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "/"
                + (front ? "front" : "back");
    }

    void handleSignTranslationResult(WebSocket socket, JsonObject payload) {
        String messageId = trimFlatContent(getString(payload, "message_id", ""), 64);
        PendingSignTranslation pending = pendingSignTranslations.get(messageId);
        if (pending == null || pending.socket != socket) {
            MineAstr.LOGGER.warn("MineAstr 忽略未知或来源不匹配的告示牌翻译响应：{}", messageId);
            return;
        }
        if (!pendingSignTranslations.remove(messageId, pending)) {
            MineAstr.LOGGER.debug("MineAstr 已忽略重复的告示牌翻译响应：{}", messageId);
            return;
        }
        MinecraftServer currentServer = context.server();
        if (currentServer == null) {
            return;
        }
        boolean ok = getBoolean(payload, "ok", false);
        Map<String, String> translations = new HashMap<>();
        if (ok
                && payload.has("translations")
                && payload.get("translations").isJsonObject()) {
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
        boolean alreadyBilingual = ok && getBoolean(payload, "already_bilingual", false);
        boolean usable = ok && (alreadyBilingual || !translations.isEmpty());
        currentServer.execute(() -> {
            if (!isCurrentSignTranslationCacheRevision(pending.cacheRevision)) {
                MineAstr.LOGGER.debug(
                        "MineAstr discarded a stale sign translation response after an administrator cache update: sign={}",
                        pending.signId);
                return;
            }
            if (alreadyBilingual) {
                signTranslationStore.putSkipped(
                        pending.signId,
                        pending.fingerprint,
                        pending.source);
            } else if (usable) {
                signTranslationStore.putAutomatic(
                        pending.signId,
                        pending.fingerprint,
                        pending.source,
                        translations,
                        showOriginal);
            }
            SignTranslationStore.Entry storedEntry = usable
                    ? signTranslationStore.find(pending.signId, pending.fingerprint).orElse(null)
                    : null;
            boolean effectiveSkip = storedEntry != null && storedEntry.skipTranslation();
            Map<String, String> effectiveTranslations = storedEntry == null
                    ? translations
                    : storedEntry.translations();
            boolean effectiveShowOriginal = storedEntry == null
                    ? showOriginal
                    : storedEntry.showOriginal();
            ServerPlayer player = currentServer.getPlayerList().getPlayer(pending.playerUuid);
            if (player != null) {
                if (pending.clientOverlay) {
                    sendSignTranslationResult(
                            player,
                            pending.pos,
                            pending.front,
                            pending.fingerprint,
                            effectiveSkip ? SKIPPED_SIGN_TRANSLATION_MARKER : effectiveTranslations,
                            effectiveSkip ? false : effectiveShowOriginal,
                            usable);
                } else {
                    if (!effectiveSkip) {
                        sendTranslatedSign(
                                player,
                                pending.source,
                                effectiveTranslations,
                                effectiveShowOriginal);
                    }
                }
            }
        });
    }

    private record TargetedSign(
            BlockPos pos,
            boolean front,
            String signId,
            String fingerprint,
            String source) {
    }

    private record PendingSignTranslation(
            WebSocket socket,
            String messageId,
            UUID playerUuid,
            BlockPos pos,
            boolean front,
            String signId,
            String fingerprint,
            String source,
            boolean clientOverlay,
            long cacheRevision) {
    }

    void start(MinecraftServer server) {
        signTranslationStore = new SignTranslationStore();
        signTranslationStore.load(server.getWorldPath(LevelResource.ROOT));
    }

    void stop() {
        pendingSignTranslations.clear();
        signTranslationStore.save();
    }

}
