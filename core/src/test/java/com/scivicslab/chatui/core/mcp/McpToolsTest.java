package com.scivicslab.chatui.core.mcp;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.QueueActor;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.rest.ChatResource;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link McpTools}.
 * Uses a real ActorSystem with stub LlmProvider to test MCP tool behaviour
 * without CDI or Quarkus runtime.
 */
class McpToolsTest {

    private ActorSystem actorSystem;
    private ActorRef<ChatActor> chatRef;
    private ActorRef<QueueActor> queueRef;
    private McpTools mcpTools;
    private StubProvider provider;
    private List<ChatEvent> emittedEvents;

    @BeforeEach
    void setUp() throws Exception {
        provider = new StubProvider();
        actorSystem = new ActorSystem("mcp-test");
        ChatActor chatActor = new ChatActor(provider, Optional.of("test-key"));
        chatRef = actorSystem.actorOf("chat", chatActor);

        QueueActor queueActor = new QueueActor();
        queueRef = actorSystem.actorOf("queue", queueActor);
        chatRef.tell(a -> a.setQueueActor(queueRef));

        // Build a stub ChatUiActorSystem that returns our chatRef and queueRef
        var stubActorSystem = new StubActorSystem(chatRef, queueRef, provider);

        // Build a stub ChatResource that captures emitted SSE events
        emittedEvents = new ArrayList<>();
        var stubChatResource = new StubChatResource(emittedEvents, chatRef);

        // Construct McpTools and inject dependencies via reflection
        mcpTools = new McpTools();
        setField(mcpTools, "actorSystem", stubActorSystem);
        setField(mcpTools, "chatResource", stubChatResource);
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) actorSystem.terminate();
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatusTests {

        @Test
        @DisplayName("returns model, session, and busy state")
        void returnsStatus() {
            String status = mcpTools.getStatus();

            assertTrue(status.contains("model=stub-model"));
            assertTrue(status.contains("busy=false"));
        }
    }

    @Nested
    @DisplayName("listModels")
    class ListModelsTests {

        @Test
        @DisplayName("returns comma-separated model names")
        void returnsModelNames() {
            String models = mcpTools.listModels();

            assertEquals("stub-model, other-model", models);
        }
    }

    @Nested
    @DisplayName("cancelRequest")
    class CancelRequestTests {

        @Test
        @DisplayName("returns message when no request is running")
        void noRunningRequest() {
            String result = mcpTools.cancelRequest();

            assertEquals("No request is currently running.", result);
        }
    }

    @Nested
    @DisplayName("submitPrompt")
    class SubmitPromptTests {

        @Test
        @DisplayName("returns a UUID that callers use to track status and retrieve results")
        void successfulSubmit() {
            String result = mcpTools.submitPrompt("test prompt", "", null);

            assertTrue(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                    "Expected UUID, got: " + result);
        }

        @Test
        @DisplayName("emits mcp_user event with caller label and message content")
        void emitsInfoEventWithCaller() {
            mcpTools.submitPrompt("hello", "", "agent-X");

            boolean hasMcpUser = emittedEvents.stream()
                    .anyMatch(e -> "mcp_user".equals(e.type())
                            && e.content().contains("[MCP from agent-X]")
                            && e.content().contains("hello"));
            assertTrue(hasMcpUser, "Expected mcp_user event with caller label and message content");
        }

        @Test
        @DisplayName("emits mcp_user event with 'unknown' when caller is null")
        void emitsInfoEventWithoutCaller() {
            mcpTools.submitPrompt("hello", "", null);

            boolean hasMcpUser = emittedEvents.stream()
                    .anyMatch(e -> "mcp_user".equals(e.type())
                            && e.content().contains("[MCP from unknown]")
                            && e.content().contains("hello"));
            assertTrue(hasMcpUser, "Expected mcp_user event with unknown caller");
        }

        @Test
        @DisplayName("queues message when actor is busy")
        void returnsBusyError() throws Exception {
            // Make the actor busy
            provider.setHang(true);
            chatRef.tell(a -> a.startPrompt("hang", "stub-model",
                    e -> {}, chatRef, new CompletableFuture<>()));
            Thread.sleep(100);

            String result = mcpTools.submitPrompt("second prompt", "", null);

            // submitPrompt always returns a UUID regardless of busy state
            assertTrue(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                    "Expected UUID, got: " + result);

            // Clean up
            chatRef.tell(ChatActor::cancel);
            Thread.sleep(100);
        }
    }

    @Nested
    @DisplayName("formatCallerLabel")
    class FormatCallerTests {

