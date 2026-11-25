package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.audittrail.TokenBreakdown;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The system message of a conversation whose provider calls tools by definition asks for one short line before each tool
 * call. A local model with thinking off answered a long loop with bare calls (project7/chat-01 session 50 turn 8: 80 of 81
 * model calls had no text), so the screen showed nothing between them. The line is said once, in the system message, to
 * that kind of provider only.
 */
class ChatSessionNarrationTest {

    static final String LINE = "Before each tool call, write one short line (at most 20 words) saying what you are about to do and why; "
            + "when the last tool result is in, write the answer.";

    private static final class Provider implements LlmProvider {
        final boolean byDefinition;
        final List<String> systemMessages = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        final List<Reply> replies;
        Provider(boolean byDefinition, List<Reply> replies) { this.byDefinition = byDefinition; this.replies = replies; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean callsToolsByDefinition() { return byDefinition; }
        @Override public List<String> toolNames() { return List.of("read", "write"); }
        @Override public void setSystemMessage(String text) { systemMessages.add(text); }
        @Override public String callTool(String name, String argumentsJson) { return "same result"; }
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            Reply r = replies.get(Math.min(prompts.size(), replies.size() - 1));
            prompts.add(prompt);
            if (r.text() != null) emitter.accept(ChatEvent.delta(r.text()));
            if (r.tool() != null) emitter.accept(ChatEvent.toolCall("c" + prompts.size(), r.tool(), r.arguments()));
        }
    }

    record Reply(String text, String tool, String arguments) {}

    private static List<ChatEvent> runTurn(Provider provider) {
        IIActorSystem system = new IIActorSystem("narration-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), new IoLogStore() {
            @Override public synchronized long ensureSession(String id) { return 7L; }
            @Override public void record(long s, String node, String label, String content) {}
        }, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project7", "01");
        }).join();
        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("read the file", null, emitted::add, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        return emitted;
    }

    @Test
    void aProviderThatCallsToolsByDefinitionGetsTheLineOnce_inTheSystemMessage_andNotInAnyPrompt() {
        Provider provider = new Provider(true, List.of(new Reply("Reading the file first.", "read", "{\"path\":\"a\"}"),
                new Reply("The file says hello.", null, null)));
        runTurn(provider);

        assertEquals(1, provider.systemMessages.size(), "the system message is handed once for the turn");
        String system = provider.systemMessages.get(0);
        assertTrue(system.contains(LINE), system);
        assertEquals(1, system.split(java.util.regex.Pattern.quote(LINE), -1).length - 1, "said once");
        for (String prompt : provider.prompts) assertFalse(prompt.contains("one short line"), "never part of a user message: " + prompt);
    }

    @Test
    void aProviderWithItsOwnToolLoopGetsNeitherTheSystemMessageNorTheLine() {
        Provider provider = new Provider(false, List.of(new Reply("Done.", null, null)));
        runTurn(provider);

        assertTrue(provider.systemMessages.isEmpty(), "a harness keeps its own system prompt: " + provider.systemMessages);
        ChatSession session = new ChatSession(provider, Optional.empty());
        assertFalse(session.toolInstructionsText().contains("one short line"), "not in the text a harness conversation is given");
        assertFalse(provider.prompts.get(0).contains("one short line"), provider.prompts.get(0));
    }

    @Test
    void theLineIsPartOfTheSystemPromptRowOfTheTokensTab_andNothingElseMoves() {
        ChatSession session = new ChatSession(new Provider(true, List.of(new Reply("x", null, null))), Optional.empty());
        String with = session.systemMessageText();
        String without = with.replace(" " + LINE, "");
        assertFalse(without.contains("one short line"), "the test removes exactly the line");

        Map<String, Integer> rowsWith = TokenBreakdown.splitOpenAiBody(body(with));
        Map<String, Integer> rowsWithout = TokenBreakdown.splitOpenAiBody(body(without));
        assertTrue(rowsWith.get(TokenBreakdown.A1) > rowsWithout.get(TokenBreakdown.A1), "A1 (system prompt) counts the line: " + rowsWith);
        int line = rowsWith.get(TokenBreakdown.A1) - rowsWithout.get(TokenBreakdown.A1);
        assertTrue(line >= 40 && line <= 50, "about 160 characters at 3.5 per token: " + line);
        for (Map.Entry<String, Integer> row : rowsWithout.entrySet()) {
            if (!TokenBreakdown.A1.equals(row.getKey())) assertEquals(row.getValue(), rowsWith.get(row.getKey()), row.getKey() + " is unchanged");
        }
    }

    private static String body(String system) {
        return new JSONObject().put("model", "m").put("stream", true).put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", "hello"))).toString();
    }

    @Test
    void aLineBeforeEachCallDoesNotHideARepeatedCall() {
        // The model narrates every step, as it is now asked to, and calls the same thing with the same result each time.
        Provider provider = new Provider(true, List.of(new Reply("Reading the file again.", "read", "{\"path\":\"a\"}"),
                new Reply("I give up.", null, null)));
        // The scripted model repeats its first reply while it is asked for more; the last reply is for the request after the guard.
        List<Reply> script = new ArrayList<>();
        for (int i = 0; i < 6; i++) script.add(new Reply("Reading the file again.", "read", "{\"path\":\"a\"}"));
        script.add(new Reply("I give up.", null, null));
        Provider repeating = new Provider(true, script);

        runTurn(repeating);

        assertTrue(repeating.prompts.size() <= 5, "the guard ended the turn after the third identical call, not at the step limit: "
                + repeating.prompts.size());
    }
}
