package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class MineAstrLoginTaskTest {
    @Test
    void boundLoginWaitsForAsyncDecisionAndResumesOnServerThread() {
        var decision = new CompletableFuture<MineAstrBridge.LoginCheckResult>();
        var mainThread = new ArrayDeque<Runnable>();
        var prepared = new AtomicBoolean();
        var finished = new AtomicInteger();
        List<Component> denied = new ArrayList<>();
        var task = new MineAstrLoginTask(() -> prepared.set(true), () -> decision,
                mainThread::add, () -> true, denied::add, finished::incrementAndGet);
        task.start(packet -> fail("Login checks require no client payload"));
        assertTrue(prepared.get());
        assertEquals(0, finished.get());
        decision.complete(new MineAstrBridge.LoginCheckResult(true, "", "", ""));
        assertEquals(0, finished.get());
        mainThread.remove().run();
        assertEquals(1, finished.get());
        assertTrue(denied.isEmpty());
    }

    @Test
    void unboundPlayerIsDisconnectedBeforeWorldEntryWithBindingCode() {
        var decision = new CompletableFuture<MineAstrBridge.LoginCheckResult>();
        var finished = new AtomicInteger();
        List<Component> denied = new ArrayList<>();
        var task = new MineAstrLoginTask(() -> {}, () -> decision, Runnable::run,
                () -> true, denied::add, finished::incrementAndGet);
        task.start(packet -> {});
        decision.complete(new MineAstrBridge.LoginCheckResult(false, "bind first",
                "disconnect.mineastr.login.not_bound", "123456"));
        assertEquals(0, finished.get());
        assertEquals(1, denied.size());
        assertTrue(denied.getFirst().getString().contains("123456"));
    }

    @Test
    void asyncErrorCannotAdvanceConfigurationAndClosedConnectionIsIgnored() {
        var decision = new CompletableFuture<MineAstrBridge.LoginCheckResult>();
        var mainThread = new ArrayDeque<Runnable>();
        var connected = new AtomicBoolean(true);
        var finished = new AtomicInteger();
        List<Component> denied = new ArrayList<>();
        var task = new MineAstrLoginTask(() -> {}, () -> decision, mainThread::add,
                connected::get, denied::add, finished::incrementAndGet);
        task.start(packet -> {});
        decision.completeExceptionally(new IllegalStateException("check failed"));
        mainThread.remove().run();
        assertEquals(1, denied.size());
        assertEquals(0, finished.get());
        var late = new MineAstrLoginTask(() -> {},
                () -> CompletableFuture.completedFuture(new MineAstrBridge.LoginCheckResult(true, "", "", "")),
                mainThread::add, connected::get, denied::add, finished::incrementAndGet);
        late.start(packet -> {});
        connected.set(false);
        mainThread.remove().run();
        assertEquals(0, finished.get());
        assertEquals(1, denied.size());
    }
}
