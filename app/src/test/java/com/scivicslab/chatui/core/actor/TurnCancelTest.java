package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.CancelScope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link TurnCancel} promises per way of sending to its owner actor: {@code ask} (register, cancel, begin, isCancelled)
 * answers after every earlier message; {@code tell} (the unregistration) is applied in order; every canceller runs once, on the
 * owner, and a cancel does not wait for the work it ends.
 */
class TurnCancelTest {

    @Test
    void cancelRunsEveryRegisteredCancellerOnceAndReturnsAfterTheyHaveRun() {
        TurnCancel scope = new TurnCancel("t1");
        try {
            scope.begin();
            List<String> ran = new CopyOnWriteArrayList<>();
            scope.register(() -> ran.add("a"));
            scope.register(() -> ran.add("b"));
            scope.cancel();
            assertEquals(2, ran.size(), "both ran before cancel() returned");
            scope.cancel();
            assertEquals(2, ran.size(), "a second cancel runs none again");
        } finally {
            scope.close();
        }
    }

    @Test
    void aCancellerThatWasUnregisteredBeforeTheCancelDoesNotRun() {
        TurnCancel scope = new TurnCancel("t2");
        try {
            scope.begin();
            List<String> ran = new ArrayList<>();
            CancelScope.Registration finished = scope.register(() -> ran.add("finished work"));
            CancelScope.Registration running = scope.register(() -> ran.add("running work"));
            assertNotNull(running);
            finished.close();   // told: applied before the cancel, which is asked after it
            scope.cancel();
            assertEquals(List.of("running work"), ran);
        } finally {
            scope.close();
        }
    }

    @Test
    void registeringAfterTheCancelIsRefusedAndABeginAcceptsWorkAgain() {
        TurnCancel scope = new TurnCancel("t3");
        try {
            scope.begin();
            scope.cancel();
            assertTrue(scope.isCancelled());
            assertNull(scope.register(() -> { }));
            scope.begin();
            assertFalse(scope.isCancelled());
            assertNotNull(scope.register(() -> { }));
        } finally {
            scope.close();
        }
    }

    @Test
    void aCancellerThatThrowsDoesNotStopTheOthers() {
        TurnCancel scope = new TurnCancel("t4");
        try {
            scope.begin();
            List<String> ran = new CopyOnWriteArrayList<>();
            scope.register(() -> { throw new IllegalStateException("broken"); });
            scope.register(() -> ran.add("second"));
            scope.cancel();
            assertEquals(List.of("second"), ran);
        } finally {
            scope.close();
        }
    }

    @Test
    void aCancelDoesNotWaitForTheWorkItEnds() throws Exception {
        TurnCancel scope = new TurnCancel("t5");
        try {
            scope.begin();
            java.util.concurrent.CompletableFuture<Void> work = new java.util.concurrent.CompletableFuture<>();
            scope.register(() -> work.complete(null));
            // The work is still "running" (not yet ended): the cancel is a message to the owner, which only runs the canceller.
            long start = System.nanoTime();
            scope.cancel();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
            work.get(1, TimeUnit.SECONDS);
        } finally {
            scope.close();
        }
    }

    @Test
    void afterTheOwnerIsClosedACancelIsHarmlessAndNothingIsCancelled() {
        TurnCancel scope = new TurnCancel("t6");
        scope.close();
        scope.cancel();
        assertFalse(scope.isCancelled());
    }
}
