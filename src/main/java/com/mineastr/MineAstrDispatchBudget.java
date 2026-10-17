package com.mineastr;

import java.util.function.LongSupplier;

/**
 * 每批至少推进一步，然后同时受数量和耗时限制。
 * Make at least one step of progress, then enforce both count and elapsed time.
 * This is a cooperative budget: a running step cannot be interrupted.
 */
final class MineAstrDispatchBudget {
    private final LongSupplier clock;
    private final long started;
    private final long nanos;
    private final int maximum;
    private int dispatched;

    MineAstrDispatchBudget(LongSupplier clock, int maximum, long nanos) {
        this.clock = clock;
        this.maximum = maximum;
        this.nanos = nanos;
        this.started = clock.getAsLong();
    }

    boolean allowNext() {
        if (dispatched >= maximum) return false;
        if (dispatched > 0 && clock.getAsLong() - started >= nanos) return false;
        dispatched++;
        return true;
    }
}
