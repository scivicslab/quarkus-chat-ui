package com.scivicslab.chatui.logging;

import com.scivicslab.pojoactor.core.ActorClosedException;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * The most recent {@code capacity} items of a stream, kept by one owner: an actor that runs a plain ring ({@link Ring}).
 * An item is {@code tell}'d, so the writer never waits and never blocks on a reader; a read is an {@code ask}, answered after
 * every item told before it, from any thread. Nothing here locks.
 *
 * @param <E> the item
 */
final class RecentRing<E> {

    /** The plain ring the owner runs. */
    static final class Ring<E> {
        private final int capacity;
        private final Deque<E> items;

        Ring(int capacity) {
            this.capacity = capacity;
            this.items = new ArrayDeque<>(capacity);
        }

        void add(E item) {
            items.addLast(item);
            while (items.size() > capacity) items.pollFirst();
        }

        /** @return the last {@code limit} items, oldest first; all of them when {@code limit} is not positive */
        List<E> recent(int limit) {
            int size = items.size();
            int skip = (limit > 0 && size > limit) ? size - limit : 0;
            List<E> out = new ArrayList<>(size - skip);
            int i = 0;
            for (E item : items) {
                if (i++ >= skip) out.add(item);
            }
            return out;
        }

        int count() { return items.size(); }

        int capacity() { return capacity; }

        void clear() { items.clear(); }
    }

    private final ActorRef<Ring<E>> owner;
    private final int capacity;

    RecentRing(String name, int capacity) {
        this.capacity = capacity;
        this.owner = new ActorRef<>(name, new Ring<>(capacity));
    }

    int capacity() { return capacity; }

    /** Adds one item; the caller does not wait. An item told after {@link #close()} is dropped. */
    void add(E item) {
        owner.tell(r -> r.add(item)).exceptionally(e -> null);
    }

    /** @return the items, oldest first, after every item told before this call */
    List<E> recent() {
        return recent(0);
    }

    /** @return the last {@code limit} items, oldest first; all of them when {@code limit} is not positive */
    List<E> recent(int limit) {
        try {
            return owner.ask(r -> r.recent(limit)).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ActorClosedException) return List.of();
            throw e;
        }
    }

    int count() {
        try {
            return owner.ask(Ring::count).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ActorClosedException) return 0;
            throw e;
        }
    }

    void clear() {
        owner.tell(Ring::clear).exceptionally(e -> null);
    }

    /** Stops the owner; later reads see nothing. */
    void close() {
        owner.close();
    }
}
