package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider that parks its harness CLI's calls (tasks/todo.md, "Shrinking the composed prompt"): the calls become
 * steps of the conversation's own agent loop. The conversation runs them itself, records them in step order, hands
 * the results back, and ends the provider's turn when its own turn ends, so ask_person still ends the turn. The fake
 * provider stands for the CLI: its first prompt reports one parked call; a blank prompt goes on with the result.
 */
class ParkedCallsTurnTest {

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new CopyOnWriteArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 7L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** Parks {@code tool} on its first prompt; on the blank prompt after it, answers with the call's result. */
    private static final class ParkingProvider implements LlmProvider {
        private final String tool;
        private final String argumentsJson;
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<ToolCallResult> results = new CopyOnWriteArrayList<>();
        final List<String> systemMessages = new CopyOnWriteArrayList<>();
        final AtomicInteger turnsEnded = new AtomicInteger();

        ParkingProvider(String tool, String argumentsJson) {
            this.tool = tool;
            this.argumentsJson = argumentsJson;
        }

        @Override public String id() { return "fake-cli"; }
        @Override public String displayName() { return "Fake CLI"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean callsToolsByDefinition() { return true; }
        @Override public boolean parksToolCalls() { return true; }
        @Override public void setSystemMessage(String text) { systemMessages.add(text); }
        @Override public void addToolResults(List<ToolCallResult> r) { results.addAll(r); }
        @Override public void endTurn() { turnsEnded.incrementAndGet(); }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                emitter.accept(ChatEvent.delta("Let me use the conversation's tool."));
                emitter.accept(ChatEvent.toolCall("chat-ui-call-1", tool, argumentsJson));
                return;
            }
            String observed = results.isEmpty() ? "(none)" : results.get(results.size() - 1).content();
            emitter.accept(ChatEvent.delta("The tool said: " + observed));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static ChatSessionIIAR newSession(ParkingProvider provider, CapturingIoLog ioLog) {
        IIActorSystem system = new IIActorSystem("parked-calls-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project9", "01");
        }).join();
        return iiar;
    }

    private static String runTurn(ChatSessionIIAR iiar, CompletableFuture<TurnReply> done) throws Exception {
        List<ChatEvent> emitted = new CopyOnWriteArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("compute 23*47", null, emitted::add, chatRef, done, "result-" + System.nanoTime(), false,
                    List.of(), "screen", null);
            c.runUntilEnd();
        }).join();
        done.get(30, TimeUnit.SECONDS);
        return emitted.stream().filter(e -> "delta".equals(e.type())).map(ChatEvent::content).reduce("", String::concat);
    }

    @Test
    void aParkedCallRunsAsAStepOfTheConversation_andItsResultGoesBackToTheProvider() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ParkingProvider provider = new ParkingProvider("calc", "{\"expression\":\"23*47\"}");
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        String answer = runTurn(iiar, new CompletableFuture<>());

        assertEquals(List.of("compute 23*47", ""), provider.prompts,
                "no tool text before the question, and a blank prompt resumes the CLI after the call");
        assertEquals(1, provider.results.size(), provider.results.toString());
        assertEquals("chat-ui-call-1", provider.results.get(0).callId());
        assertEquals("1081", provider.results.get(0).content().strip());
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().matches("turn1/step\\d+/tool")
                && r.content().contains("TOOL: calc")), "the call is recorded once as the conversation's step: " + ioLog.records);
        assertEquals(1, ioLog.records.stream().filter(r -> r.label().endsWith("/tool")).count(), ioLog.records.toString());
        assertTrue(answer.contains("The tool said: 1081"), answer);
        assertFalse(provider.systemMessages.isEmpty(), "the system message is handed over, not put in the prompt");
        assertFalse(provider.systemMessages.get(0).contains("<invoke"), provider.systemMessages.get(0));
        long deadline = System.currentTimeMillis() + 5_000;
        while (provider.turnsEnded.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, provider.turnsEnded.get(), "the provider is told the turn has ended");
    }

    @Test
    void aParkedAskPersonEndsTheTurn_andTheProviderIsToldBeforeItIsResumed() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ParkingProvider provider = new ParkingProvider("ask_person", "{\"question\":\"Chapter 1 first, or all six?\"}");
        ChatSessionIIAR iiar = newSession(provider, ioLog);
        CompletableFuture<TurnReply> done = new CompletableFuture<>();

        String answer = runTurn(iiar, done);

        assertEquals(1, provider.prompts.size(), "the CLI is not resumed after ask_person");
        assertTrue(answer.strip().endsWith("Chapter 1 first, or all six?"), answer);
        assertTrue(done.get().ending().startsWith("[ended: asked;"), done.get().ending());
        long deadline = System.currentTimeMillis() + 5_000;
        while (provider.turnsEnded.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, provider.turnsEnded.get(), "the provider answers the parked call and stops the CLI's turn");
    }
}
