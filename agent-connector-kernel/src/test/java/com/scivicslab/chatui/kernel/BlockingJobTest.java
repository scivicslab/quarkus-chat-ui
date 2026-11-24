package com.scivicslab.chatui.kernel;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the jobs rely on from POJO-actor: the work is one message of its own actor, so it runs on a
 * virtual thread of that actor and not on the caller's; {@code tellNow} reaches the actor while that one
 * message is still running; two jobs do not wait for each other; one job's work runs in order.
 */
class BlockingJobTest {

    private static Process start(String... command) throws Exception {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    @Test
    void theWorkRunsOnAVirtualThreadOfItsOwnActorNotOnTheCallers() throws Exception {
        BlockingJob<Thread> job = BlockingJob.start("job-thread", Thread::currentThread, null);
        Thread worker = job.result().get(5, TimeUnit.SECONDS);
        assertTrue(worker.isVirtual(), "the work runs on a virtual thread");
        assertNotSame(Thread.currentThread(), worker);
    }

    @Test
    void theActorClosesWhenTheWorkEnds() throws Exception {
        BlockingJob<String> job = BlockingJob.start("job-close", () -> "done", null);
        assertEquals("done", job.result().get(5, TimeUnit.SECONDS));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (job.ownerAlive() && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(job.ownerAlive(), "the job leaves no actor running");
    }

    @Test
    void whatTheWorkThrowsIsTheFailureOfTheResultAndTheActorStillCloses() throws Exception {
        BlockingJob<String> job = BlockingJob.start("job-fail", () -> { throw new java.io.IOException("boom"); }, null);
        var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> job.result().get(5, TimeUnit.SECONDS));
        assertEquals("boom", e.getCause().getMessage());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (job.ownerAlive() && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(job.ownerAlive());
    }

    @Test
    void cancelReachesTheActorWhileItsOneMessageIsStillBlockedReading() throws Exception {
        Process cat = start("cat");   // writes nothing: a read of its output blocks until the pipe closes
        try {
            BlockingJob<Integer> job = BlockingJob.start("job-cancel", () -> {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(cat.getInputStream()))) {
                    return out.readLine() == null ? -1 : 1;
                }
            }, () -> cat.destroy());
            Thread.sleep(200);   // the work is blocked in its read now
            assertFalse(job.result().isDone());
            job.cancel();        // tellNow: the mailbox is held by the work itself
            assertEquals(-1, job.result().get(5, TimeUnit.SECONDS));
        } finally {
            cat.destroyForcibly();
        }
    }

    @Test
    void aBlockedJobDoesNotHoldBackAnother() throws Exception {
        Process cat = start("cat");
        try {
            BlockingJob<Integer> blocked = BlockingJob.start("job-blocked", () -> cat.getInputStream().read(), cat::destroy);
            Thread.sleep(100);
            BlockingJob<String> other = BlockingJob.start("job-other", () -> "free", null);
            assertEquals("free", other.result().get(5, TimeUnit.SECONDS));
            blocked.cancel();
            blocked.result().get(5, TimeUnit.SECONDS);
        } finally {
            cat.destroyForcibly();
        }
    }

    @Test
    void oneJobsLinesComeInTheOrderTheProcessWroteThem() throws Exception {
        Process seq = start("sh", "-c", "i=1; while [ $i -le 300 ]; do echo $i; i=$((i+1)); done");
        List<Integer> seen = new ArrayList<>();
        BlockingJob<Void> job = BlockingJob.start("job-order", () -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(seq.getInputStream()))) {
                String line;
                while ((line = out.readLine()) != null) seen.add(Integer.parseInt(line));
            }
            return null;
        }, null);
        CompletableFuture<Void> end = job.result();
        end.get(10, TimeUnit.SECONDS);
        assertEquals(300, seen.size());
        for (int i = 0; i < 300; i++) assertEquals(i + 1, seen.get(i));
    }
}
