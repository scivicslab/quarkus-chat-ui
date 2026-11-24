package com.scivicslab.chatui.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link RateSlots} promises per way of sending to its owner: a slot is asked ({@code ask}, answered in the order the
 * callers asked), and a due slot arrives as a message of the library's scheduler; the first call is not held, later calls start at
 * least an interval apart, and many callers asking together get distinct slots.
 */
class RateSlotsTest {

    @Test
    void theFirstCallIsNotHeldAndTheNextOnesStartAnIntervalApart() throws Exception {
        try (RateSlots slots = new RateSlots("t-slots", 200)) {
            long t0 = System.nanoTime();
            slots.awaitSlot();
            long first = System.nanoTime();
            slots.awaitSlot();
            long second = System.nanoTime();
            slots.awaitSlot();
            long third = System.nanoTime();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(first - t0) < 150, "the first slot is due at once");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(second - first) >= 180, "second after " + (second - first) / 1_000_000 + " ms");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(third - second) >= 180, "third after " + (third - second) / 1_000_000 + " ms");
        }
    }

    @Test
    void manyCallersAskingTogetherGetDistinctSlotsInTheOrderTheyAsked() throws Exception {
        try (RateSlots slots = new RateSlots("t-slots-many", 100)) {
            List<CompletableFuture<Void>> due = new ArrayList<>();
            List<Long> at = Collections.synchronizedList(new ArrayList<>());
            long t0 = System.nanoTime();
            for (int i = 0; i < 5; i++) {   // asked from one thread, so in this order
                due.add(slots.slot().thenRun(() -> at.add(System.nanoTime())));
            }
            for (CompletableFuture<Void> f : due) f.get(10, TimeUnit.SECONDS);
            assertEquals(5, at.size());
            for (int i = 1; i < 5; i++) {
                long gap = TimeUnit.NANOSECONDS.toMillis(at.get(i) - at.get(i - 1));
                assertTrue(gap >= 80, "slot " + i + " followed the one before after only " + gap + " ms");
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(at.get(4) - t0) >= 380, "five slots need four intervals");
        }
    }

    @Test
    void aCallerInterruptedWhileItWaitsStopsWaiting() throws Exception {
        try (RateSlots slots = new RateSlots("t-slots-interrupt", 5_000)) {
            slots.awaitSlot();   // the first is free
            Thread waiter = Thread.ofVirtual().unstarted(() -> {
                try {
                    slots.awaitSlot();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.start();
            Thread.sleep(200);
            waiter.interrupt();
            waiter.join(2_000);
            assertTrue(!waiter.isAlive(), "the interrupted caller returned");
        }
    }

    @Test
    void afterTheCloseACallGoesAheadUnspaced() throws Exception {
        RateSlots slots = new RateSlots("t-slots-closed", 5_000);
        slots.awaitSlot();
        slots.close();
        long start = System.nanoTime();
        slots.awaitSlot();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
    }
}
