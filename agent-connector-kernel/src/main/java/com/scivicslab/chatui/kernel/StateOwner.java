package com.scivicslab.chatui.kernel;

import com.scivicslab.pojoactor.core.ActorClosedException;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The handle to state that one actor owns: the state is a plain object, every use of it is a message, and any thread may send one.
 * {@link #run} and {@link #ask} are answered after every message sent before them; {@link #tell} does not wait.
 *
 * @param <S> the plain state
 */
public final class StateOwner<S> implements AutoCloseable {

    private final ActorRef<S> owner;

    /**
     * @param name  the owner's name, for logs
     * @param state the plain state the owner runs
     */
    public StateOwner(String name, S state) {
        this.owner = new ActorRef<>(name, state);
    }

    /** Asks the owner a question about the state, or has it change the state and say what happened; the caller waits for the answer. */
    public <R> R ask(Function<S, R> call) {
        try {
            return owner.ask(call).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    /** Has the owner change the state; the caller waits until it has. */
    public void run(Consumer<S> change) {
        ask(s -> {
            change.accept(s);
            return null;
        });
    }

    /** Has the owner change the state; the caller does not wait. A change sent after {@link #close()} is dropped. */
    public void tell(Consumer<S> change) {
        owner.tell(change).exceptionally(e -> null);
    }

    /**
     * Waits for the next event of a queue the state holds, at most {@code timeoutMillis}. The wait is on a future outside the
     * owner, so the owner stays free to take the next message.
     *
     * @return the event, or {@code null} when none arrived in time
     * @throws InterruptedException when the caller is interrupted, or the wait was cancelled with {@link EventQueue#cancelWaiting()}
     */
    public <E> E poll(Function<S, EventQueue<E>> queue, long timeoutMillis) throws InterruptedException {
        CompletableFuture<E> reader = ask(s -> queue.apply(s).take());
        try {
            return reader.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ask(s -> queue.apply(s).abandon(reader));
        } catch (CancellationException e) {
            throw new InterruptedException("the wait was cancelled");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CancellationException) throw new InterruptedException("the wait was cancelled");
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            ask(s -> queue.apply(s).abandon(reader));
            throw e;
        }
    }

    /** @return whether the owner still runs */
    public boolean isAlive() {
        return owner.isAlive();
    }

    /** Stops the owner. */
    @Override
    public void close() {
        try {
            owner.close();
        } catch (ActorClosedException ignored) {
            // already closed
        }
    }
}
