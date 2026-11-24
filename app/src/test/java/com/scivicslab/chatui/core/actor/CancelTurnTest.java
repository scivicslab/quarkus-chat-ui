package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.CancelScope;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancel reaches a turn through the conversation's cancel scope, with no flag the loop and the canceller both touch:
 * a running tool call is ended and the turn ends within a second, the loop sends no further model request after the
 * cancel, and a cancel sent before the turn's first request stops the turn before it asks the model.
 */
class CancelTurnTest {

    /** Answers its first prompt with one call to a tool; the tool blocks until the turn's cancel ends it. */
    private static final class SlowToolProvider implements LlmProvider {
        final AtomicInteger modelRequests = new AtomicInteger();
        final CompletableFuture<Void> toolRunning = new CompletableFuture<>();
        final List<String> toolResults = new CopyOnWriteArrayList<>();

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean callsToolsByDefinition() { return true; }
        @Override public void addToolResults(List<ToolCallResult> r) { r.forEach(x -> toolResults.add(x.content())); }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            if (modelRequests.incrementAndGet() == 1) {
                emitter.accept(ChatEvent.toolCall("call-1", "calc", "{\"expression\":\"1+1\"}"));
                return;
            }
            emitter.accept(ChatEvent.delta("a second model request"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }

        @Override
        public String callTool(String name, String argumentsJson, CancelScope scope) {
            CompletableFuture<String> ended = new CompletableFuture<>();
            CancelScope.Registration registration = scope.register(() -> ended.complete("error: the call was cancelled"));
            if (registration == null) return "error: the call was cancelled";
            toolRunning.complete(null);
            try {
                return ended.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                return "error: " + e;
            } finally {
                registration.close();
            }
        }
    }

    private static ChatSessionIIAR session(SlowToolProvider provider) {
        IIActorSystem system = new IIActorSystem("cancel-turn-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project9", "01");
        }).join();
        return iiar;
    }

    /** Starts the turn on a virtual thread of its own: the actor is held by the turn until it ends. */
    private static CompletableFuture<TurnReply> startTurn(ChatSessionIIAR iiar) {
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("compute", null, e -> { }, chatRef, done, "result-" + System.nanoTime(), false,
                    List.of(), "screen", null);
            c.runUntilEnd();
        });
        return done;
    }

    @Test
    void aCancelDuringARunningToolEndsTheToolAndTheTurnWithinASecondAndAsksTheModelNoMore() throws Exception {
        SlowToolProvider provider = new SlowToolProvider();
        ChatSessionIIAR iiar = session(provider);
        try {
            CompletableFuture<TurnReply> done = startTurn(iiar);
            provider.toolRunning.get(10, TimeUnit.SECONDS);

            long cancelledAt = System.nanoTime();
            iiar.tellNow(a -> ((ChatSession) a).cancel());
            done.get(1, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - cancelledAt) / 1e9;
            System.out.printf("cancel -> the turn ended (a tool call was running): %.3f s%n", seconds);

            assertTrue(seconds < 1.0, "the turn ended after " + seconds + " s");
            assertEquals(1, provider.modelRequests.get(), "no model request after the cancel");
        } finally {
            iiar.close();
        }
    }

    @Test
    void aCancelBeforeTheFirstRequestStopsTheTurnBeforeItAsksTheModel() throws Exception {
        SlowToolProvider provider = new SlowToolProvider();
        ChatSessionIIAR iiar = session(provider);
        try {
            // Cancelled first, then the turn's steps run: the cancel state is the turn's, not reset by a later step.
            ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
            CompletableFuture<TurnReply> done = new CompletableFuture<>();
            chatRef.tellNow(c -> {
                c.start("compute", null, e -> { }, chatRef, done, "result-" + System.nanoTime(), false,
                        List.of(), "screen", null);
                c.cancel();
                c.runUntilEnd();
            });
            done.get(5, TimeUnit.SECONDS);
            assertEquals(0, provider.modelRequests.get(), "the loop did not ask the model");
        } finally {
            iiar.close();
        }
    }

    @Test
    void aCancelAfterTheTurnEndedIsHarmless() throws Exception {
        SlowToolProvider provider = new SlowToolProvider();
        ChatSessionIIAR iiar = session(provider);
        try {
            CompletableFuture<TurnReply> done = startTurn(iiar);
            provider.toolRunning.get(10, TimeUnit.SECONDS);
            iiar.tellNow(a -> ((ChatSession) a).cancel());
            done.get(5, TimeUnit.SECONDS);
            iiar.tellNow(a -> ((ChatSession) a).cancel()).get(5, TimeUnit.SECONDS);   // after the end, and twice
            assertEquals(1, provider.modelRequests.get());
        } finally {
            iiar.close();
        }
    }
}
