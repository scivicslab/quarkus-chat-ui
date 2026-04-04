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

import java.time.Instant;
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
 * enqueue, dequeue, cancel_and_send, and wait-with-timeout behavior.</p>
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
    @DisplayName("queue mode: enqueue adds item and emits info message")
    void queueMode_addsItemAndEmitsInfo() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        // Use raw ActorRef<ChatActor> via unchecked cast — StubChatActor extends ChatActor
        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        queueRef.tell(q -> q.enqueue(
                "Hello", "gpt-4", "queue", 0,
                events::add, chatActorRef, queueRef
        )).get(5, TimeUnit.SECONDS);

        assertEquals(1, queueActor.getQueueSize());
        assertTrue(queueActor.hasPending());
        assertEquals(1, events.size());
        assertEquals("info", events.get(0).type());
        assertTrue(events.get(0).content().contains("Queued"));
    }

    @Test
    @DisplayName("cancel_and_send mode: adds to front of queue and cancels ChatActor")
    void cancelAndSendMode_addsToFrontAndCancels() throws Exception {
        List<ChatEvent> events1 = new ArrayList<>();
        List<ChatEvent> events2 = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        // Enqueue a regular item first
        queueRef.tell(q -> q.enqueue(
                "First", "gpt-4", "queue", 0,
                events1::add, chatActorRef, queueRef
        )).get(5, TimeUnit.SECONDS);

        // Enqueue cancel_and_send — should go to front
        queueRef.tell(q -> q.enqueue(
                "Urgent", "gpt-4", "cancel_and_send", 0,
                events2::add, chatActorRef, queueRef
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
    @DisplayName("wait mode: enqueue adds item with timeout info")
    void waitMode_addsItemWithTimeoutInfo() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        queueRef.tell(q -> q.enqueue(
                "Wait prompt", "gpt-4", "wait", 30,
                events::add, chatActorRef, queueRef
        )).get(5, TimeUnit.SECONDS);

        assertEquals(1, queueActor.getQueueSize());
        assertEquals(1, events.size());
        assertTrue(events.get(0).content().contains("Waiting"));
        assertTrue(events.get(0).content().contains("30"));
    }

    @Test
    @DisplayName("onPromptComplete dequeues and sends when ChatActor is idle")
    void onPromptComplete_dequeuesWhenIdle() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        // Enqueue a prompt
        queueRef.tell(q -> q.enqueue(
                "Hello", "gpt-4", "queue", 0,
                events::add, chatActorRef, queueRef
        )).get(5, TimeUnit.SECONDS);

        assertEquals(1, queueActor.getQueueSize());

        // ChatActor is not busy (stub default), so onPromptComplete should trigger dequeue
        queueRef.tell(q -> q.onPromptComplete(chatActorRef)).get(5, TimeUnit.SECONDS);

        // Give the cross-actor tell a moment to process
        Thread.sleep(200);

        // The stub records prompts it received
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

        queueRef.tell(q -> q.enqueue("First", null, "queue", 0, events1::add, chatActorRef, queueRef))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Second", null, "queue", 0, events2::add, chatActorRef, queueRef))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Third", null, "queue", 0, events3::add, chatActorRef, queueRef))
                .get(5, TimeUnit.SECONDS);

        assertEquals(3, queueActor.getQueueSize());

        // Dequeue first
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

        // Make ChatActor busy so dequeue doesn't happen immediately
        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue("Normal", null, "queue", 0, eventsA::add, chatActorRef, queueRef))
                .get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.enqueue("Urgent", null, "cancel_and_send", 0, eventsB::add, chatActorRef, queueRef))
                .get(5, TimeUnit.SECONDS);

        assertEquals(2, queueActor.getQueueSize());

        // Make ChatActor idle again, then dequeue
        chatRef.tell(StubChatActor::makeIdle).get(5, TimeUnit.SECONDS);
        queueRef.tell(q -> q.onPromptComplete(chatActorRef)).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Urgent should be dequeued first
        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertEquals(1, prompts.size());
        assertEquals("Urgent", prompts.get(0));
    }

    @Test
    @DisplayName("tick does nothing when queue is empty")
    void tick_emptyQueue_doesNothing() throws Exception {
        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        // Should not throw or cause any side effects
        queueRef.tell(q -> q.tick(chatActorRef)).get(5, TimeUnit.SECONDS);

        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertTrue(prompts.isEmpty());
    }

    @Test
    @DisplayName("tick dequeues when ChatActor is idle")
    void tick_idleChatActor_dequeues() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        queueRef.tell(q -> q.enqueue("Tick test", null, "queue", 0, events::add, chatActorRef, queueRef))
                .get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.tick(chatActorRef)).get(5, TimeUnit.SECONDS);
        Thread.sleep(300);

        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertEquals(1, prompts.size());
        assertEquals("Tick test", prompts.get(0));
    }

    @Test
    @DisplayName("tick does not dequeue when ChatActor is busy")
    void tick_busyChatActor_doesNotDequeue() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.enqueue("Blocked", null, "queue", 0, events::add, chatActorRef, queueRef))
                .get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.tick(chatActorRef)).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        List<String> prompts = chatRef.ask(StubChatActor::getReceivedPrompts).get(5, TimeUnit.SECONDS);
        assertTrue(prompts.isEmpty());
        assertEquals(1, queueActor.getQueueSize());
    }

    @Test
    @DisplayName("wait mode with expired timeout cancels and emits timeout info")
    void waitMode_expiredTimeout_cancelsAndEmitsInfo() throws Exception {
        List<ChatEvent> events = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ActorRef<ChatActor> chatActorRef = (ActorRef<ChatActor>) (ActorRef<?>) chatRef;

        chatRef.tell(StubChatActor::makeBusy).get(5, TimeUnit.SECONDS);

        // Enqueue with 0-second timeout (expires immediately)
        // We use a custom QueueItem with an enqueuedAt in the past to simulate expiration
        queueRef.tell(q -> {
            // Directly add an item that is already expired (enqueuedAt 10 seconds ago)
            var item = new QueueActor.QueueItem(
                    "Timed out", null, "wait", 1,
                    Instant.now().minusSeconds(10), events::add, new CompletableFuture<>()
            );
            // Access the queue through enqueue first, then immediately tick
            q.enqueue("Timed out", null, "wait", 1, events::add, chatActorRef, queueRef);
        }).get(5, TimeUnit.SECONDS);

        // Wait a tiny bit so the 1-second timeout has clearly expired
        Thread.sleep(1100);

        // Reset cancel flag to check if tick triggers cancel
        chatRef.tell(StubChatActor::resetCancelled).get(5, TimeUnit.SECONDS);

        queueRef.tell(q -> q.tick(chatActorRef)).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Should have cancelled the current prompt
        Boolean cancelled = chatRef.ask(StubChatActor::wasCancelled).get(5, TimeUnit.SECONDS);
        assertTrue(cancelled);

        // Should have emitted a timeout info event
        boolean hasTimeoutMsg = events.stream()
                .anyMatch(e -> "info".equals(e.type()) && e.content() != null
                        && e.content().contains("timeout expired"));
        assertTrue(hasTimeoutMsg, "Expected a timeout expired info event");
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
