package com.mineastr;

import java.util.function.LongSupplier;

/** A batch always makes progress but yields before a backlog monopolizes the server thread. */
final class MineAstrDispatchBudget {
    private final LongSupplier clock;
    private final long started, nanos;
    private final int maximum;
    private int dispatched;
    MineAstrDispatchBudget(LongSupplier clock, int maximum, long nanos) {
        this.clock=clock; this.maximum=maximum; this.nanos=nanos; started=clock.getAsLong();
    }
    boolean allowNext() {
        if (dispatched >= maximum || (dispatched > 0 && clock.getAsLong() - started >= nanos)) return false;
        dispatched++;
        return true;
    }
}
