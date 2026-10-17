package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 服务拆分后的状态隔离和生命周期约束 / State isolation and lifecycle regression. */
class MineAstrServiceOwnershipTest {
    @Test
    void nativeChatSequenceIsBoundedAndIndependentAcrossServiceInstances() {
        MineAstrBridgeContext context = disconnectedContext(new AtomicInteger());
        MineAstrNativeChatService first = new MineAstrNativeChatService(context);
        MineAstrNativeChatService second = new MineAstrNativeChatService(context);

        assertEquals(-1L, first.reserveNativeChatSequence(true));
        for (int sequence = 0; sequence < MineAstrLimits.MAX_PENDING_NATIVE_CHATS; sequence++) {
            assertEquals(sequence, first.reserveNativeChatSequence(false));
        }
        assertEquals(-1L, first.reserveNativeChatSequence(false));
        assertEquals(-1L, second.reserveNativeChatSequence(true));
        assertEquals(0L, second.reserveNativeChatSequence(false));

        first.resetOrder(true);
        assertEquals(-1L, first.reserveNativeChatSequence(true));
        assertEquals(0L, first.reserveNativeChatSequence(false));
        assertEquals(1L, second.reserveNativeChatSequence(false));
    }

    @Test
    void playerLogoutClearsOnlyItsOwnersRateWindow() {
        MineAstrBridgeContext context = disconnectedContext(new AtomicInteger());
        MineAstrNativeChatService first = new MineAstrNativeChatService(context);
        MineAstrNativeChatService second = new MineAstrNativeChatService(context);
        UUID player = UUID.randomUUID();
        for (int request = 0; request < MineAstrLimits.MAX_NATIVE_CHAT_RATE; request++) {
            assertTrue(first.allowNativeChatRate(player));
            assertTrue(second.allowNativeChatRate(player));
        }
        assertFalse(first.allowNativeChatRate(player));
        first.unregisterPlayer(player);
        assertTrue(first.allowNativeChatRate(player));
        assertFalse(second.allowNativeChatRate(player));
    }

    @Test
    void signInvalidationReadsCurrentLifecycleAndDoesNotInvalidateAnotherInstance() {
        AtomicInteger serverReads = new AtomicInteger();
        MineAstrBridgeContext context = disconnectedContext(serverReads);
        MineAstrSignService first = new MineAstrSignService(context);
        MineAstrSignService second = new MineAstrSignService(context);
        assertEquals(0, serverReads.get());
        long revision = first.currentSignTranslationCacheRevision();

        first.clearAllSignTranslations();
        first.clearAllSignTranslations();

        assertFalse(first.isCurrentSignTranslationCacheRevision(revision));
        assertTrue(second.isCurrentSignTranslationCacheRevision(revision));
        assertEquals(2, serverReads.get());
    }

    private static MineAstrBridgeContext disconnectedContext(AtomicInteger serverReads) {
        return (MineAstrBridgeContext) Proxy.newProxyInstance(
                MineAstrBridgeContext.class.getClassLoader(),
                new Class<?>[]{MineAstrBridgeContext.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("server")) {
                        serverReads.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError("Unexpected transport access: " + method.getName());
                });
    }
}
