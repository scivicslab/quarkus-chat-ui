package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model that answers every step with the same tool call is stopped after the third call: one more request,
 * sent with no tool choice, asks it for an answer in text, and the turn ends with {@code repeated-call}
 * (production {@code chat-session-agent-loop.yaml}, a provider that sends tool calls as text).
 */
class ChatSessionRepeatedCallTest {

    private static final String SAME_CALL = """
            <invoke name="read">
            <parameter name="path">no-such-file-for-this-test</parameter>
            </invoke>""";

    /** Answers each request from a script: the request's number (from 1) and whether it must be answered in text. */
    private static final class ScriptedProvider implements LlmProvider {
        final List<String> prompts = new ArrayList<>();
        final List<Boolean> textOnly = new ArrayList<>();
        private final BiFunction<Integer, Boolean, String> script;

        ScriptedProvider(BiFunction<Integer, Boolean, String> script) { this.script = script; }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            textOnly.add(ctx.noToolCalls());
            String reply = script.apply(prompts.size(), ctx.noToolCalls());
            if (reply != null && !reply.isEmpty()) emitter.accept(ChatEvent.delta(reply));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private record Outcome(ScriptedProvider provider, String answer, String ending) {}

    private static Outcome runTurn(BiFunction<Integer, Boolean, String> script) {
        ScriptedProvider provider = new ScriptedProvider(script);
        IIActorSystem system = new IIActorSystem("chat-session-repeated-call-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef =
                iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        String[] result = new String[2];
        chatRef.tellNow(c -> {
            c.start("find something", null, event -> {}, chatRef, new CompletableFuture<>(), "key", false);
            c.runUntilEnd();
            result[0] = c.getCompletedResult("key");
            result[1] = c.getCompletedEnding("key");
        }).join();
        return new Outcome(provider, result[0], result[1]);
    }

    @Test
    void theSameCallThreeTimesThenOneTextOnlyRequestEndsTheTurn() {
        Outcome o = runTurn((n, textOnly) -> textOnly ? "The file does not exist, so I cannot read it." : SAME_CALL);

        assertEquals(4, o.provider().prompts.size(), "three calls, then the one request for an answer in text");
        assertEquals(List.of(false, false, false, true), o.provider().textOnly);
        assertTrue(o.provider().prompts.get(3).contains("give your answer in text now"), o.provider().prompts.get(3));
        assertEquals("The file does not exist, so I cannot read it.", o.answer());
        assertEquals("[ended: repeated-call; model calls: 4; tool calls: 3]", o.ending());
    }

    @Test
    void aRequestForTextThatReturnsNoTextEndsWithTheErrorTextAfterOneRequest() {
        Outcome o = runTurn((n, textOnly) -> textOnly ? "" : SAME_CALL);

        assertEquals(4, o.provider().prompts.size(), "an empty answer is not retried");
        assertEquals("The model repeated the same call 3 times and gave no answer", o.answer());
        assertEquals("[ended: repeated-call; model calls: 4; tool calls: 3]", o.ending());
    }

    @Test
    void aCallMadeInTheRequestForTextIsNotRun() {
        Outcome o = runTurn((n, textOnly) -> SAME_CALL);

        assertEquals(4, o.provider().prompts.size());
        assertEquals("The model repeated the same call 3 times and gave no answer", o.answer());
        assertEquals("[ended: repeated-call; model calls: 4; tool calls: 3]", o.ending());
    }

    @Test
    void textBetweenTheCallsStartsTheCountAgain() {
        // Calls 1, 2 and 3 are the same call, but the third comes with text: the count starts again at that call,
        // so the turn is stopped after the fifth call, not the third.
        Outcome o = runTurn((n, textOnly) -> textOnly ? "Done."
                : n == 3 ? "Still looking.\n" + SAME_CALL : SAME_CALL);

        assertEquals(6, o.provider().prompts.size(), "five calls, then the request for text");
        assertEquals(List.of(false, false, false, false, false, true), o.provider().textOnly);
        assertEquals("Done.", o.answer());
        assertEquals("[ended: repeated-call; model calls: 6; tool calls: 5]", o.ending());
    }

    @Test
    void differentCallsAreNeverStopped() {
        Outcome o = runTurn((n, textOnly) -> n <= 7
                ? "<invoke name=\"read\">\n<parameter name=\"path\">no-such-file-" + n + "</parameter>\n</invoke>"
                : "All read.");

        assertEquals(8, o.provider().prompts.size());
        assertFalse(o.provider().textOnly.contains(true));
        assertEquals("All read.", o.answer());
        assertEquals("[ended: answered; model calls: 8; tool calls: 7]", o.ending());
    }
}
