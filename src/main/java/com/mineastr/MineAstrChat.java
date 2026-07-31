package com.mineastr;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class MineAstrChat {
    private static final int MAX_BROADCAST_CONTENT_LENGTH = 2000;
    private static final int MAX_BROADCAST_SENDER_LENGTH = 64;
    private static final ConcurrentMap<UUID, TranslationPreference> translationPreferences = new ConcurrentHashMap<>();

    private MineAstrChat() {
    }

    public static void broadcast(JsonObject payload, MinecraftServer server) {
        String senderName = MineAstrProtocol.trimFlatContent(
                MineAstrProtocol.getString(payload, "sender_name", MineAstrConfig.BOT_DISPLAY_NAME.get()),
                MAX_BROADCAST_SENDER_LENGTH);
        String content = MineAstrProtocol.trimFlatContent(
                MineAstrProtocol.getString(payload, "content", ""),
                MAX_BROADCAST_CONTENT_LENGTH);
        if (senderName.isEmpty()) {
            senderName = MineAstrProtocol.trimFlatContent(MineAstrConfig.BOT_DISPLAY_NAME.get(), MAX_BROADCAST_SENDER_LENGTH);
        }
        if (content.isBlank()) {
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
                String translated = MineAstrProtocol.trimFlatContent(entry.getValue().getAsString(), MAX_BROADCAST_CONTENT_LENGTH);
                if (!translated.isBlank()) {
                    translations.addProperty(language, translated);
                }
            }
        }
        boolean defaultShowOriginal = MineAstrProtocol.getBoolean(payload, "show_original", false);
        String finalSenderName = senderName;
        String finalContent = content;
        server.execute(() -> {
            MineAstr.LOGGER.info("[{}] {}", finalSenderName, finalContent);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(render(
                        player,
                        finalSenderName,
                        finalContent,
                        translations,
                        defaultShowOriginal));
            }
        });
    }

    public static Component render(
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

    public static String selectTranslation(JsonObject translations, String language) {
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

    public static void registerPreference(ServerPlayer player, boolean translationsEnabled, boolean showOriginal) {
        translationPreferences.put(
                player.getUUID(),
                new TranslationPreference(translationsEnabled, showOriginal));
        MineAstr.LOGGER.debug(
                "MineAstr 已记录玩家 {} 的翻译显示偏好：enabled={} show_original={}",
                player.getGameProfile().name(),
                translationsEnabled,
                showOriginal);
    }

    public static void unregisterPreference(ServerPlayer player) {
        translationPreferences.remove(player.getUUID());
    }

    public record TranslationPreference(boolean translationsEnabled, boolean showOriginal) {
    }
}
