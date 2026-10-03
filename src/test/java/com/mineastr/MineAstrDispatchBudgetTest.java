package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MineAstrDispatchBudgetTest {
    @Test void largeBacklogYieldsAfterEightMessagesEvenWhenClockDoesNotAdvance() {
        var clock=new AtomicLong(); var budget=new MineAstrDispatchBudget(clock::get,8,2_000_000);
        for(int i=0;i<8;i++)assertTrue(budget.allowNext());
        assertFalse(budget.allowNext());
    }
    @Test void slowDispatchYieldsOnElapsedTimeAndEachNewBatchMakesProgress() {
        var clock=new AtomicLong(); var budget=new MineAstrDispatchBudget(clock::get,8,2_000_000);
        assertTrue(budget.allowNext()); clock.addAndGet(2_000_000);
        assertFalse(budget.allowNext());
        assertTrue(new MineAstrDispatchBudget(clock::get,8,2_000_000).allowNext());
    }
}
