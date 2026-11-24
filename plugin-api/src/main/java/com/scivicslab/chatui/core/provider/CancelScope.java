package com.scivicslab.chatui.core.provider;

/**
 * What a turn can cancel while it runs: the owner of the turn's cancel state, handed to a provider so that the work it
 * starts (a request to a model, a call to a tool) can be ended from outside while it blocks.
 *
 * <p>A piece of blocking work first {@link #register registers} what ends it (it closes the request itself, so that the
 * connection closes), then starts. Registering returns {@code null} when the turn has already been cancelled: the work
 * must not start. {@link #cancel()} runs every registered canceller once. The scope has no thread and no flag of its own
 * that other threads touch: the implementation is one actor, and every method is a message to it.</p>
 */
public interface CancelScope {

    /** A registration; closing it removes the canceller once the work it covers has ended. */
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Registers what ends a piece of work that is about to start.
     *
     * @param canceller what ends the work from outside; it must be quick and must not block
     * @return the registration to close when the work ends, or {@code null} when the turn is already cancelled and the
     *         work must not start
     */
    Registration register(Runnable canceller);

    /** @return whether the turn has been cancelled and not begun again */
    boolean isCancelled();

    /** A scope that is never cancelled, for callers that have no turn to cancel. */
    CancelScope NEVER = new CancelScope() {
        @Override public Registration register(Runnable canceller) { return () -> { }; }
        @Override public boolean isCancelled() { return false; }
    };
}
