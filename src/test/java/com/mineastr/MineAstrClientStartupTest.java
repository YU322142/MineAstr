package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.nio.file.Path;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.Test;

class MineAstrClientStartupTest {
    @Test
    void loadingScreenTickDoesNotReadUnloadedConfig() {
        assertFalse(MineAstrClientConfig.isLoaded());
        assertThrows(IllegalStateException.class,
                () -> MineAstrClientConfig.GAME_TRANSLATIONS_ENABLED.getAsBoolean());
        assertDoesNotThrow(() -> MineAstrClient.onClientTick(null));
    }

    @Test
    void optionalClientApisAreSafeBeforeConfigLoads() {
        assertFalse(MineAstrClientConfig.isLoaded());
        assertFalse(MineAstrClientConfig.receivesBotImages());
        assertFalse(MineAstrClient.areFloatingTranslationOverlaysEnabled());
        assertTrue(MineAstrClient.shouldShowOriginalTranslatedMessages());
        assertEquals(8.0, MineAstrClient.floatingTranslationMaxDistance());
        assertEquals(1.0F, MineAstrClient.floatingTranslationScale());
        assertDoesNotThrow(MineAstrClient::sendTranslationPreferences);
        assertDoesNotThrow(MineAstrClient::sendBotImagePreferences);
    }

    @Test
    void loadedPreferencesReplaceStartupFallbacksAndUnloadingIsSafe() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        MineAstrClientConfig.SPEC.correct(config);
        config.set("showOriginalTranslatedMessages", false);
        config.set("signTranslationsEnabled", true);
        config.set("signTranslationMaxDistance", 12);
        config.set("signTranslationScale", 1.5);
        config.set("receiveImageMessages", true);
        config.set("acceptBotImages", false);
        // ILoadedConfig is sealed; use FML's real in-memory loaded-config record.
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        var loaded = (IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null);
        try {
            MineAstrClientConfig.SPEC.acceptConfig(loaded);
            assertTrue(MineAstrClientConfig.isLoaded());
            assertFalse(MineAstrClient.shouldShowOriginalTranslatedMessages());
            assertTrue(MineAstrClient.areFloatingTranslationOverlaysEnabled());
            assertEquals(12.0, MineAstrClient.floatingTranslationMaxDistance());
            assertEquals(1.5F, MineAstrClient.floatingTranslationScale());
            assertFalse(MineAstrClientConfig.receivesBotImages());
        } finally {
            MineAstrClientConfig.SPEC.acceptConfig(null);
        }
        assertFalse(MineAstrClientConfig.isLoaded());
        assertDoesNotThrow(() -> MineAstrClient.onClientTick(null));
    }
}
