package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The step and time limits are read through a board of their own, so a read does not wait for the conversation's running
 * turn, and sees every change the conversation made, in order.
 */
class ChatLimitsBoardTest {

    private static final class Blocking implements LlmProvider {
        final CountDownLatch inTurn = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() { release.countDown(); }
        @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
            inTurn.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            e.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void theLimitsAreReadWhileATurnRunsAndFollowEveryChange() throws Exception {
        IIActorSystem system = new IIActorSystem("limits-board");
        try {
            Blocking provider = new Blocking();
            ChatSessionIIAR chat = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
            system.addIIActor(chat);
            com.scivicslab.pojoactor.core.ActorRef<LlmProvider> providerRef =
                    chat.<LlmProvider>createChild(chat.getName() + ".provider", provider);
            chat.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
            assertEquals(ChatSession.DEFAULT_CONVERSATION_STEP_LIMIT, chat.getStepLimitDirect());
            assertEquals(ChatSession.DEFAULT_TIME_LIMIT_MINUTES, chat.getTimeLimitMinutesDirect());

            chat.tell(a -> ((ChatSession) a).setStepLimit(42)).get(5, TimeUnit.SECONDS);
            chat.tell(a -> ((ChatSession) a).setTimeLimitMinutes(0)).get(5, TimeUnit.SECONDS);
            assertEquals(42, chat.getStepLimitDirect());
            assertEquals(0, chat.getTimeLimitMinutesDirect());

            // A turn is running: the actor is held by it, and the read still answers.
            com.scivicslab.pojoactor.core.ActorRef<ChatSession> ref = chat.asChatSessionRef();
            ref.tellNow(c -> {
                c.start("hello", null, e -> { }, ref, new java.util.concurrent.CompletableFuture<>(), null, false);
                c.runUntilEnd();
            });
            assertEquals(true, provider.inTurn.await(10, TimeUnit.SECONDS));
            assertEquals(42, chat.getStepLimitDirect());
            assertEquals(0, chat.getTimeLimitMinutesDirect());
            provider.release.countDown();
        } finally {
            system.terminate();
        }
    }
}
