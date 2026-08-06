package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MineAstrCommandsTest {
    @Test
    void cacheStatusDistinguishesMissAutomaticManualAndBilingualSkip() {
        assertEquals(
                "commands.mineastr.sign.state.miss",
                MineAstrCommands.cacheStateKey(null));
        assertEquals(
                "commands.mineastr.sign.state.automatic",
                MineAstrCommands.cacheStateKey(entry(Map.of("zh_cn", "Welcome"), Set.of(), false)));
        assertEquals(
                "commands.mineastr.sign.state.manual",
                MineAstrCommands.cacheStateKey(entry(Map.of("zh_cn", "Corrected"), Set.of("zh_cn"), false)));
        assertEquals(
                "commands.mineastr.sign.state.bilingual",
                MineAstrCommands.cacheStateKey(entry(Map.of(), Set.of(), true)));
    }

    @Test
    void statusSourceSummaryIsSingleLineAndBounded() {
        String summary = MineAstrCommands.summarize("first\nsecond\r" + "x".repeat(100));

        assertEquals(80, summary.length());
        assertEquals("first second " + "x".repeat(64) + "...", summary);
    }

    @Test
    void clearAllInvalidatesResponsesThatStartedBeforeTheAdminCommand() {
        MineAstrBridge bridge = new MineAstrBridge();
        long requestRevision = bridge.currentSignTranslationCacheRevision();

        assertEquals(0, bridge.clearAllSignTranslations());
        assertFalse(bridge.isCurrentSignTranslationCacheRevision(requestRevision));
    }

    private static SignTranslationStore.Entry entry(
            Map<String, String> translations,
            Set<String> manualLanguages,
            boolean skipTranslation) {
        return new SignTranslationStore.Entry(
                "fingerprint",
                "source",
                translations,
                false,
                manualLanguages,
                skipTranslation,
                SignTranslationStore.CURRENT_POLICY_VERSION);
    }
}
