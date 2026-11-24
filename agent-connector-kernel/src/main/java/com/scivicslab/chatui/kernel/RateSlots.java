package com.scivicslab.chatui.kernel;

import com.scivicslab.pojoactor.core.ActorClosedException;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Spaces calls at least a fixed interval apart, without anyone sleeping: an owner actor holds the next time a call may
 * start; a caller asks it for a slot, and the answer arrives, as a message POJO-actor's scheduler sends, when the slot is due.
 *
 * <p>The first slot is due at once. Each later slot is due one interval after the one before it, in the order the callers asked,
 * however many ask together.</p>
 */
public final class RateSlots implements AutoCloseable {

    /** The plain state the owner runs. */
    static final class Book {
        private final long intervalMillis;
        private long nextFree;
        private long issued;

        Book(long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        /** Reserves the next slot for a caller that asks at {@code now}. @return how long until it is due, and its number */
        Slot reserve(long now) {
            long start = Math.max(now, nextFree);
            nextFree = start + intervalMillis;
            return new Slot(start - now, ++issued);
        }
    }

    /** One reserved slot: how long until it is due, and which slot it is. */
    record Slot(long delayMillis, long number) {}

    private final String name;
    private final ActorRef<Book> owner;
    private final Scheduler timer = new Scheduler(1);

    /**
     * @param name           the owner's name, for logs
     * @param intervalMillis the least time between the starts of two calls
     */
    public RateSlots(String name, long intervalMillis) {
        this.name = name;
        this.owner = new ActorRef<>(name, new Book(intervalMillis));
    }

    /**
     * Returns when this caller's slot is due. The caller waits for the message that says so; nothing sleeps.
     *
     * @throws InterruptedException when the caller is interrupted while it waits
     */
    public void awaitSlot() throws InterruptedException {
        CompletableFuture<Void> due = slot();
        try {
            due.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof ActorClosedException) return;   // closed: the call goes ahead, unspaced
            throw new IllegalStateException(e.getCause());
        }
    }

    /** @return a future that completes when this caller's slot is due; the slot is reserved now */
    public CompletableFuture<Void> slot() {
        return owner.ask(b -> b.reserve(System.currentTimeMillis())).thenCompose(slot -> {
            if (slot.delayMillis() <= 0) return CompletableFuture.<Void>completedFuture(null);
            CompletableFuture<Void> due = new CompletableFuture<>();
            timer.scheduleOnce(name + "-slot-" + slot.number(), owner, b -> due.complete(null),
                    slot.delayMillis(), TimeUnit.MILLISECONDS);
            return due;
        });
    }

    /** Stops the owner and the timer; a caller still waiting is let go. */
    @Override
    public void close() {
        timer.close();
        owner.close();
    }
}
