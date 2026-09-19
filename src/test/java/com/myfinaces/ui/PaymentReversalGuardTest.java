package com.myfinaces.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PaymentReversalGuardTest {

    @Test
    void secondAcquireForSamePaymentIsRejected() {
        PaymentReversalGuard guard = new PaymentReversalGuard();

        assertTrue(guard.tryAcquire("pay-1"));
        assertFalse(guard.tryAcquire("pay-1"));
        assertFalse(guard.tryAcquire("pay-1"));
        assertTrue(guard.isInFlight("pay-1"));
    }

    @Test
    void acquireSucceedsAgainAfterRelease() {
        PaymentReversalGuard guard = new PaymentReversalGuard();

        assertTrue(guard.tryAcquire("pay-1"));
        guard.release("pay-1");

        assertFalse(guard.isInFlight("pay-1"));
        assertTrue(guard.tryAcquire("pay-1"));
    }

    @Test
    void differentPaymentsAcquireIndependently() {
        PaymentReversalGuard guard = new PaymentReversalGuard();

        assertTrue(guard.tryAcquire("pay-1"));
        assertTrue(guard.tryAcquire("pay-2"));

        guard.release("pay-1");
        assertFalse(guard.isInFlight("pay-1"));
        assertTrue(guard.isInFlight("pay-2"));
    }

    @Test
    void concurrentAcquiresAllowExactlyOne() throws Exception {
        PaymentReversalGuard guard = new PaymentReversalGuard();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (guard.tryAcquire("pay-1")) {
                    acquired.incrementAndGet();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, acquired.get());
    }
}
