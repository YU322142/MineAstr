package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MineAstrServerWorkQueueTest {
    @Test
    void backlogYieldsAtTimeBudgetAndResumesFairlyNextTick() {
        var queue = new MineAstrServerWorkQueue(2);
        var work = new ArrayList<String>();
        long[] clock = {0};
        queue.submit(() -> { work.add("scan"); clock[0] += 2_000_000; return false; }, error -> fail(error));
        queue.submit(() -> { work.add("bindings"); return true; }, error -> fail(error));
        assertFalse(queue.submit(() -> true, error -> fail(error)));

        queue.drain(new MineAstrDispatchBudget(() -> clock[0], 256, 2_000_000));
        assertEquals(List.of("scan"), work);
        queue.drain(new MineAstrDispatchBudget(() -> clock[0], 256, 2_000_000));
        assertEquals(List.of("scan", "bindings", "scan"), work);
    }

    @Test
    void failureIsRemovedAndDoesNotPreventOtherJobsFromCompleting() {
        var queue = new MineAstrServerWorkQueue(2);
        var failures = new AtomicInteger();
        var completed = new AtomicInteger();
        queue.submit(() -> { throw new IllegalStateException("disk failure"); }, error -> failures.incrementAndGet());
        queue.submit(() -> { completed.incrementAndGet(); return true; }, error -> fail(error));
        queue.drain(new MineAstrDispatchBudget(() -> 0, 256, 2_000_000));
        assertEquals(1, failures.get());
        assertEquals(1, completed.get());
        queue.drain(new MineAstrDispatchBudget(() -> 0, 256, 2_000_000));
        assertEquals(1, failures.get());
    }

    @Test
    void countBudgetBoundsCheapJobsAndShutdownDiscardsContinuations() {
        var queue = new MineAstrServerWorkQueue(1);
        var count = new AtomicInteger();
        queue.submit(() -> { count.incrementAndGet(); return false; }, error -> fail(error));
        queue.drain(new MineAstrDispatchBudget(() -> 0, 256, 2_000_000));
        assertEquals(256, count.get());
        queue.clear();
        queue.drain(new MineAstrDispatchBudget(() -> 0, 256, 2_000_000));
        assertEquals(256, count.get());
    }
}
