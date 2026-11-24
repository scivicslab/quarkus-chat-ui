package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A thread interrupted while it waits for a model or tool request closes that request's connection (a shutdown or a timeout does this). */
class InterruptClosesTheRequestTest {

    @Test
    void anInterruptedToolCallClosesItsConnection() throws Exception {
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            McpToolClient mcp = new McpToolClient(server.url() + "/mcp", "project1/chat-01");
            Thread[] caller = new Thread[1];
            CompletableFuture<McpToolClient.Result> call = CompletableFuture.supplyAsync(() -> {
                caller[0] = Thread.currentThread();
                return mcp.callTool("slow", "{}");
            }, command -> Thread.ofVirtual().start(command));
            server.requestSeen().get(10, TimeUnit.SECONDS);
            Thread.sleep(200);
            long interruptedAt = System.nanoTime();
            caller[0].interrupt();
            long closedAt = server.connectionClosed().get(2, TimeUnit.SECONDS);
            System.out.printf("interrupt -> tool connection closed: %.3f s%n", (closedAt - interruptedAt) / 1e9);
            call.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anInterruptedModelRequestClosesItsConnection() throws Exception {
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            OpenAiCompatProvider p = new OpenAiCompatProvider(List.of(server.url()), "m");
            p.getAvailableModels();
            Thread[] caller = new Thread[1];
            CompletableFuture<List<ChatEvent>> turn = CompletableFuture.supplyAsync(() -> {
                caller[0] = Thread.currentThread();
                List<ChatEvent> events = new ArrayList<>();
                p.sendPrompt("hello", null, events::add, new ProviderContext(null, List.of(), true, () -> {}, null, true));
                return events;
            }, command -> Thread.ofVirtual().start(command));
            server.requestSeen().get(10, TimeUnit.SECONDS);
            Thread.sleep(200);
            long interruptedAt = System.nanoTime();
            caller[0].interrupt();
            long closedAt = server.connectionClosed().get(2, TimeUnit.SECONDS);
            System.out.printf("interrupt -> model connection closed: %.3f s%n", (closedAt - interruptedAt) / 1e9);
            assertTrue(turn.get(5, TimeUnit.SECONDS).stream().anyMatch(e -> "error".equals(e.type())));
        }
    }
}
