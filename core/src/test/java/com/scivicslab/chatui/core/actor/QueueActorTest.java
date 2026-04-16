package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 tests for QueueActor.
 *
 * <p>Uses a real ActorSystem with a minimal stub ChatActor to verify
 * enqueue, dequeue, cancel_and_send, and clearAgentMessages behavior.</p>
 */
class QueueActorTest {

    private ActorSystem actorSystem;
    private ActorRef<QueueActor> queueRef;
    private ActorRef<StubChatActor> chatRef;
    private QueueActor queueActor;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem("test");
        queueActor = new QueueActor();
        queueRef = actorSystem.actorOf("queue", queueActor);
        chatRef = actorSystem.actorOf("chat", new StubChatActor());
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) actorSystem.terminate();
    }

    @Test
    @DisplayName("initially empty queue")
    void initialState_empty() {
        assertEquals(0, queueActor.getQueueSize());
        assertFalse(queueActor.hasPending());
    }

    @Test
    @DisplayName("queue mode: idle ChatActor dispatches prompt immediately")
    void queueMode_idleChatActor_dispatchesImmediately() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        queueRef.tell(q -> q.enqueue(
                "Hello", "gpt-4", "queue",
                events::add, chatActorRef, "human"
        )).get(5, TimeUnit.SECONDS);

        // Give the cross-actor tell a moment to process
        Thread.sleep(200);

        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertEquals(1, prompts.size());
        assertEquals("Hello", prompts.get(0));
        // Queue is empty after immediate dispatch
        assertEquals(0, queueActor.getQueueSize());
    }

    @Test
    @DisplayName("queue mode: busy ChatActor keeps item in queue")
    void queueMode_busyChatActor_keepsInQueue() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue(
                "Hello", "gpt-4", "queue",
                events::add, chatActorRef, "human"
        )).get(5, TimeUnit.SECONDS);

        Thread.sleep(200);

        assertEquals(1, queueActor.getQueueSize());
        assertTrue(queueActor.hasPending());
        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertTrue(prompts.isEmpty());
    }

    @Test
    @DisplayName("cancel_and_send mode: adds to front of queue and cancels ChatActor")
    void cancelAndSendMode_addsToFrontAndCancels() throws Exception {
        List<ChatEvent> events1 = new ArrayList<>();
        List<ChatEvent> events2 = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        // Enqueue a regular item first
        queueRef.tell(q -> q.enqueue(
                "First", "gpt-4", "queue",
                events1::add, chatActorRef, "human"
        )).get(5, TimeUnit.SECONDS);

        // Enqueue cancel_and_send — should go to front
        queueRef.tell(q -> q.enqueue(
                "Urgent", "gpt-4", "cancel_and_send",
                events2::add, chatActorRef, "human"
        )).get(5, TimeUnit.SECONDS);

        assertEquals(2, queueActor.getQueueSize());

        // Verify the cancel_and_send emitter got the cancel info
        assertEquals(1, events2.size());
        assertTrue(events2.get(0).content().contains("cancelled"));

        // Verify cancel was called on the stub
        Boolean cancelled = chatRef.ask(StubChatActor::wasCancelled).get(5, TimeUnit.SECONDS);
        assertTrue(cancelled);
    }

    @Test
    @DisplayName("onPromptComplete dequeues and sends when ChatActor is idle")
    void onPromptComplete_dequeuesWhenIdle() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        // Make busy so enqueue does not auto-dispatch
        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue(
                "Hello", "gpt-4", "queue",
                events::add, chatActorRef, "human"
        )).get(5, TimeUnit.SECONDS);

        assertEquals(1, queueActor.getQueueSize());

        // Make idle, then signal completion
        chatRef.tell(StubChatActor::makeIdle).get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.onPromptComplete(chatActorRef)).get(5, TimeUnit.SECONDS);

        Thread.sleep(200);

        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertEquals(1, prompts.size());
        assertEquals("Hello", prompts.get(0));
    }

    @Test
    @DisplayName("multiple items dequeue in FIFO order")
    void multipleItems_dequeueInOrder() throws Exception {
        List<ChatEvent> events1 = new ArrayList<>();
        List<ChatEvent> events2 = new ArrayList<>();
        List<ChatEvent> events3 = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue("First", null, "queue", events1::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Second", null, "queue", events2::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Third", null, "queue", events3::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);

        assertEquals(3, queueActor.getQueueSize());

        chatRef.tell(StubChatActor::makeIdle).get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.onPromptComplete(chatActorRef)).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertEquals(1, prompts.size());
        assertEquals("First", prompts.get(0));
    }

    @Test
    @DisplayName("cancel_and_send goes to front of existing queue")
    void cancelAndSend_goesToFront() throws Exception {
        List<ChatEvent> eventsA = new ArrayList<>();
        List<ChatEvent> eventsB = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue("Normal", null, "queue", eventsA::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Urgent", null, "cancel_and_send", eventsB::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);

        assertEquals(2, queueActor.getQueueSize());

        chatRef.tell(StubChatActor::makeIdle).get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.onPromptComplete(chatActorRef)).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Urgent should be dequeued first
        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertEquals(1, prompts.size());
        assertEquals("Urgent", prompts.get(0));
    }

    @Test
    @DisplayName("clearAgentMessages removes only agent-sourced items, leaving human items")
    void clearAgentMessages_removesOnlyAgentItems() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue("Human question", null, "queue", events::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Agent reply A", null, "queue", events::add, chatActorRef, "agent:localhost:28010"))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Agent reply B", null, "queue", events::add, chatActorRef, "agent:localhost:28011"))
                .get(5, TimeUnit.SECONDS);

        assertEquals(3, queueActor.getQueueSize());

        queueRef.tell(QueueActor::clearAgentMessages).get(5, TimeUnit.SECONDS);

        assertEquals(1, queueActor.getQueueSize());
    }

    @Test
    @DisplayName("clearAgentMessages on all-human queue removes nothing")
    void clearAgentMessages_allHuman_removesNothing() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue("Q1", null, "queue", events::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Q2", null, "queue", events::add, chatActorRef, "human"))
                .get(5, TimeUnit.SECONDS);

        queueRef.tell(QueueActor::clearAgentMessages).get(5, TimeUnit.SECONDS);

        assertEquals(2, queueActor.getQueueSize());
    }

    // ========================================================================
    // Stub ChatActor for testing
    // ========================================================================

    /**
     * A minimal stub that extends ChatActor to allow use with ActorRef<ChatActor>.
     * Tracks received prompts and cancel calls without performing real LLM I/O.
     */
    static class StubChatActor extends ChatActor {

        private boolean stubBusy = false;
        private boolean cancelled = false;
        private final List<String> receivedPrompts = new ArrayList<>();

        StubChatActor() {
            super(new StubLlmProvider(), Optional.empty());
        }

        @Override
        public boolean isBusy() {
            return stubBusy;
        }

        @Override
        public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                                ActorRef<ChatActor> self, CompletableFuture<Void> done) {
            startPrompt(prompt, model, emitter, self, done, null);
        }

        @Override
        public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                                ActorRef<ChatActor> self, CompletableFuture<Void> done, String resultKey) {
            receivedPrompts.add(prompt);
            // Immediately complete — no real LLM call
            done.complete(null);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        public void makeBusy() { stubBusy = true; }
        public void makeIdle() { stubBusy = false; }
        public boolean wasCancelled() { return cancelled; }
        public void resetCancelled() { cancelled = false; }
        public List<String> getReceivedPrompts() { return List.copyOf(receivedPrompts); }
    }

    /**
     * Minimal LlmProvider stub required by ChatActor's constructor.
     * All methods are no-ops or return defaults.
     */
    static class StubLlmProvider implements LlmProvider {
        @Override public String id() { return "stub"; }
        @Override public String displayName() { return "Stub"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "stub-model"; }
        @Override public void setModel(String model) {}
        @Override public void sendPrompt(String prompt, String model,
                                         Consumer<ChatEvent> emitter, ProviderContext ctx) {}
        @Override public void cancel() {}
    }
}
