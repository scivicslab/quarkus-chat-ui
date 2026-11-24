package com.scivicslab.chatui.kernel;

import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * A queue of events with readers that wait for the next one, as a plain object for one owner (an actor) to run: it has no lock
 * and no thread of its own. A reader {@link #take()}s a future that is complete when an event is queued and pending when none is;
 * a reader that stops waiting {@link #abandon}s its future, so an event never goes to a reader that has left.
 *
 * <p>{@link StateOwner#poll} is the waiting side: it asks the owner for the future and waits for it outside the owner.</p>
 *
 * @param <E> the event
 */
public final class EventQueue<E> {

    private final ArrayDeque<E> events = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<E>> waiting = new ArrayDeque<>();
    private boolean interrupted;

    /** Queues an event, or hands it to the reader that has waited longest. */
    public void offer(E event) {
        CompletableFuture<E> reader = waiting.pollFirst();
        if (reader != null) reader.complete(event);
        else events.addLast(event);
    }

    /** @return a future with the oldest event, complete at once when one is queued, pending until the next when not */
    public CompletableFuture<E> take() {
        if (interrupted) return CompletableFuture.failedFuture(new CancellationException("the wait was interrupted"));
        if (!events.isEmpty()) return CompletableFuture.completedFuture(events.pollFirst());
        CompletableFuture<E> reader = new CompletableFuture<>();
        waiting.addLast(reader);
        return reader;
    }

    /**
     * Takes back a future the reader will not wait for any more.
     *
     * @return the event that reached the future in the meantime, or {@code null} when none did
     */
    public E abandon(CompletableFuture<E> reader) {
        if (reader.isDone() && !reader.isCompletedExceptionally()) return reader.getNow(null);
        waiting.remove(reader);
        return null;
    }

    /** @return the oldest queued event, or {@code null} when none is queued; never waits */
    public E pollNow() {
        return events.pollFirst();
    }

    /** @return whether no event is queued */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /** Drops every queued event; the readers that wait keep waiting. */
    public void clear() {
        events.clear();
    }

    /**
     * Interrupts the readers for good, until {@link #resume()}: every waiting reader's future fails, and so does every
     * future {@link #take()} gives from now on. An interrupt that comes before the reader has begun to wait is not lost,
     * as an interrupt of a thread is not.
     */
    public void interrupt() {
        interrupted = true;
        cancelWaiting();
    }

    /** Lets readers wait again after {@link #interrupt()}. */
    public void resume() {
        interrupted = false;
    }

    /** Ends the waits there are now: each waiting reader's future fails with a {@link CancellationException}. */
    public void cancelWaiting() {
        for (CompletableFuture<E> reader : waiting) reader.completeExceptionally(new CancellationException("the wait was cancelled"));
        waiting.clear();
    }
}
