package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.CancelScope;
import com.scivicslab.pojoactor.core.ActorClosedException;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The cancel state of one conversation's turn, owned by one actor: whether the turn was cancelled, and what ends each
 * piece of blocking work (a request to a model, a call to a tool) that is in flight.
 *
 * <p>The state is a plain object ({@link State}); this class is the handle other code holds. Every method is a message to the
 * owner, so the loop that asks "was I cancelled?" and the thread that cancels never touch a shared flag, and a canceller
 * registered a moment after the cancel is refused instead of being missed. The owner's messages are all short (the
 * cancellers close a request or a stream and return), so a cancel is an {@code ask} that returns once every canceller
 * has run, and never waits for the turn's work.</p>
 */
public final class TurnCancel implements CancelScope {

    private static final Logger LOG = Logger.getLogger(TurnCancel.class.getName());

    /** The plain state the owner runs. */
    static final class State {
        private boolean cancelled;
        private long nextId = 1;
        private final Map<Long, Runnable> cancellers = new HashMap<>();

        void begin() {
            cancelled = false;
            cancellers.clear();
        }

        /** @return the registration id, or -1 when the turn is cancelled and the work must not start */
        long register(Runnable canceller) {
            if (cancelled) return -1;
            long id = nextId++;
            cancellers.put(id, canceller);
            return id;
        }

        void unregister(long id) {
            cancellers.remove(id);
        }

        void cancel() {
            cancelled = true;
            Map<Long, Runnable> running = new HashMap<>(cancellers);
            cancellers.clear();
            for (Runnable canceller : running.values()) {
                try {
                    canceller.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "a canceller failed", e);
                }
            }
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    private final ActorRef<State> owner;

    /** @param name the owner's name, for logs */
    public TurnCancel(String name) {
        this.owner = new ActorRef<>(name, new State());
    }

    /** Starts a turn: the turn is not cancelled and nothing is registered. */
    public void begin() {
        ask(State::begin);
    }

    /** Cancels the turn: later registrations are refused, and every registered canceller has run when this returns. */
    public void cancel() {
        ask(State::cancel);
    }

    @Override
    public Registration register(Runnable canceller) {
        Long id = owner.ask(s -> s.register(canceller)).join();
        if (id == null || id < 0) return null;
        return () -> owner.tell(s -> s.unregister(id)).exceptionally(e -> null);
    }

    @Override
    public boolean isCancelled() {
        try {
            return owner.ask(State::isCancelled).join();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof ActorClosedException) return false;
            throw e;
        }
    }

    /** Stops the owner with its conversation. */
    public void close() {
        owner.close();
    }

    private void ask(java.util.function.Consumer<State> call) {
        try {
            owner.ask(s -> {
                call.accept(s);
                return null;
            }).join();
        } catch (RuntimeException e) {
            if (!(e.getCause() instanceof ActorClosedException)) throw e;
        }
    }
}
