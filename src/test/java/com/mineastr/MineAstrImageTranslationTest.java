package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class MineAstrImageTranslationTest {
    @Test void bilingualSkipRoundTripsAsSuccessWithoutAnyDisplayText() {
        var result = MineAstrPayloads.ImageTranslationResult.skippedBilingual("image-1");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MineAstrPayloads.ImageTranslationResult.CODEC.encode(buffer, result);
            var decoded = MineAstrPayloads.ImageTranslationResult.CODEC.decode(buffer);
            assertEquals(result, decoded);
            assertTrue(decoded.ok());
            assertTrue(decoded.alreadyBilingual());
            assertTrue(decoded.sourceText().isEmpty());
            assertTrue(decoded.translations().isEmpty());
            assertFalse(decoded.showOriginal());
            assertTrue(decoded.error().isEmpty());
        } finally {
            buffer.release();
        }
    }

    @Test void ordinaryTranslationAndFailureAreNotBilingualSkips() {
        assertFalse(new MineAstrPayloads.ImageTranslationResult("a", "en_us", "Vault",
                Map.of("zh_cn", "保险库"), false, true, "").alreadyBilingual());
        assertFalse(new MineAstrPayloads.ImageTranslationResult("b", "multilingual", "",
                Map.of(), false, false, "translation_failed").alreadyBilingual());
        assertFalse(new MineAstrPayloads.ImageTranslationResult("c", "multilingual", "untranslated text",
                Map.of(), false, true, "").alreadyBilingual());
    }
}
