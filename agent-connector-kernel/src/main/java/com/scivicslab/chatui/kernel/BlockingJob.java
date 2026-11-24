package com.scivicslab.chatui.kernel;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * One blocking piece of work (reading a process's stream, one HTTP call, one model call) run by an
 * actor that owns it: the owner starts it, can cancel it, and reports its end through {@link #result()}.
 *
 * <p>The work runs as one message of its own actor, so on that actor's virtual thread, not on a real
 * thread and not on a thread this code starts itself. A blocked read there costs a virtual thread, which
 * is what a blocking read is for; CPU-heavy work does not belong in a job. The actor is created for the
 * job and closed when the work ends, however it ends, so a job leaves nothing running.</p>
 *
 * <p>{@link #cancel()} is sent with {@code tellNow}: the actor's one message is the work itself, still
 * running, so a message sent through its mailbox would wait behind the very work it means to stop. The
 * cancel action ends the work from outside, and for a read of a process's pipe that means destroying the
 * process: closing the pipe's stream does not wake a read that is blocked in it (measured with {@code cat}),
 * and neither does an interrupt. The work then returns or throws and the job ends.</p>
 *
 * @param <R> what the work returns
 */
public final class BlockingJob<R> {

    /**
     * The work of a job.
     *
     * @param <R> what it returns
     */
    @FunctionalInterface
    public interface Body<R> {
        /**
         * @return the work's value
         * @throws Exception whatever ends the work abnormally; it becomes the failure of {@link BlockingJob#result()}
         */
        R run() throws Exception;
    }

    /** The plain object the actor wraps: the work and the future that reports its end. */
    private static final class Work<R> {
        private final Body<R> body;
        private final Runnable canceller;
        private final CompletableFuture<R> done = new CompletableFuture<>();

        Work(Body<R> body, Runnable canceller) {
            this.body = body;
            this.canceller = canceller;
        }

        void run() {
            try {
                done.complete(body.run());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        }

        void cancel() {
            if (canceller != null) canceller.run();
        }
    }

    private final ActorRef<Work<R>> owner;
    private final Work<R> work;

    private BlockingJob(String name, Body<R> body, Runnable canceller) {
        this.work = new Work<>(body, canceller);
        this.owner = new ActorRef<>(name, work);
        // The owner is closed as the work ends. The future completes inside the actor's own message
        // (close does not wait for its own message), or at once when the work failed before it began.
        work.done.whenComplete((value, failure) -> owner.close());
        owner.tell(Work::run);
    }

    /**
     * Starts {@code body} on a new actor.
     *
     * @param name      the actor's name, for logs
     * @param body      the blocking work
     * @param canceller what {@link #cancel()} does to end the work from outside, or {@code null} for none
     * @param <R>       what the work returns
     * @return the running job
     */
    public static <R> BlockingJob<R> start(String name, Body<R> body, Runnable canceller) {
        return new BlockingJob<>(name, body, canceller);
    }

    /**
     * @return the future of the work's value; it fails with what the work threw, and completes before the
     *         actor closes, so a caller that waits on it sees the end of the work
     */
    public CompletableFuture<R> result() {
        return work.done;
    }

    /**
     * Ends the running work from outside, without waiting for it; the job then ends as it ends. A job that
     * has already ended ignores this.
     */
    public void cancel() {
        if (work.done.isDone()) return;
        owner.tellNow(Work::cancel).exceptionally(failure -> null);
    }

    /** @return whether the job's actor still runs; false once the work has ended */
    boolean ownerAlive() {
        return owner.isAlive();
    }
}
