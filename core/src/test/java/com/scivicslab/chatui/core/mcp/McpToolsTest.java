package com.scivicslab.chatui.core.mcp;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.LlmConsoleActorSystem;
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
    private McpTools mcpTools;
    private StubProvider provider;
    private List<ChatEvent> emittedEvents;

    @BeforeEach
    void setUp() throws Exception {
        provider = new StubProvider();
        actorSystem = new ActorSystem("mcp-test");
        ChatActor chatActor = new ChatActor(provider, Optional.of("test-key"));
        chatRef = actorSystem.actorOf("chat", chatActor);

        // Build a stub LlmConsoleActorSystem that returns our chatRef
        var stubActorSystem = new StubActorSystem(chatRef, provider);

        // Build a stub ChatResource that captures emitted SSE events
        emittedEvents = new ArrayList<>();
        var stubChatResource = new StubChatResource(emittedEvents);

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
    @DisplayName("sendPrompt")
    class SendPromptTests {

        @Test
        @DisplayName("returns LLM response on success")
        void successfulPrompt() {
            provider.setResponseText("Hello from LLM");

            String result = mcpTools.sendPrompt("test prompt", "", null);

            assertEquals("Hello from LLM", result);
        }

        @Test
        @DisplayName("uses specified model when provided")
        void usesSpecifiedModel() {
            provider.setResponseText("response");

            mcpTools.sendPrompt("test", "other-model", null);

            assertEquals("other-model", provider.lastModel);
        }

        @Test
        @DisplayName("uses current model when model is blank")
        void usesCurrentModelWhenBlank() {
            provider.setResponseText("response");

            mcpTools.sendPrompt("test", "", null);

            assertEquals("stub-model", provider.lastModel);
        }

        @Test
        @DisplayName("emits SSE info event with caller label")
        void emitsInfoEventWithCaller() {
            provider.setResponseText("response");

            mcpTools.sendPrompt("hello", "", "agent-X");

            boolean hasInfo = emittedEvents.stream()
                    .anyMatch(e -> "info".equals(e.type())
                            && e.content().contains("[MCP from agent-X]")
                            && e.content().contains("hello"));
            assertTrue(hasInfo, "Expected info event with caller label");
        }

        @Test
        @DisplayName("emits SSE info event without caller when null")
        void emitsInfoEventWithoutCaller() {
            provider.setResponseText("response");

            mcpTools.sendPrompt("hello", "", null);

            boolean hasInfo = emittedEvents.stream()
                    .anyMatch(e -> "info".equals(e.type())
                            && e.content().contains("[MCP]")
                            && e.content().contains("hello"));
            assertTrue(hasInfo, "Expected info event without caller label");
        }

        @Test
        @DisplayName("returns error text when provider emits error")
        void returnsErrorFromProvider() {
            provider.setErrorText("Something went wrong");

            String result = mcpTools.sendPrompt("test", "", null);

            assertTrue(result.contains("[ERROR] Something went wrong"));
        }

        @Test
        @DisplayName("returns busy error when actor is already processing")
        void returnsBusyError() throws Exception {
            // Make the actor busy by starting a prompt that never completes
            provider.setHang(true);
            chatRef.tell(a -> a.startPrompt("hang", "stub-model",
                    e -> {}, chatRef, new CompletableFuture<>()));
            Thread.sleep(100); // let the virtual thread start

            String result = mcpTools.sendPrompt("second prompt", "", null);

            assertEquals("Error: LLM is currently processing another prompt. Try again later.", result);

            // Clean up: cancel the hanging prompt
            chatRef.tell(ChatActor::cancel);
            Thread.sleep(100);
        }
    }

    @Nested
    @DisplayName("formatCaller")
    class FormatCallerTests {

        @Test
        @DisplayName("non-URL caller is returned as-is with 'from' prefix")
        void nonUrlCaller() {
            provider.setResponseText("ok");

            mcpTools.sendPrompt("test", "", "agent-A");

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "info".equals(e.type())
                            && e.content().contains(" from agent-A"));
            assertTrue(found);
        }

        @Test
        @DisplayName("null caller produces no label")
        void nullCaller() {
            provider.setResponseText("ok");

            mcpTools.sendPrompt("test", "", null);

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "info".equals(e.type())
                            && e.content().equals("[MCP] test"));
            assertTrue(found);
        }

        @Test
        @DisplayName("blank caller produces no label")
        void blankCaller() {
            provider.setResponseText("ok");

            mcpTools.sendPrompt("test", "", "  ");

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "info".equals(e.type())
                            && e.content().equals("[MCP] test"));
            assertTrue(found);
        }

        @Test
        @DisplayName("unreachable URL falls back to raw URL")
        void unreachableUrlFallback() {
            provider.setResponseText("ok");

            mcpTools.sendPrompt("test", "", "http://127.0.0.1:1/no-server");

            boolean found = emittedEvents.stream()
                    .anyMatch(e -> "info".equals(e.type())
                            && e.content().contains(" from http://127.0.0.1:1/no-server"));
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
     * Stub LlmConsoleActorSystem that wraps a pre-built ActorRef.
     */
    static class StubActorSystem extends LlmConsoleActorSystem {
        private final ActorRef<ChatActor> ref;
        private final LlmProvider provider;

        StubActorSystem(ActorRef<ChatActor> ref, LlmProvider provider) {
            this.ref = ref;
            this.provider = provider;
        }

        @Override public ActorRef<ChatActor> getChatActor() { return ref; }
        @Override public LlmProvider getProvider() { return provider; }
    }

    /**
     * Stub ChatResource that captures emitted events instead of writing to SSE.
     */
    static class StubChatResource extends ChatResource {
        private final List<ChatEvent> events;

        StubChatResource(List<ChatEvent> events) { this.events = events; }

        @Override
        public void emitSse(ChatEvent event) { events.add(event); }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
