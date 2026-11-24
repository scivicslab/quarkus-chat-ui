package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link OwnedMap} promises: every operation is one message to the owner, so none interleaves with another. */
class OwnedMapTest {

    @Test
    void operationsFromOneThreadTakeEffectInTheOrderTheyWereSent() {
        OwnedMap<Integer> map = new OwnedMap<>("t-order");
        try {
            for (int i = 0; i < 200; i++) map.put("k" + i, i);
            for (int i = 0; i < 200; i += 2) map.remove("k" + i);
            assertEquals(100, map.size());
            assertNull(map.get("k0"));
            assertEquals(1, map.get("k1"));
            assertFalse(map.containsKey("k2"));
            assertEquals("k1", map.keySet().iterator().next());
        } finally {
            map.close();
        }
    }

    @Test
    void aStepIsOneMessageSoManyThreadsCountWithoutALoss() throws Exception {
        OwnedMap<Integer> map = new OwnedMap<>("t-count");
        ExecutorService threads = Executors.newFixedThreadPool(8);   // test only: callers from outside the actors
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                done.add(threads.submit(() -> {
                    go.await();
                    for (int i = 0; i < 250; i++) {
                        map.apply(m -> {
                            m.put("n", m.getOrDefault("n", 0) + 1);
                            return null;
                        });
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
            assertEquals(2000, map.get("n"), "read and write in one step: no increment is lost");
        } finally {
            threads.shutdownNow();
            map.close();
        }
    }

    @Test
    void theCopiesAreTakenAtOneMomentAndDoNotChangeLater() {
        OwnedMap<String> map = new OwnedMap<>("t-copy");
        try {
            map.put("a", "1");
            var keys = map.keySet();
            var values = map.values();
            map.put("b", "2");
            assertEquals(1, keys.size());
            assertEquals(1, values.size());
            assertTrue(map.keySet().contains("b"));
        } finally {
            map.close();
        }
    }

    @Test
    void anExceptionInAStepReachesTheCallerAndLeavesTheMapUsable() {
        OwnedMap<String> map = new OwnedMap<>("t-fail");
        try {
            map.put("a", "1");
            try {
                map.apply(m -> { throw new IllegalStateException("broken step"); });
            } catch (IllegalStateException expected) {
                assertEquals("broken step", expected.getMessage());
            }
            assertEquals("1", map.get("a"));
        } finally {
            map.close();
        }
    }
}
