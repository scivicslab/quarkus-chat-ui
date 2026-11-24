package com.scivicslab.chatui.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link StateOwner} and {@link EventQueue} promise per way of sending to the owner: {@code run} and {@code ask} answer after
 * every message before them; {@code tell} keeps its order; a wait for an event is outside the owner, never loses an event to a
 * reader that left, and can be cancelled.
 */
class StateOwnerTest {

    static final class State {
        final EventQueue<String> events = new EventQueue<>();
        final List<String> log = new ArrayList<>();
        int count;
    }

    @Test
    void tellsKeepTheirOrderAndAnAskAfterThemSeesThemAll() {
        try (StateOwner<State> owner = new StateOwner<>("t-owner", new State())) {
            for (int i = 0; i < 100; i++) {
                int n = i;
                owner.tell(s -> s.log.add("e" + n));
            }
            List<String> seen = owner.ask(s -> List.copyOf(s.log));
            assertEquals(100, seen.size());
            for (int i = 0; i < 100; i++) assertEquals("e" + i, seen.get(i));
        }
    }

    @Test
    void manyThreadsChangingTheStateLoseNoChange() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(8);   // test only: callers from outside the actors
        try (StateOwner<State> owner = new StateOwner<>("t-owner-many", new State())) {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                done.add(threads.submit(() -> {
                    for (int i = 0; i < 250; i++) owner.run(s -> s.count++);
                    return null;
                }));
            }
            for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
            assertEquals(2000, (int) owner.ask(s -> s.count));
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void aWaitReceivesAnEventOfferedAfterItBegan() throws Exception {
        try (StateOwner<State> owner = new StateOwner<>("t-wait", new State())) {
            CompletableFuture<String> got = CompletableFuture.supplyAsync(() -> {
                try {
                    return owner.poll(s -> s.events, 10_000);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }, command -> Thread.ofVirtual().start(command));
            Thread.sleep(100);
            owner.run(s -> s.events.offer("hello"));
            assertEquals("hello", got.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void anEventQueuedBeforeTheWaitIsTakenAtOnceAndInOrder() throws Exception {
        try (StateOwner<State> owner = new StateOwner<>("t-queued", new State())) {
            owner.run(s -> { s.events.offer("a"); s.events.offer("b"); });
            assertEquals("a", owner.poll(s -> s.events, 1_000));
            assertEquals("b", owner.poll(s -> s.events, 1_000));
        }
    }

    @Test
    void aWaitThatTimesOutLeavesNothingBehindToTakeTheNextEvent() throws Exception {
        try (StateOwner<State> owner = new StateOwner<>("t-timeout", new State())) {
            assertNull(owner.poll(s -> s.events, 100));
            owner.run(s -> s.events.offer("later"));
            assertEquals("later", owner.poll(s -> s.events, 1_000), "the abandoned reader did not swallow the event");
        }
    }

    @Test
    void aCancelledWaitEndsAsAnInterruptionAndTheNextWaitStillWorks() throws Exception {
        try (StateOwner<State> owner = new StateOwner<>("t-cancel", new State())) {
            CompletableFuture<Object> outcome = CompletableFuture.supplyAsync(() -> {
                try {
                    return owner.poll(s -> s.events, 30_000);
                } catch (InterruptedException e) {
                    return "interrupted";
                }
            }, command -> Thread.ofVirtual().start(command));
            Thread.sleep(100);
            owner.run(s -> s.events.cancelWaiting());
            assertEquals("interrupted", outcome.get(5, TimeUnit.SECONDS));
            owner.run(s -> s.events.offer("after"));
            assertEquals("after", owner.poll(s -> s.events, 1_000));
        }
    }

    @Test
    void anExceptionInAChangeReachesTheCallerAndTheOwnerGoesOn() {
        try (StateOwner<State> owner = new StateOwner<>("t-fail", new State())) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> owner.run(s -> { throw new IllegalStateException("broken"); }));
            assertEquals("broken", e.getMessage());
            assertEquals(0, (int) owner.ask(s -> s.count));
        }
    }
}
