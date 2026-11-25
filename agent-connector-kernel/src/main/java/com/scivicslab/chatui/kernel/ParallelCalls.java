package com.scivicslab.chatui.kernel;

import com.scivicslab.chatui.core.provider.CancelScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Runs the calls of one response at the same time, each as a {@link BlockingJob} (a virtual thread of an actor of its
 * own: no slot of the managed thread pool, no pool or thread made here), and hands the outcomes back in call order.
 *
 * <p>Two limits keep it from loading the machine. At most {@link #PER_RESPONSE} calls of one response run at once; the
 * others wait, and an actor is made for a call only when its turn comes. And at most {@link #GLOBAL} calls run at once in
 * the whole process, whichever conversation they belong to: the permits are one actor's count ({@link ToolPermits}), asked
 * for before a call's actor is made and given back when it ends.</p>
 *
 * <p>A cancelled turn ({@link CancelScope}) starts no further call, stops waiting for those running, and asks each of
 * them to end ({@link BlockingJob#cancel}); a call that was not started, or was still running, is a cancelled outcome.</p>
 */
public final class ParallelCalls {

    /** How many calls of one response run at once. */
    public static final int PER_RESPONSE = 8;
    /** How many calls run at once in the whole process. */
    public static final int GLOBAL = 8;

    private static final StateOwner<ToolPermits> PERMITS = new StateOwner<>("tool-permits", new ToolPermits(GLOBAL));

    /** One call. */
    @FunctionalInterface
    public interface Call<R> {
        /** @return the call's value */
        R run() throws Exception;
    }

    /**
     * How one call ended.
     *
     * @param value     what it returned, when it ended normally
     * @param failure   what it threw, when it did
     * @param cancelled whether the turn was cancelled before it ended (or before it began)
     */
    public record Outcome<R>(R value, Throwable failure, boolean cancelled) {}

    private ParallelCalls() {
    }

    /** @return the outcomes of {@code calls}, in call order, run under the default limits */
    public static <R> List<Outcome<R>> run(List<Call<R>> calls, CancelScope scope) {
        return run(calls, PER_RESPONSE, PERMITS, scope);
    }

    /** @return how many permits of the process-wide count are free (for a test) */
    public static int freeGlobalPermits() {
        return PERMITS.ask(ToolPermits::free);
    }

    /**
     * @param calls       the calls, in call order
     * @param perResponse how many of them run at once
     * @param permits     the count of calls that may run at once in the whole process
     * @param scope       the turn's cancel scope
     * @return the outcomes, in call order
     */
    static <R> List<Outcome<R>> run(List<Call<R>> calls, int perResponse, StateOwner<ToolPermits> permits, CancelScope scope) {
        int n = calls.size();
        List<BlockingJob<R>> jobs = new ArrayList<>(Collections.nCopies(n, null));
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        CancelScope.Registration registration = scope.register(() -> cancelled.complete(null));
        if (registration == null) cancelled.complete(null);
        try {
            List<CompletableFuture<Void>> running = new ArrayList<>();
            for (int i = 0; i < n && !cancelled.isDone(); i++) {
                while (running.size() >= perResponse && !cancelled.isDone()) {
                    List<CompletableFuture<?>> either = new ArrayList<>(running);
                    either.add(cancelled);
                    CompletableFuture.anyOf(either.toArray(new CompletableFuture<?>[0])).get();
                    running.removeIf(CompletableFuture::isDone);
                }
                if (cancelled.isDone()) break;
                CompletableFuture<Void> turn = permits.ask(ToolPermits::acquire);
                CompletableFuture.anyOf(turn, cancelled).get();
                if (cancelled.isDone()) {
                    permits.tell(p -> p.abandon(turn));
                    break;
                }
                Call<R> call = calls.get(i);
                BlockingJob<R> job = BlockingJob.start("parallel-tool-call", call::run, null);
                job.result().whenComplete((value, failure) -> permits.tell(ToolPermits::release));
                jobs.set(i, job);
                running.add(job.result().handle((value, failure) -> null));
            }
            List<Outcome<R>> outcomes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BlockingJob<R> job = jobs.get(i);
                if (job == null) {
                    outcomes.add(new Outcome<>(null, null, true));
                    continue;
                }
                CompletableFuture.anyOf(job.result().handle((value, failure) -> null), cancelled).get();
                if (!job.result().isDone()) {
                    job.cancel();
                    outcomes.add(new Outcome<>(null, null, true));
                    continue;
                }
                try {
                    outcomes.add(new Outcome<>(job.result().get(), null, false));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause(), false));
                }
            }
            if (cancelled.isDone()) {
                for (BlockingJob<R> job : jobs) if (job != null) job.cancel();
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (BlockingJob<R> job : jobs) if (job != null) job.cancel();
            List<Outcome<R>> outcomes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) outcomes.add(new Outcome<>(null, null, true));
            return outcomes;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            if (registration != null) registration.close();
        }
    }
}
