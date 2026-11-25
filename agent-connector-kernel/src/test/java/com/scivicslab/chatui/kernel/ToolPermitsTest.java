package com.scivicslab.chatui.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** The permit count: served in the order callers asked, and a caller that gave up leaves nothing behind. */
class ToolPermitsTest {

    @Test
    void permitsAreServedInTheOrderTheyWereAskedFor() {
        ToolPermits p = new ToolPermits(1);
        CompletableFuture<Void> first = p.acquire();
        CompletableFuture<Void> second = p.acquire();
        CompletableFuture<Void> third = p.acquire();
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        p.release();
        assertTrue(second.isDone());
        assertFalse(third.isDone());
        p.release();
        assertTrue(third.isDone());
        assertEquals(0, p.free());
    }

    @Test
    void aCallerThatGaveUpLeavesTheQueue_andAPermitItAlreadyHeldIsGivenBack() {
        ToolPermits p = new ToolPermits(1);
        CompletableFuture<Void> held = p.acquire();
        CompletableFuture<Void> waiting = p.acquire();

        p.abandon(waiting);
        assertEquals(0, p.waiting());
        p.abandon(held);
        assertEquals(1, p.free(), "the permit that was granted comes back");
    }
}
