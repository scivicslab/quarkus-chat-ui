package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the idle-monitor path on {@link ChatActor}: draining a provider's autonomous
 * output as its own assistant turn (no preceding user prompt). Uses a real ActorSystem and a stub
 * provider — no external services.
 */
class ChatActorAutonomousTest {

    private ActorSystem system;
    private ActorRef<ChatActor> chatRef;
    private StubAutonomousProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        system = new ActorSystem("test-autonomous");
        provider = new StubAutonomousProvider();
        chatRef = system.actorOf("chat", new ChatActor(provider, Optional.empty()));
        chatRef.tell(a -> a.init(chatRef)).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (system != null) system.terminate();
    }

    @Test
    @DisplayName("idle monitor drains one autonomous turn: streams to SSE, records history, releases busy")
    void pollAutonomousActivity_drainsTurn() throws Exception {
        List<ChatEvent> sse = Collections.synchronizedList(new ArrayList<>());
        chatRef.tell(a -> a.setSseEmitter(sse::add)).get(5, TimeUnit.SECONDS);

        provider.queueAutonomousTurn("background job finished");

        chatRef.tell(a -> a.pollAutonomousActivity(chatRef)).get(5, TimeUnit.SECONDS);
        // Allow the managed-pool drain and the self.tell callbacks to settle.
        Thread.sleep(500);

        // The autonomous output is recorded as an assistant turn with no user prompt before it.
        List<ChatActor.HistoryEntry> hist = chatRef.ask(a -> a.getHistory(10)).get(5, TimeUnit.SECONDS);
        assertEquals(1, hist.size());
        assertEquals("assistant", hist.get(0).role());
        assertEquals("background job finished", hist.get(0).content());

        // The content was streamed to the browser as a delta.
        assertTrue(sse.stream().anyMatch(e -> "delta".equals(e.type())
                && "background job finished".equals(e.content())));

        // The session is released after the drain.
        assertFalse(chatRef.ask(ChatActor::isBusy).get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("no autonomous activity: idle monitor is a no-op (no turn, session stays idle)")
    void pollAutonomousActivity_noActivity_noop() throws Exception {
        chatRef.tell(a -> a.pollAutonomousActivity(chatRef)).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        assertEquals(0, chatRef.ask(a -> a.getHistory(10)).get(5, TimeUnit.SECONDS).size());
        assertFalse(chatRef.ask(ChatActor::isBusy).get(5, TimeUnit.SECONDS));
    }

    // ---- Stub provider that emits one autonomous turn on demand ----

    static class StubAutonomousProvider implements LlmProvider {
        private final List<String> pending = Collections.synchronizedList(new ArrayList<>());

        void queueAutonomousTurn(String content) { pending.add(content); }

        @Override public String id() { return "stub-autonomous"; }
        @Override public String displayName() { return "Stub Autonomous"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "stub-model"; }
        @Override public void setModel(String model) {}
        @Override public void sendPrompt(String prompt, String model,
                                         Consumer<ChatEvent> emitter, ProviderContext ctx) {}
        @Override public void cancel() {}
        @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }

        @Override public boolean supportsAutonomousEvents() { return true; }
        @Override public boolean hasAutonomousActivity() { return !pending.isEmpty(); }

        @Override
        public boolean drainAutonomousActivity(Consumer<ChatEvent> emitter) {
            if (pending.isEmpty()) return false;
            String content = pending.remove(0);
            emitter.accept(ChatEvent.delta(content));
            emitter.accept(ChatEvent.result(null, 0, 0, "stub-model", false));
            return true;
        }
    }
}
