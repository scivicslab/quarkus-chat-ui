package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.TurnReply;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reply is delivered off the thread that completes the target's answer, once, with the answer or the failure. */
class DeliverWhenDoneTest {

    @Test
    void theDeliveryRunsOnAJobNotOnTheCompletingThreadAndGetsTheAnswer() throws Exception {
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        CompletableFuture<Thread> ranOn = new CompletableFuture<>();
        AtomicReference<TurnReply> got = new AtomicReference<>();
        AskChatTool.deliverWhenDone(done, (reply, failure) -> {
            got.set(reply);
            ranOn.complete(Thread.currentThread());
        });
        TurnReply answer = new TurnReply("yes", null, "t1");
        done.complete(answer);
        Thread worker = ranOn.get(5, TimeUnit.SECONDS);
        assertNotSame(Thread.currentThread(), worker);
        assertTrue(worker.isVirtual());
        assertSame(answer, got.get());
    }

    @Test
    void aFailureOfTheTargetIsHandedOnAndADeliveryThatThrowsDoesNotBreakTheCompleter() throws Exception {
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        CompletableFuture<Throwable> seen = new CompletableFuture<>();
        AskChatTool.deliverWhenDone(done, (reply, failure) -> {
            seen.complete(failure);
            throw new IllegalStateException("queue gone");
        });
        done.completeExceptionally(new RuntimeException("turn failed"));
        assertEquals("turn failed", seen.get(5, TimeUnit.SECONDS).getMessage());
        assertTrue(done.isCompletedExceptionally());
    }
}
