package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a page reads of a conversation (its MCP servers, its models, its prompt workflow, the request messages, the setting
 * it was just given, where its turn is in the log) answers while the turn runs; asking the conversation's mailbox for the same
 * would wait for the turn to end.
 */
class ReadsDuringATurnTest {

    private static final class Blocking implements LlmProvider {
        final CountDownLatch inTurn = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(new ModelEntry("m1", "local", "s")); }
        @Override public String getCurrentModel() { return "m1"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() { release.countDown(); }
        @Override public String requestMessagesJson() { return "[{\"role\":\"user\"}]"; }
        @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
            inTurn.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            e.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void theReadsAnswerAtOnceWhileTheTurnRuns() throws Exception {
        IIActorSystem system = new IIActorSystem("reads-during-a-turn");
        try {
            Blocking provider = new Blocking();
            ChatSessionIIAR chat = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
            system.addIIActor(chat);
            ActorRef<LlmProvider> providerRef = chat.<LlmProvider>createChild(chat.getName() + ".provider", provider);
            chat.tellNow(a -> {
                ((ChatSession) a).setProviderName(providerRef.getName());
                ((ChatSession) a).setMcpServers(java.util.Map.of("docs", "http://127.0.0.1:1/mcp"));
            }).get(5, TimeUnit.SECONDS);
            ActorRef<ChatSession> ref = chat.asChatSessionRef();
            ref.tell(c -> {   // in the mailbox, as the queue runs a turn: the actor is held until it ends
                c.start("hello", null, e -> { }, ref, new CompletableFuture<>(), null, false);
                c.runUntilEnd();
            });
            assertTrue(provider.inTurn.await(10, TimeUnit.SECONDS));

            long start = System.nanoTime();
            assertEquals(java.util.Map.of("docs", "http://127.0.0.1:1/mcp"), chat.getMcpServersDirect());
            assertEquals("m1", chat.getAvailableModelsDirect().get(0).name());
            assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW, chat.getPromptWorkflowFileDirect());
            assertEquals("[{\"role\":\"user\"}]", chat.requestMessagesJsonDirect());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 500, "the reads took " + millis + " ms during the turn");

            // The same read through the mailbox waits for the turn.
            CompletableFuture<java.util.Map<String, String>> viaMailbox = chat.<java.util.Map<String, String>>ask(
                    a -> ((ChatSession) a).getMcpServers());
            Thread.sleep(300);
            assertFalse(viaMailbox.isDone(), "the mailbox read still waits behind the turn");
            provider.release.countDown();
            viaMailbox.get(10, TimeUnit.SECONDS);
        } finally {
            system.terminate();
        }
    }

    /** A setting changed during a turn is taken at once (no wait for the turn), read back at once, and the turn reads it at its next step. */
    @Test
    void aSettingChangedDuringATurnReturnsAtOnceAndTheTurnSeesIt() throws Exception {
        IIActorSystem system = new IIActorSystem("settings-during-a-turn");
        try {
            Blocking provider = new Blocking();
            ChatSessionIIAR chat = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
            system.addIIActor(chat);
            ActorRef<LlmProvider> providerRef = chat.<LlmProvider>createChild(chat.getName() + ".provider", provider);
            chat.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).get(5, TimeUnit.SECONDS);
            ActorRef<ChatSession> ref = chat.asChatSessionRef();
            ref.tell(c -> {
                c.start("hello", null, e -> { }, ref, new CompletableFuture<>(), null, false);
                c.runUntilEnd();
            });
            assertTrue(provider.inTurn.await(10, TimeUnit.SECONDS));

            long start = System.nanoTime();
            chat.setStepLimitDirect(7);
            chat.setTimeLimitMinutesDirect(3);
            chat.setNoThinkDirect(true);
            chat.setMcpServersDirect(java.util.Map.of("docs", "http://127.0.0.1:1/mcp"));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 500, "the four settings took " + millis + " ms during the turn");
            assertEquals(7, chat.getStepLimitDirect());
            assertEquals(3, chat.getTimeLimitMinutesDirect());
            assertTrue(chat.isNoThinkDirect());
            assertEquals(java.util.Map.of("docs", "http://127.0.0.1:1/mcp"), chat.getMcpServersDirect());

            // The same change through the mailbox waits for the turn (what the setters used to do).
            CompletableFuture<Void> viaMailbox = chat.tell(a -> ((ChatSession) a).setStepLimit(9));
            Thread.sleep(300);
            assertFalse(viaMailbox.isDone());
            provider.release.countDown();
            viaMailbox.get(10, TimeUnit.SECONDS);
        } finally {
            system.terminate();
        }
    }
}
