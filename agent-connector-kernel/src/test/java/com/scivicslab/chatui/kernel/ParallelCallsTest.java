package com.scivicslab.chatui.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.CancelScope;
import com.scivicslab.chatui.kernel.ParallelCalls.Call;
import com.scivicslab.chatui.kernel.ParallelCalls.Outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The guarantees of {@link ParallelCalls}, pinned as the actor guarantees are: outcomes in call order whichever call ends
 * first, at most {@code perResponse} calls at once with an actor made for a call only when its turn comes, a count of
 * calls at once across responses, and a cancelled turn that starts nothing more and stops waiting.
 */
class ParallelCallsTest {

    /** A scope a test cancels by hand. */
    static final class TestScope implements CancelScope {
        private final List<Runnable> cancellers = new ArrayList<>();
        private boolean cancelled;

        @Override public synchronized Registration register(Runnable canceller) {
            if (cancelled) return null;
            cancellers.add(canceller);
            return () -> { synchronized (this) { cancellers.remove(canceller); } };
        }

        @Override public synchronized boolean isCancelled() { return cancelled; }

        void cancel() {
            List<Runnable> toRun;
            synchronized (this) {
                cancelled = true;
                toRun = new ArrayList<>(cancellers);
            }
            toRun.forEach(Runnable::run);
        }
    }

    /** Runs {@code work} on a thread of its own, as a conversation's turn runs apart from the test. */
    private static <T> CompletableFuture<T> apart(java.util.function.Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try { result.complete(work.get()); } catch (Throwable t) { result.completeExceptionally(t); }
        });
        return result;
    }

    private static StateOwner<ToolPermits> permits(int n) {
        return new StateOwner<>("test-permits", new ToolPermits(n));
    }

    @Test
    void outcomesAreInCallOrderEvenWhenALaterCallEndsFirst() throws Exception {
        CountDownLatch secondDone = new CountDownLatch(1);
        List<Call<String>> calls = List.of(
                () -> { secondDone.await(5, TimeUnit.SECONDS); return "first"; },
                () -> { try { return "second"; } finally { secondDone.countDown(); } },
                () -> "third");

        List<Outcome<String>> outcomes = ParallelCalls.run(calls, 8, permits(8), CancelScope.NEVER);

        assertEquals(List.of("first", "second", "third"), outcomes.stream().map(Outcome::value).toList());
    }

    @Test
    void aCallThatThrowsIsItsOwnOutcomeAndTheOthersAreUnaffected() {
        List<Call<String>> calls = List.of(() -> "a", () -> { throw new IllegalStateException("boom"); }, () -> "c");

        List<Outcome<String>> outcomes = ParallelCalls.run(calls, 8, permits(8), CancelScope.NEVER);

        assertEquals("a", outcomes.get(0).value());
        assertEquals("boom", outcomes.get(1).failure().getMessage());
        assertFalse(outcomes.get(1).cancelled());
        assertEquals("c", outcomes.get(2).value());
    }

    @Test
    void atMostEightRunAtOnceOfTwentyCalls_andTheyDoRunEightAtOnce() {
        AtomicInteger now = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<Call<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int index = i;
            calls.add(() -> {
                most.accumulateAndGet(now.incrementAndGet(), Math::max);
                try { Thread.sleep(150); } finally { now.decrementAndGet(); }
                return index;
            });
        }

        List<Outcome<Integer>> outcomes = ParallelCalls.run(calls, 8, permits(8), CancelScope.NEVER);

        assertEquals(8, most.get(), "eight at once, no more");
        for (int i = 0; i < 20; i++) assertEquals(i, outcomes.get(i).value(), "call order");
    }

    @Test
    void aCallsActorIsMadeOnlyWhenItsTurnComes() throws Exception {
        AtomicInteger started = new AtomicInteger();
        CountDownLatch hold = new CountDownLatch(1);
        List<Call<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) calls.add(() -> { started.incrementAndGet(); hold.await(10, TimeUnit.SECONDS); return 1; });

        CompletableFuture<List<Outcome<Integer>>> run = apart(() -> ParallelCalls.run(calls, 8, permits(8), CancelScope.NEVER));
        Thread.sleep(500);
        assertEquals(8, started.get(), "the other twelve have not begun, and have no actor yet");
        hold.countDown();
        run.get(20, TimeUnit.SECONDS);
        assertEquals(20, started.get());
    }

    @Test
    void theCountAcrossResponsesHoldsToo() throws Exception {
        StateOwner<ToolPermits> shared = permits(8);
        AtomicInteger now = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<Call<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> {
                most.accumulateAndGet(now.incrementAndGet(), Math::max);
                try { Thread.sleep(150); } finally { now.decrementAndGet(); }
                return 1;
            });
        }
        List<CompletableFuture<List<Outcome<Integer>>>> responses = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            responses.add(apart(() -> ParallelCalls.run(calls, 8, shared, CancelScope.NEVER)));
        }
        for (var r : responses) r.get(20, TimeUnit.SECONDS);

        assertEquals(8, most.get(), "three responses of eight calls: still eight at once in all");
        Thread.sleep(200);
        assertEquals(8, shared.ask(ToolPermits::free), "every permit is given back");
    }

    @Test
    void aCancelledTurnStartsNothingMoreStopsWaitingAndAsksTheRunningCallsToEnd() throws Exception {
        TestScope scope = new TestScope();
        AtomicInteger started = new AtomicInteger();
        CountDownLatch hold = new CountDownLatch(1);
        List<Call<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) calls.add(() -> { started.incrementAndGet(); hold.await(10, TimeUnit.SECONDS); return 1; });
        StateOwner<ToolPermits> shared = permits(8);
        CompletableFuture<List<Outcome<Integer>>> run = apart(() -> ParallelCalls.run(calls, 8, shared, scope));
        Thread.sleep(400);
        long cancelledAt = System.nanoTime();
        scope.cancel();

        List<Outcome<Integer>> outcomes = run.get(5, TimeUnit.SECONDS);

        assertTrue((System.nanoTime() - cancelledAt) / 1e9 < 2, "it stopped waiting at the cancel, not when the calls ended");
        assertEquals(20, outcomes.size());
        assertTrue(outcomes.stream().allMatch(Outcome::cancelled), "none had ended, none was started after the cancel");
        assertEquals(8, started.get(), "no call began after the cancel");
        hold.countDown();
        Thread.sleep(400);
        assertEquals(8, shared.ask(ToolPermits::free), "the permits of the abandoned calls come back when they end");
    }

    @Test
    void aTurnAlreadyCancelledStartsNothing() {
        TestScope scope = new TestScope();
        scope.cancel();
        AtomicInteger started = new AtomicInteger();
        List<Call<Integer>> calls = List.of(() -> started.incrementAndGet(), () -> started.incrementAndGet());

        List<Outcome<Integer>> outcomes = ParallelCalls.run(calls, 8, permits(8), scope);

        assertEquals(0, started.get());
        assertTrue(outcomes.stream().allMatch(Outcome::cancelled));
    }
}
