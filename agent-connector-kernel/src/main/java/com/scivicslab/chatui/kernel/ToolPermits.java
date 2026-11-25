package com.scivicslab.chatui.kernel;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

/**
 * A count of permits and the callers waiting for one, as a plain object that one actor owns
 * ({@link StateOwner}): every use is a message, so the count and the queue need no lock, and the callers are served in
 * the order they asked. A caller waits on the future {@link #acquire} returns, outside the owner, so the owner stays free
 * to take the next message.
 */
public final class ToolPermits {

    private int free;
    private final ArrayDeque<CompletableFuture<Void>> waiting = new ArrayDeque<>();

    /** @param permits how many may be held at once */
    public ToolPermits(int permits) {
        this.free = permits;
    }

    /** @return a future that is complete once the caller holds a permit: at once when one is free, otherwise in turn */
    public CompletableFuture<Void> acquire() {
        if (free > 0) {
            free--;
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> turn = new CompletableFuture<>();
        waiting.addLast(turn);
        return turn;
    }

    /** Gives a permit back: to the caller that has waited longest, or to the count when none waits. */
    public void release() {
        CompletableFuture<Void> next = waiting.pollFirst();
        if (next == null) free++;
        else next.complete(null);
    }

    /**
     * A caller stops waiting for the permit {@code turn} stands for: it leaves the queue, and when it was granted the permit
     * already, the permit is given back.
     */
    public void abandon(CompletableFuture<Void> turn) {
        if (!waiting.remove(turn)) release();
    }

    /** @return how many permits are free */
    public int free() {
        return free;
    }

    /** @return how many callers wait for one */
    public int waiting() {
        return waiting.size();
    }
}
