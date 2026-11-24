package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.TurnCancel;
import com.scivicslab.chatui.core.provider.CancelScope;
import com.scivicslab.chatui.openaicompat.DisconnectDetectingServer;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page summaries of one observation end together: when one fails, when the calling thread is interrupted, when the turn
 * is cancelled; each completion's request is closed through the scope it is given.
 */
class WebPageSummarizerCancelTest {

    private static String observation(String... pageWords) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pageWords.length; i++) {
            sb.append(i + 1).append(". Title ").append(i + 1).append("\n   URL: http://x/").append(i + 1).append("\n   ")
                    .append((pageWords[i] + " ").repeat(400)).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /** A completion that waits for its scope to be cancelled, as a request waiting for a slow model does, and notes that it was. */
    private static BiFunction<String, CancelScope, String> slowCompletion(List<String> cancelled) {
        return (page, scope) -> {
            CompletableFuture<Void> ended = new CompletableFuture<>();
            CancelScope.Registration registration = scope.register(() -> ended.complete(null));
            if (registration == null) {
                cancelled.add(page.substring(0, 4));
                return null;
            }
            try {
                if (page.startsWith("BAD")) throw new IllegalStateException("this page's completion failed");
                ended.get(30, TimeUnit.SECONDS);
                cancelled.add(page.substring(0, 4));
                return null;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                registration.close();
            }
        };
    }

    @Test
    void aFailedSiblingEndsTheOthersAtOnce() throws Exception {
        List<String> cancelled = new CopyOnWriteArrayList<>();
        WebPageSummarizer summarizer = new WebPageSummarizer(slowCompletion(cancelled));
        String observation = observation("slowA", "BADxx", "slowC");

        long start = System.nanoTime();
        String result = summarizer.summarize(observation);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(observation, result, "passed through when a page's completion failed");
        assertTrue(millis < 2000, "ended after " + millis + " ms, not after the slow pages' own time");
        long until = System.currentTimeMillis() + 3000;
        while (cancelled.size() < 2 && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(2, cancelled.size(), "both slow siblings were cancelled: " + cancelled);
    }

    @Test
    void anInterruptOfTheCallerEndsAllTheCompletions() throws Exception {
        List<String> cancelled = new CopyOnWriteArrayList<>();
        WebPageSummarizer summarizer = new WebPageSummarizer(slowCompletion(cancelled));
        String observation = observation("slowA", "slowB", "slowC");
        Thread[] caller = new Thread[1];
        CompletableFuture<String> done = CompletableFuture.supplyAsync(() -> {
            caller[0] = Thread.currentThread();
            return summarizer.summarize(observation);
        }, command -> Thread.ofVirtual().start(command));
        Thread.sleep(300);
        long start = System.nanoTime();
        caller[0].interrupt();
        assertEquals(observation, done.get(3, TimeUnit.SECONDS));
        long until = System.currentTimeMillis() + 3000;
        while (cancelled.size() < 3 && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(3, cancelled.size(), "all three were cancelled: " + cancelled);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
    }

    @Test
    void aCancelOfTheTurnEndsAllTheCompletions() throws Exception {
        List<String> cancelled = new CopyOnWriteArrayList<>();
        WebPageSummarizer summarizer = new WebPageSummarizer(slowCompletion(cancelled));
        String observation = observation("slowA", "slowB");
        TurnCancel turn = new TurnCancel("turn");
        turn.begin();
        try {
            CompletableFuture<String> done = CompletableFuture.supplyAsync(() -> summarizer.summarize(observation, turn),
                    command -> Thread.ofVirtual().start(command));
            Thread.sleep(300);
            turn.cancel();
            String result = done.get(3, TimeUnit.SECONDS);   // the pages stay as they were: a cancelled call must not lose the page
            assertTrue(result.contains("Title 1") && result.contains("Title 2"), result);
            assertEquals(2, cancelled.size(), cancelled.toString());
        } finally {
            turn.close();
        }
    }

    @Test
    void theRealCompletionClosesItsConnectionWhenItsScopeIsCancelled() throws Exception {
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            OpenAiCompatProvider provider = new OpenAiCompatProvider(List.of(server.url()), "m");
            provider.getAvailableModels();
            TurnCancel scope = new TurnCancel("completion");
            scope.begin();
            try {
                CompletableFuture<String> reply = CompletableFuture.supplyAsync(
                        () -> provider.completeOutsideConversation("summarise", scope),
                        command -> Thread.ofVirtual().start(command));
                server.requestSeen().get(10, TimeUnit.SECONDS);
                Thread.sleep(200);
                long cancelledAt = System.nanoTime();
                scope.cancel();
                long closedAt = server.connectionClosed().get(2, TimeUnit.SECONDS);
                System.out.printf("cancel -> summary request closed: %.3f s%n", (closedAt - cancelledAt) / 1e9);
                assertEquals(null, reply.get(3, TimeUnit.SECONDS));
            } finally {
                scope.close();
            }
        }
    }
}
