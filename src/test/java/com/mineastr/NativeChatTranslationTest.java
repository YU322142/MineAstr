package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeChatTranslationTest {
    @Test
    void targetLanguagesAreNormalizedDeduplicatedAndBounded() {
        assertEquals(
                List.of("zh_cn", "en_us", "ja_jp", "de_de", "fr_fr", "es_es", "ko_kr", "ru_ru"),
                NativeChatTranslation.targetLanguages(List.of(
                        " zh-CN ",
                        "zh_cn",
                        "EN_us",
                        "ja_jp",
                        "bad locale",
                        "de_de",
                        "fr_fr",
                        "es_es",
                        "ko_kr",
                        "ru_ru",
                        "pt_br")));
    }

    @Test
    void translationSelectionPrefersExactThenLanguageFamily() {
        Map<String, String> translations = Map.of(
                "zh_cn", "简体中文",
                "zh_tw", "繁體中文",
                "en_us", "English");

        assertEquals("繁體中文", NativeChatTranslation.select(translations, "zh_tw"));
        assertEquals("繁體中文", NativeChatTranslation.select(translations, "zh_hk"));
        assertEquals("", NativeChatTranslation.select(translations, "ja_jp"));
    }

    @Test
    void equivalentTextIgnoresOuterLineWhitespaceAndTrailingBlankLines() {
        assertTrue(NativeChatTranslation.sameText(" Welcome \n", "Welcome"));
    }

    @Test
    void packetTextIsBoundedWithoutSplittingEmojiSurrogates() {
        String value = "x".repeat(255) + "😀" + "tail";
        String bounded = NativeChatTranslation.packetText(value);

        assertEquals(255, bounded.length());
        assertEquals("x".repeat(255), bounded);
    }
}
