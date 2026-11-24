package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What other threads read of a conversation (whether it is busy and since when, its provider and tool set) comes from the
 * board the turn tells, so a read does not wait for a running turn and follows the turn's changes in the order it made them.
 */
class ChatBoardTest {

    private static final class Blocking implements LlmProvider {
        final CountDownLatch inTurn = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m1"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() { release.countDown(); }
        @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
            inTurn.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            e.accept(ChatEvent.delta("answer"));
            e.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void theBoardAnswersWhileATurnRunsAndFollowsItsEnd() throws Exception {
        IIActorSystem system = new IIActorSystem("chat-board-test");
        try {
            Blocking provider = new Blocking();
            ChatSessionIIAR chat = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
            system.addIIActor(chat);
            ActorRef<LlmProvider> providerRef = chat.<LlmProvider>createChild(chat.getName() + ".provider", provider);
            chat.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();

            assertFalse(chat.isBusyDirect());
            assertNull(chat.runningSinceDirect());
            assertSame(provider, chat.getModelDirect() == null ? null : provider, "the board knows the provider");
            assertEquals("m1", chat.getModelDirect());
            assertEquals("fake", chat.getProviderIdDirect());

            ActorRef<ChatSession> ref = chat.asChatSessionRef();
            ref.tellNow(c -> {
                c.start("hello", null, e -> { }, ref, new java.util.concurrent.CompletableFuture<>(), null, false);
                c.runUntilEnd();
            });
            assertTrue(provider.inTurn.await(10, TimeUnit.SECONDS));
            // The actor is held by the turn; these reads still answer, and say the turn runs.
            assertTrue(chat.isBusyDirect());
            assertNotNull(chat.runningSinceDirect());

            provider.release.countDown();
            long deadline = System.currentTimeMillis() + 10_000;
            while (chat.isBusyDirect() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertFalse(chat.isBusyDirect());
            assertNull(chat.runningSinceDirect());
            assertEquals("answer", chat.getHistorySnapshotDirect().get(chat.getHistorySnapshotDirect().size() - 1).content());
        } finally {
            system.terminate();
        }
    }

    @Test
    void aSettingChangedByTheTurnsOwnMessageIsReadAfterIt() throws Exception {
        IIActorSystem system = new IIActorSystem("chat-board-test-2");
        try {
            ChatSessionIIAR chat = new ChatSessionIIAR("chat", new Blocking(), Optional.empty(), null, system);
            system.addIIActor(chat);
            chat.tell(a -> ((ChatSession) a).setNoThink(true)).get(5, TimeUnit.SECONDS);
            assertTrue(chat.isNoThinkDirect());
            chat.tell(a -> ((ChatSession) a).setNoThink(false)).get(5, TimeUnit.SECONDS);
            assertFalse(chat.isNoThinkDirect());
        } finally {
            system.terminate();
        }
    }
}
