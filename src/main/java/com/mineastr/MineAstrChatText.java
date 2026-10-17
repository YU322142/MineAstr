package com.mineastr;

import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.mineastr.MineAstrLimits.MAX_NATIVE_CHAT_PACKET_LENGTH;
import static com.mineastr.MineAstrLimits.MAX_NATIVE_CHAT_TARGET_LANGUAGES;
import static com.mineastr.MineAstrProtocolValues.startsWith;

/**
 * 聊天语言选择与文本规范化；服务端和测试共用的纯函数。
 * Pure locale selection and text normalization shared by server features and tests.
 */
final class MineAstrChatText {
    private MineAstrChatText() {}

    static String selectTranslation(JsonObject translations, String language) {
        if (translations.has(language) && translations.get(language).isJsonPrimitive()) {
            return translations.get(language).getAsString();
        }
        int separator = language.indexOf('_');
        String family = separator > 0 ? language.substring(0, separator) : language;
        if (translations.has(family) && translations.get(family).isJsonPrimitive()) {
            return translations.get(family).getAsString();
        }
        return translations.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(family + "_")
                        && entry.getValue().isJsonPrimitive())
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().getAsString())
                .findFirst()
                .orElse("");
    }

    static String selectTranslation(Map<String, String> translations, String language) {
        String exact = translations.get(language);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        int separator = language.indexOf('_');
        String family = separator > 0 ? language.substring(0, separator) : language;
        String familyTranslation = translations.get(family);
        if (familyTranslation != null && !familyTranslation.isBlank()) {
            return familyTranslation;
        }
        List<String> preferredVariants = switch (language) {
            case "zh_hk", "zh_mo" -> List.of("zh_tw", "zh_cn");
            case "zh_sg", "zh_my" -> List.of("zh_cn", "zh_tw");
            default -> List.of();
        };
        for (String preferred : preferredVariants) {
            String preferredTranslation = translations.get(preferred);
            if (preferredTranslation != null && !preferredTranslation.isBlank()) {
                return preferredTranslation;
            }
        }
        return translations.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(family + "_"))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    static boolean sameSignText(String original, String translated) {
        return normalizeSignText(original).equals(normalizeSignText(translated));
    }

    static String normalizeSignText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int last = lines.length - 1;
        while (last >= 0 && lines[last].strip().isEmpty()) {
            last--;
        }
        if (last < 0) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index <= last; index++) {
            if (index > 0) {
                normalized.append('\n');
            }
            normalized.append(lines[index].strip());
        }
        return normalized.toString();
    }

    static List<String> nativeChatTargetLanguages(Iterable<String> rawLanguages) {
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        if (rawLanguages == null) {
            return List.of();
        }
        for (String rawLanguage : rawLanguages) {
            String language = normalizeNativeChatLanguage(rawLanguage);
            if (!language.isBlank()) {
                languages.add(language);
                if (languages.size() >= MAX_NATIVE_CHAT_TARGET_LANGUAGES) {
                    break;
                }
            }
        }
        return List.copyOf(languages);
    }

    static String normalizeNativeChatLanguage(String rawLanguage) {
        if (rawLanguage == null) {
            return "";
        }
        String language = rawLanguage.strip().replace('-', '_').toLowerCase(Locale.ROOT);
        return language.matches("[a-z0-9_]{2,16}") ? language : "";
    }

    static String trimNativeChatPacketText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= MAX_NATIVE_CHAT_PACKET_LENGTH) {
            return value;
        }
        int end = MAX_NATIVE_CHAT_PACKET_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    static String normalizeChatPlatform(String platform) {
        return switch (platform == null ? "" : platform.strip().toLowerCase(Locale.ROOT)) {
            case "discord", "dc" -> "discord";
            case "qq", "default", "aiocqhttp", "aqqbot", "qq_official" -> "qq";
            default -> "minecraft";
        };
    }

    static String trimSignText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', '\n').replace('\u0000', ' ').strip();
        int maxLength = MineAstrPayloads.MAX_SIGN_TRANSLATION_TEXT_LENGTH;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
