package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.core.actor.TurnCancel;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cancel path of the local LLM: a cancel closes the request itself, so the connection to the server closes (the
 * server stops generating), whether the request waits for its response or reads the stream; a running tool call ends the
 * same way; a cancel before a request sends nothing; a cancel after the turn is harmless. The seconds from the cancel to
 * the server seeing the connection close are printed, and each must be below one second.
 */
class CancelPathTest {

    private static final long LIMIT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static double seconds(long from, long to) {
        return (to - from) / 1e9;
    }

    private static OpenAiCompatProvider provider(String url) {
        OpenAiCompatProvider p = new OpenAiCompatProvider(List.of(url), "m");
        p.getAvailableModels();
        return p;
    }

    /** Runs sendPrompt on a thread of its own, as the turn's provider actor does, and returns its events when it ends. */
    private static CompletableFuture<List<ChatEvent>> sendInBackground(OpenAiCompatProvider p, TurnCancel scope) {
        return CompletableFuture.supplyAsync(() -> {
            List<ChatEvent> events = new ArrayList<>();
            p.sendPrompt("hello", null, events::add,
                    new ProviderContext(null, List.of(), true, () -> {}, null, true).withCancel(scope));
            return events;
        }, command -> Thread.ofVirtual().start(command));
    }

    @Test
    void aCancelWhileTheStreamIsBeingReadClosesTheConnectionWithinASecond() throws Exception {
        TurnCancel scope = new TurnCancel("test-cancel");
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.STREAM)) {
            OpenAiCompatProvider p = provider(server.url());
            scope.begin();
            CompletableFuture<List<ChatEvent>> turn = sendInBackground(p, scope);
            server.firstEventSent().get(10, TimeUnit.SECONDS);
            Thread.sleep(200);   // the client is inside its read loop

            long cancelledAt = System.nanoTime();
            scope.cancel();
            long closedAt = server.connectionClosed().get(5, TimeUnit.SECONDS);
            System.out.printf("cancel -> server saw the connection close (stream being read): %.3f s%n",
                    seconds(cancelledAt, closedAt));
            assertTrue(closedAt - cancelledAt < LIMIT_NANOS, "closed after " + seconds(cancelledAt, closedAt) + " s");

            List<ChatEvent> events = turn.get(5, TimeUnit.SECONDS);
            assertTrue(events.stream().anyMatch(e -> "error".equals(e.type()) && "Request cancelled".equals(e.content())),
                    events.toString());
        } finally {
            scope.close();
        }
    }

    @Test
    void aCancelWhileTheRequestWaitsForItsResponseClosesTheConnectionWithinASecond() throws Exception {
        TurnCancel scope = new TurnCancel("test-cancel");
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            OpenAiCompatProvider p = provider(server.url());
            scope.begin();
            CompletableFuture<List<ChatEvent>> turn = sendInBackground(p, scope);
            server.requestSeen().get(10, TimeUnit.SECONDS);
            Thread.sleep(200);

            long cancelledAt = System.nanoTime();
            scope.cancel();
            long closedAt = server.connectionClosed().get(5, TimeUnit.SECONDS);
            System.out.printf("cancel -> server saw the connection close (waiting for the response): %.3f s%n",
                    seconds(cancelledAt, closedAt));
            assertTrue(closedAt - cancelledAt < LIMIT_NANOS, "closed after " + seconds(cancelledAt, closedAt) + " s");

            List<ChatEvent> events = turn.get(5, TimeUnit.SECONDS);
            assertTrue(events.stream().anyMatch(e -> "error".equals(e.type()) && "Request cancelled".equals(e.content())),
                    events.toString());
        } finally {
            scope.close();
        }
    }

    @Test
    void aRunningToolCallEndsWithinASecondAndItsConnectionCloses() throws Exception {
        TurnCancel scope = new TurnCancel("test-cancel");
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            McpToolClient mcp = new McpToolClient(server.url() + "/mcp", "project1/chat-01");
            OpenAiCompatProvider p = new OpenAiCompatProvider(List.of(server.url()), "m", mcp);
            scope.begin();
            CompletableFuture<String> call = CompletableFuture.supplyAsync(
                    () -> p.callTool("slow", "{}", scope), command -> Thread.ofVirtual().start(command));
            server.requestSeen().get(10, TimeUnit.SECONDS);
            Thread.sleep(200);

            long cancelledAt = System.nanoTime();
            scope.cancel();
            String result = call.get(1, TimeUnit.SECONDS);
            long returnedAt = System.nanoTime();
            long closedAt = server.connectionClosed().get(5, TimeUnit.SECONDS);
            System.out.printf("cancel -> tool call returned: %.3f s; cancel -> server saw the connection close: %.3f s%n",
                    seconds(cancelledAt, returnedAt), seconds(cancelledAt, closedAt));
            assertTrue(result.startsWith("error: ") && result.contains("cancelled"), result);
            assertTrue(closedAt - cancelledAt < LIMIT_NANOS, "closed after " + seconds(cancelledAt, closedAt) + " s");
        } finally {
            scope.close();
        }
    }

    @Test
    void aCancelBeforeAnyRequestSendsNothing() throws Exception {
        TurnCancel scope = new TurnCancel("test-cancel");
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            McpToolClient mcp = new McpToolClient(server.url() + "/mcp", "project1/chat-01");
            OpenAiCompatProvider p = new OpenAiCompatProvider(List.of(server.url()), "m", mcp);
            p.getAvailableModels();
            scope.begin();
            scope.cancel();

            List<ChatEvent> events = new ArrayList<>();
            p.sendPrompt("hello", null, events::add,
                    new ProviderContext(null, List.of(), true, () -> {}, null, true).withCancel(scope));
            String tool = p.callTool("slow", "{}", scope);

            assertTrue(events.isEmpty(), "the loop stops before it sends: " + events);
            assertTrue(tool.contains("cancelled"), tool);
            assertFalse(server.requestSeen().isDone(), "no model or tool request reached the server");
            assertEquals(List.of("GET /v1/models HTTP/1.1"), server.requestLines());
        } finally {
            scope.close();
        }
    }

    @Test
    void aCancelAfterTheTurnEndedIsHarmlessAndTheNextTurnRunsAgain() throws Exception {
        TurnCancel scope = new TurnCancel("test-cancel");
        try {
            scope.begin();
            var registration = scope.register(() -> { });
            registration.close();   // the work ended
            scope.cancel();
            scope.cancel();   // twice
            assertTrue(scope.isCancelled());
            assertEquals(null, scope.register(() -> { }), "the cancelled turn takes no new work");

            scope.begin();   // the next turn
            assertFalse(scope.isCancelled());
            assertTrue(scope.register(() -> { }) != null);
        } finally {
            scope.close();
        }
    }
}
