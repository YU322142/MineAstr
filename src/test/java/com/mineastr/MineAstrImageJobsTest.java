package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MineAstrImageJobsTest {
    @Test void remoteBodiesRemainBoundedWhileDecoderIsBusy() throws Exception {
        try (var jobs = new MineAstrImageJobs(8, 2, 1)) {
            CountDownLatch firstTwo = new CountDownLatch(2), decoding = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(5);
            AtomicInteger retained = new AtomicInteger(), maximum = new AtomicInteger();
            for (int i=0;i<5;i++) assertTrue(jobs.submit(true, () -> {
                maximum.accumulateAndGet(retained.incrementAndGet(),Math::max);firstTwo.countDown();return new byte[1];
            }, bytes -> {
                decoding.countDown();release.await();retained.decrementAndGet();
            }, exc -> fail(exc), timing -> done.countDown()));
            assertTrue(decoding.await(2,TimeUnit.SECONDS));assertTrue(firstTwo.await(2,TimeUnit.SECONDS));
            assertEquals(2,retained.get());release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(2,maximum.get());
        }
    }
    @Test void blockedDownloadsDoNotHoldAnInlineImage() throws Exception {
        try (var jobs = new MineAstrImageJobs(8, 2, 1)) {
            CountDownLatch downloading = new CountDownLatch(2), release = new CountDownLatch(1), ready = new CountDownLatch(1);
            for (int i=0;i<2;i++) assertTrue(jobs.submit(true, () -> {
                assertTrue(Thread.currentThread().isVirtual());
                downloading.countDown(); release.await(); return new byte[]{1};
            }, bytes -> {}, exc -> fail(exc), timing -> {}));
            assertTrue(downloading.await(2, TimeUnit.SECONDS));
            assertTrue(jobs.submit(false, () -> new byte[]{2}, bytes -> assertArrayEquals(new byte[]{2}, bytes), exc -> fail(exc), timing -> ready.countDown()));
            assertTrue(ready.await(2, TimeUnit.SECONDS), "Small inline image is held behind slow downloads");
            release.countDown();
        }
    }

    @Test void oneSlowUrlDoesNotHoldAnotherUrl() throws Exception {
        try (var jobs = new MineAstrImageJobs(8, 2, 1)) {
            CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), ready = new CountDownLatch(1);
            assertTrue(jobs.submit(true, () -> {blocked.countDown();release.await();return new byte[1];}, bytes -> {}, exc -> fail(exc), timing -> {}));
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            assertTrue(jobs.submit(true, () -> new byte[]{3}, bytes -> {}, exc -> fail(exc), timing -> ready.countDown()));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            release.countDown();
        }
    }

    @Test void downloadingIsParallelButBounded() throws Exception {
        try (var jobs = new MineAstrImageJobs(8, 2, 2)) {
            CountDownLatch firstTwo = new CountDownLatch(2), release = new CountDownLatch(1), done = new CountDownLatch(5);
            AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
            for (int i=0;i<5;i++) assertTrue(jobs.submit(true, () -> {
                int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);firstTwo.countDown();
                try {release.await();return new byte[1];} finally {active.decrementAndGet();}
            }, bytes -> {}, exc -> fail(exc), timing -> done.countDown()));
            assertTrue(firstTwo.await(2, TimeUnit.SECONDS));
            assertEquals(2,active.get());
            release.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));assertEquals(2,maximum.get());
        }
    }

    @Test void decodingHasItsOwnBoundWithoutHoldingDownloads() throws Exception {
        try (var jobs = new MineAstrImageJobs(8, 4, 1)) {
            CountDownLatch loaded = new CountDownLatch(4), decoding = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(4);
            AtomicInteger active = new AtomicInteger(),maximum = new AtomicInteger();
            for (int i=0;i<4;i++) assertTrue(jobs.submit(true, () -> {loaded.countDown();return new byte[1];}, bytes -> {
                int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);decoding.countDown();
                try {release.await();} finally {active.decrementAndGet();}
            }, exc -> fail(exc), timing -> done.countDown()));
            assertTrue(decoding.await(2, TimeUnit.SECONDS));assertTrue(loaded.await(2, TimeUnit.SECONDS));
            assertEquals(1,active.get());release.countDown();assertTrue(done.await(2, TimeUnit.SECONDS));assertEquals(1,maximum.get());
        }
    }

    @Test void saturationAndCancellationReleasePendingCapacity() throws Exception {
        try (var jobs = new MineAstrImageJobs(2, 2, 1)) {
            CountDownLatch started = new CountDownLatch(2), interrupted = new CountDownLatch(2), finished = new CountDownLatch(1);
            for (int i=0;i<2;i++) assertTrue(jobs.submit(true, () -> {
                started.countDown();
                try {new CountDownLatch(1).await();return new byte[1];}
                catch(InterruptedException exc){interrupted.countDown();throw exc;}
            }, bytes -> {}, exc -> fail("Cancelled work must not mark an image failed"), timing -> {}));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertFalse(jobs.submit(false, () -> new byte[1], bytes -> {}, exc -> fail(exc), timing -> {}));
            jobs.cancelAll();assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            assertTrue(jobs.submit(false, () -> new byte[1], bytes -> {}, exc -> fail(exc), timing -> finished.countDown()));
            assertTrue(finished.await(2,TimeUnit.SECONDS));
        }
    }

    @Test void failedProcessingReleasesCapacityAndReportsCause() throws Exception {
        try (var jobs = new MineAstrImageJobs(1, 1, 1)) {
            CountDownLatch failed = new CountDownLatch(1), finished = new CountDownLatch(1);
            AtomicReference<Exception> error = new AtomicReference<>();
            assertTrue(jobs.submit(false, () -> new byte[1], bytes -> {throw new IOException("decode failure");}, exc -> {error.set(exc);failed.countDown();}, timing -> fail("Unexpected success")));
            assertTrue(failed.await(2,TimeUnit.SECONDS));assertInstanceOf(IOException.class,error.get());
            assertTrue(jobs.submit(false, () -> new byte[1], bytes -> {}, exc -> fail(exc), timing -> finished.countDown()));
            assertTrue(finished.await(2,TimeUnit.SECONDS));
        }
    }
}
