package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MineAstrNativeChatTest {
    @Test
    void targetLanguagesAreNormalizedDeduplicatedAndBounded() {
        List<String> languages = MineAstrBridge.nativeChatTargetLanguages(List.of(
                "zh-CN",
                "en_us",
                "ZH_cn",
                "invalid locale!",
                "ja_jp",
                "de_de",
                "fr_fr",
                "es_es",
                "pt_br",
                "ru_ru",
                "ko_kr"));

        assertEquals(
                List.of("zh_cn", "en_us", "ja_jp", "de_de", "fr_fr", "es_es", "pt_br", "ru_ru"),
                languages);
        assertEquals(MineAstrBridge.MAX_NATIVE_CHAT_TARGET_LANGUAGES, languages.size());
    }

    @Test
    void translationSelectionPrefersExactLocaleThenLanguageFamily() {
        assertEquals(
                "繁體中文",
                MineAstrBridge.selectTranslation(
                        Map.of("zh_cn", "简体中文", "zh_tw", "繁體中文"),
                        "zh_tw"));
        assertEquals(
                "English",
                MineAstrBridge.selectTranslation(Map.of("en_us", "English"), "en_gb"));
        assertEquals(
                "繁體中文",
                MineAstrBridge.selectTranslation(
                        Map.of("zh_cn", "简体中文", "zh_tw", "繁體中文"),
                        "zh_hk"));
        assertEquals("", MineAstrBridge.selectTranslation(Map.of("ja_jp", "日本語"), "de_de"));
    }

    @Test
    void invalidOrMissingLocalesAreRejected() {
        assertEquals("", MineAstrBridge.normalizeNativeChatLanguage(null));
        assertEquals("", MineAstrBridge.normalizeNativeChatLanguage(""));
        assertEquals("", MineAstrBridge.normalizeNativeChatLanguage("../../zh_cn"));
        assertEquals("en_us", MineAstrBridge.normalizeNativeChatLanguage(" EN-US "));
    }

    @Test
    void packetTextIsBoundedWithoutSplittingEmojiSurrogates() {
        String value = "x".repeat(255) + "😀" + "tail";
        String bounded = MineAstrBridge.trimNativeChatPacketText(value);

        assertEquals(255, bounded.length());
        assertEquals("x".repeat(255), bounded);
    }
}
