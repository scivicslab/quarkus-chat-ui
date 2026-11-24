package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * A map of names to values that one actor owns: the map is a plain {@link LinkedHashMap} inside the actor, and every
 * operation is a message to it, answered in the order the messages arrived. Any thread may use it; none holds a lock, and none
 * sees the map itself. {@link #keySet()}, {@link #values()} and {@link #entrySet()} return copies taken at one moment.
 *
 * <p>An operation that must read and write as one step ({@code increment a counter}, {@code put unless present}) is
 * {@link #apply} with a function that runs inside the owner.</p>
 *
 * @param <V> the value
 */
final class OwnedMap<V> {

    private final ActorRef<Map<String, V>> owner;

    OwnedMap(String name) {
        this.owner = new ActorRef<>(name, new LinkedHashMap<>());
    }

    private <R> R ask(Function<Map<String, V>, R> call) {
        try {
            return owner.ask(call).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    V get(String key) { return ask(m -> m.get(key)); }

    V put(String key, V value) { return ask(m -> m.put(key, value)); }

    V remove(String key) { return ask(m -> m.remove(key)); }

    boolean containsKey(String key) { return ask(m -> m.containsKey(key)); }

    int size() { return ask(Map::size); }

    /** @return the names, in the order they were put, as they were when this was asked */
    Set<String> keySet() { return ask(m -> new LinkedHashSet<>(m.keySet())); }

    /** @return the values, in the order they were put, as they were when this was asked */
    Collection<V> values() { return ask(m -> new ArrayList<>(m.values())); }

    /** @return the entries, in the order they were put, as they were when this was asked */
    Set<Map.Entry<String, V>> entrySet() {
        return ask(m -> {
            Set<Map.Entry<String, V>> copy = new LinkedHashSet<>();
            m.forEach((k, v) -> copy.add(Map.entry(k, v)));
            return copy;
        });
    }

    /** Runs {@code step} inside the owner, on the map itself, as one step no other operation interleaves with. */
    <R> R apply(Function<Map<String, V>, R> step) { return ask(step); }

    /** Stops the owner. */
    void close() { owner.close(); }
}
