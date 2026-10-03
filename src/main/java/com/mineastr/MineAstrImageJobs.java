package com.mineastr;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Each image owns a virtual thread; network waits never hold decoding permits. */
final class MineAstrImageJobs implements AutoCloseable {
    @FunctionalInterface interface Loader { byte[] load() throws Exception; }
    @FunctionalInterface interface Processor { void process(byte[] bytes) throws Exception; }
    record Timing(double downloadQueueSeconds, double loadSeconds, double decodeQueueSeconds, double prepareSeconds) {}

    private final ExecutorService threads = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("MineAstr-ChatImage-", 0).factory());
    private final Semaphore pending;
    // Keep a remote permit until processing finishes, bounding retained downloaded bodies.
    private final Semaphore downloads;
    private final Semaphore decoding;
    private final Set<FutureTask<Timing>> jobs = ConcurrentHashMap.newKeySet();

    MineAstrImageJobs(int maxPending, int maxDownloads, int maxDecoding) {
        if (maxPending < 1 || maxDownloads < 1 || maxDecoding < 1) throw new IllegalArgumentException("Positive limits required");
        pending = new Semaphore(maxPending);
        downloads = new Semaphore(maxDownloads, true);
        decoding = new Semaphore(maxDecoding, true);
    }

    boolean submit(boolean remote, Loader loader, Processor processor,
            Consumer<Exception> failure, Consumer<Timing> completed) {
        if (!pending.tryAcquire()) return false;
        long submitted = System.nanoTime();
        FutureTask<Timing> task = new FutureTask<>(() -> {
            if (remote) downloads.acquire();
            try {
                long loadStarted = System.nanoTime();
                byte[] bytes = loader.load();
                long loaded = System.nanoTime();
                decoding.acquire();
                long decodeStarted = System.nanoTime();
                try { processor.process(bytes); }
                finally { decoding.release(); }
                long prepared = System.nanoTime();
                return new Timing((loadStarted-submitted)/1e9, (loaded-loadStarted)/1e9,
                        (decodeStarted-loaded)/1e9, (prepared-decodeStarted)/1e9);
            } finally { if (remote) downloads.release(); }
        }) {
            @Override protected void done() {
                jobs.remove(this);
                pending.release();
                if (isCancelled()) return;
                try { completed.accept(get()); }
                catch (InterruptedException exc) { Thread.currentThread().interrupt(); }
                catch (ExecutionException exc) {
                    Throwable cause = exc.getCause();
                    failure.accept(cause instanceof Exception exception ? exception : new RuntimeException(cause));
                }
            }
        };
        jobs.add(task);
        try { threads.execute(task); }
        catch (RejectedExecutionException exc) { task.cancel(false); return false; }
        return true;
    }

    void cancelAll() { for (var job : Set.copyOf(jobs)) job.cancel(true); }
    @Override public void close() { cancelAll(); threads.shutdownNow(); }
}
