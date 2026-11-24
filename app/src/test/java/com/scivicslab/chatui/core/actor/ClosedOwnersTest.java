package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.kernel.RateSlots;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a closed owner does when it is used: the work is refused, the waiting are let go, a read answers as for a conversation with nothing in it. */
class ClosedOwnersTest {

    @Test
    void aClosedCancelScopeRefusesNewWorkInsteadOfThrowing() {
        TurnCancel scope = new TurnCancel("closed-scope");
        scope.begin();
        scope.close();
        assertNull(scope.register(() -> { }), "a closed scope refuses the work");
        assertFalse(scope.isCancelled());
        scope.cancel();   // harmless
    }

    @Test
    void closingTheRateSlotsLetsTheWaitingCallersGo() throws Exception {
        RateSlots slots = new RateSlots("closing", 60_000);
        slots.awaitSlot();   // the first is free
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                slots.awaitSlot();   // due in a minute
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, command -> Thread.ofVirtual().start(command));
        Thread.sleep(300);
        assertFalse(waiting.isDone(), "the caller waits for its slot");
        slots.close();
        waiting.get(3, TimeUnit.SECONDS);   // let go by the close, as the javadoc says
    }

    @Test
    void readsOfADeletedConversationAnswerAsForOneWithNothingInItInsteadOfThrowing() {
        IIActorSystem system = new IIActorSystem("deleted-reads");
        try {
            LlmProvider provider = new LlmProvider() {
                @Override public String id() { return "fake"; }
                @Override public String displayName() { return "Fake"; }
                @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
                @Override public String getCurrentModel() { return "m"; }
                @Override public void setModel(String model) {}
                @Override public void cancel() {}
                @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {}
            };
            ChatSessionIIAR chat = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
            system.addIIActor(chat);
            assertEquals("fake", chat.getProviderIdDirect());
            chat.close();   // the conversation is deleted

            assertFalse(chat.isBusyDirect());
            assertNull(chat.runningSinceDirect());
            assertNull(chat.getProviderIdDirect());
            assertTrue(chat.getHistorySnapshotDirect().isEmpty());
            assertTrue(chat.getAvailableModelsDirect().isEmpty());
            assertEquals(ChatSession.DEFAULT_CONVERSATION_STEP_LIMIT, chat.getStepLimitDirect());
            assertEquals(ChatSession.DEFAULT_TIME_LIMIT_MINUTES, chat.getTimeLimitMinutesDirect());
            assertFalse(chat.isNoThinkDirect());
            assertTrue(chat.getMcpServersDirect().isEmpty());
        } finally {
            system.terminate();
        }
    }

    @Test
    void aFailedWriteOfStartedIsLoggedWithTheRequestId() {
        java.util.List<LogRecord> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler spy = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger.getLogger("").addHandler(spy);
        try {
            PromptQueue queue = new PromptQueue();
            ActorRef<PromptQueue> ref = new ActorRef<>("closed-queue", queue);
            queue.setSelf(ref);
            ref.close();   // the queue's own actor is gone: the write of "started" cannot be told
            PromptQueue.QueueItem item = new PromptQueue.QueueItem("do it", null, e -> { }, new CompletableFuture<>(),
                    "agent:project1/chat-01", null, false, true, List.of(), "project1/chat-01:s1:t1:r1", false, 0L, "id-1", null);
            queue.recordStartedAfterTheTurnBegan(item);
            long until = System.currentTimeMillis() + 5_000;
            while (warnings.isEmpty() && System.currentTimeMillis() < until) {
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            assertTrue(warnings.stream().anyMatch(r -> String.valueOf(r.getMessage()).contains("project1/chat-01:s1:t1:r1")),
                    "the failure is logged with the request id: " + warnings.stream().map(LogRecord::getMessage).toList());
        } finally {
            Logger.getLogger("").removeHandler(spy);
        }
    }
}