        @Test
        @DisplayName("non-URL caller is returned as-is with message content")
        void nonUrlCaller() {
            mcpTools.submitPrompt("test", "", "agent-A");

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "mcp_user".equals(e.type())
                            && e.content().contains("[MCP from agent-A]")
                            && e.content().contains("test"));
            assertTrue(found);
        }

        @Test
        @DisplayName("null caller uses 'unknown' label")
        void nullCaller() {
            mcpTools.submitPrompt("test", "", null);

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "mcp_user".equals(e.type())
                            && e.content().contains("[MCP from unknown]"));
            assertTrue(found, "Expected mcp_user event with unknown caller");
        }

        @Test
        @DisplayName("blank caller uses 'unknown' label")
        void blankCaller() {
            mcpTools.submitPrompt("test", "", "  ");

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "mcp_user".equals(e.type())
                            && e.content().contains("[MCP from unknown]"));
            assertTrue(found, "Expected mcp_user event with unknown caller");
        }

        @Test
        @DisplayName("URL caller extracts authority (host:port) with message content")
        void unreachableUrlFallback() {
            mcpTools.submitPrompt("test", "", "http://127.0.0.1:1/no-server");

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "mcp_user".equals(e.type())
                            && e.content().contains("[MCP from 127.0.0.1:1]")
                            && e.content().contains("test"));
            assertTrue(found);
        }
    }

    // ---- Test helpers ----

    /**
     * Stub LlmProvider that synchronously emits delta/error events and completes.
     */
    static class StubProvider implements LlmProvider {
        private String responseText;
        private String errorText;
        private boolean hang;
        String lastModel;

        @Override public String id() { return "stub"; }
        @Override public String displayName() { return "Stub"; }
        @Override public List<ModelEntry> getAvailableModels() {
            return List.of(
                    new ModelEntry("stub-model", "chat", "local"),
                    new ModelEntry("other-model", "chat", "local"));
        }
        private String currentModel = "stub-model";
        @Override public String getCurrentModel() { return currentModel; }
        @Override public void setModel(String model) { this.currentModel = model; }
        @Override public void cancel() { }
        @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.DEFAULT; }

        @Override
        public void sendPrompt(String prompt, String model,
                               Consumer<ChatEvent> emitter, ProviderContext ctx) {
            lastModel = model;
            if (hang) {
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
                return;
            }
            if (errorText != null) {
                emitter.accept(ChatEvent.error(errorText));
            }
            if (responseText != null) {
                emitter.accept(ChatEvent.delta(responseText));
            }
            emitter.accept(ChatEvent.result(null, 0.0, 0));
        }

        void setResponseText(String text) { this.responseText = text; this.errorText = null; }
        void setErrorText(String text) { this.errorText = text; this.responseText = null; }
        void setHang(boolean hang) { this.hang = hang; }
    }

    /**
     * Stub ChatUiActorSystem that wraps pre-built ActorRefs.
     */
    static class StubActorSystem extends ChatUiActorSystem {
        private final ActorRef<ChatActor> chatRef;
        private final ActorRef<QueueActor> queueRef;
        private final LlmProvider provider;

        StubActorSystem(ActorRef<ChatActor> chatRef, ActorRef<QueueActor> queueRef, LlmProvider provider) {
            this.chatRef = chatRef;
            this.queueRef = queueRef;
            this.provider = provider;
        }

        @Override public ActorRef<ChatActor> getChatActor() { return chatRef; }
        @Override public ActorRef<QueueActor> getQueueActor() { return queueRef; }
        @Override public LlmProvider getProvider() { return provider; }
    }

    /**
     * Stub ChatResource that captures emitted events instead of writing to SSE.
     */
    static class StubChatResource extends ChatResource {
        private final List<ChatEvent> events;
        private final ActorRef<ChatActor> chatRef;
        private int sessionCounter = 0;

        StubChatResource(List<ChatEvent> events, ActorRef<ChatActor> chatRef) {
            this.events = events;
            this.chatRef = chatRef;
        }

        @Override
        public void emitSse(ChatEvent event) { events.add(event); }

        @Override
        public SubmitResponse submit(PromptRequest request) {
            if (request == null || request.text == null || request.text.isBlank()) {
                return new SubmitResponse(null, "error", "Empty prompt");
            }
            if (chatRef.ask(ChatActor::isBusy).join()) {
                return new SubmitResponse(null, "busy", "LLM is currently processing another prompt");
            }
            String sessionId = "session-" + (++sessionCounter);
            return new SubmitResponse(sessionId, "submitted", null);
        }

        @Override
        public StatusResponse getStatus(String sessionId) {
            return new StatusResponse(sessionId, "completed", 1.0);
        }

        @Override
        public ResultResponse getResult(String sessionId) {
            return new ResultResponse(sessionId, "Completed successfully", null);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
