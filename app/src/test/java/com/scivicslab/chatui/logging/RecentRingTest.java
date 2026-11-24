package com.scivicslab.chatui.logging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the owner of the recent entries promises per way of sending to it: {@code tell} (an item) keeps the order it was
 * told in and drops the oldest past the capacity; {@code ask} (a read) comes after every item told before it, from any thread.
 */
class RecentRingTest {

    @Test
    void itemsAreKeptInTheOrderTheyWereToldAndTheOldestAreDropped() {
        RecentRing<Integer> ring = new RecentRing<>("t-ring", 100);
        try {
            for (int i = 0; i < 350; i++) ring.add(i);
            List<Integer> kept = ring.recent();
            assertEquals(100, kept.size());
            assertEquals(250, kept.get(0));
            assertEquals(349, kept.get(99));
            assertEquals(List.of(347, 348, 349), ring.recent(3));
        } finally {
            ring.close();
        }
    }

    @Test
    void aReadSeesEveryItemToldBeforeItWithoutAnyWait() {
        RecentRing<Integer> ring = new RecentRing<>("t-ring-read", 1000);
        try {
            for (int round = 1; round <= 20; round++) {
                ring.add(round);
                assertEquals(round, ring.count());
                assertEquals(round, ring.recent().get(round - 1));
            }
        } finally {
            ring.close();
        }
    }

    @Test
    void manyWritersAndReadersLoseNothingBelowTheCapacity() throws Exception {
        RecentRing<Integer> ring = new RecentRing<>("t-ring-many", 10_000);
        ExecutorService threads = Executors.newFixedThreadPool(8);   // test only: callers from outside the actors
        try {
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++) {
                done.add(threads.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        ring.add(i);
                        if (i % 50 == 0) ring.recent(10);
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
            assertEquals(4000, ring.count());
        } finally {
            threads.shutdownNow();
            ring.close();
        }
    }

    @Test
    void afterTheCloseAReadSeesNothingAndAnAddIsDropped() {
        RecentRing<Integer> ring = new RecentRing<>("t-ring-closed", 10);
        ring.add(1);
        ring.close();
        ring.add(2);
        assertTrue(ring.recent().isEmpty());
        assertEquals(0, ring.count());
    }
}
