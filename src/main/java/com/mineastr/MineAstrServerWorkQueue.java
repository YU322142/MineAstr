package com.mineastr;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 主线程上的有界、轮转工作队列；每个 Tick 共用一个预算。
 * Bounded round-robin work on the server thread, sharing one budget per tick.
 * A step must be bounded: one scan column or one atomic binding commit.
 */
final class MineAstrServerWorkQueue {
    private record Job(BooleanSupplier step, Consumer<RuntimeException> failure) {}

    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private final int capacity;

    MineAstrServerWorkQueue(int capacity) {
        this.capacity = capacity;
    }

    boolean submit(BooleanSupplier step, Consumer<RuntimeException> failure) {
        if (jobs.size() >= capacity) return false;
        jobs.addLast(new Job(step, failure));
        return true;
    }

    void tick() {
        drain(new MineAstrDispatchBudget(System::nanoTime, 256, 2_000_000L));
    }

    // Package-visible for deterministic budget/fairness tests.
    void drain(MineAstrDispatchBudget budget) {
        while (!jobs.isEmpty() && budget.allowNext()) {
            Job job = jobs.removeFirst();
            try {
                if (!job.step.getAsBoolean()) jobs.addLast(job);
            } catch (RuntimeException error) {
                job.failure.accept(error);
            }
        }
    }

    void clear() {
        jobs.clear();
    }
}
