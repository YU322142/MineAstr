package com.mineastr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure helpers shared by the native Minecraft chat translation bridge. */
final class NativeChatTranslation {
    static final int MAX_TARGET_LANGUAGES = 8;
    static final int MAX_PACKET_TEXT_LENGTH = 256;

    private NativeChatTranslation() {
    }

    static List<String> targetLanguages(Iterable<String> rawLanguages) {
        Set<String> languages = new LinkedHashSet<>();
        if (rawLanguages == null) {
            return List.of();
        }
        for (String rawLanguage : rawLanguages) {
            String language = normalizeLanguage(rawLanguage);
            if (!language.isEmpty()) {
                languages.add(language);
                if (languages.size() >= MAX_TARGET_LANGUAGES) {
                    break;
                }
            }
        }
        return List.copyOf(languages);
    }

    static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String normalized = language.strip().replace('-', '_').toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_]{2,16}") ? normalized : "";
    }

    static String select(Map<String, String> translations, String rawLanguage) {
        if (translations == null || translations.isEmpty()) {
            return "";
        }
        String language = normalizeLanguage(rawLanguage);
        if (language.isEmpty()) {
            return "";
        }
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
        for (String preferred : preferredVariants(language)) {
            String preferredTranslation = translations.get(preferred);
            if (preferredTranslation != null && !preferredTranslation.isBlank()) {
                return preferredTranslation;
            }
        }
        return translations.entrySet().stream()
                .filter(entry -> normalizeLanguage(entry.getKey()).startsWith(family + "_"))
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static List<String> preferredVariants(String language) {
        return switch (language) {
            case "zh_hk", "zh_mo" -> List.of("zh_tw", "zh_cn");
            case "zh_sg", "zh_my" -> List.of("zh_cn", "zh_tw");
            default -> List.of();
        };
    }

    static boolean sameText(String first, String second) {
        return normalizeText(first).equals(normalizeText(second));
    }

    static String packetText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= MAX_PACKET_TEXT_LENGTH) {
            return value;
        }
        int end = MAX_PACKET_TEXT_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String flattened = value.replace('\r', '\n');
        List<String> normalized = new ArrayList<>();
        for (String line : flattened.split("\n", -1)) {
            normalized.add(line.strip());
        }
        while (!normalized.isEmpty() && normalized.getLast().isEmpty()) {
            normalized.removeLast();
        }
        return String.join("\n", normalized);
    }
}
