package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/**
 * A reply with no tool call whose text is a model's tool-call markup is not an answer. Observed on project5/chat-01
 * (GLM-5.3-Flash-Uncensored, turns 46 and 47 of session 41, 2026-11-25): {@code <tool_call>edit<arg_key>...} for a tool
 * name the request did not offer was left in the text by the server, and the turn ended with it as the final answer.
 * Reproduced against the broker: the same text is read as a call when the name is in the request's tools list and left as
 * text when it is not; the line breaks in it make no difference.
 */
class ChatSessionMarkupToolCallTest {

    static final String MARKUP = "<tool_call>edit<arg_key>new_string</arg_key><arg_value>b</arg_value>"
            + "<arg_key>old_string</arg_key><arg_value>a</arg_value><arg_key>path</arg_key><arg_value>/x/y.java</arg_value></tool_call>";

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new ArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 7L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** One reply of the scripted model: its text, or a call it made the way a server hands one over. */
    private record Reply(String text, String tool, String arguments) {
        static Reply text(String t) { return new Reply(t, null, null); }
        static Reply call(String tool, String args) { return new Reply(null, tool, args); }
    }

    /** A provider that calls tools by definition, as the OpenAI-compatible one does. The last reply repeats. */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<Reply> replies;
        final List<String> prompts = new ArrayList<>();
        final List<String> toolsRun = new ArrayList<>();
        final List<ToolCallResult> results = new ArrayList<>();
        ScriptedProvider(List<Reply> replies) { this.replies = replies; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean callsToolsByDefinition() { return true; }
        @Override public List<String> toolNames() { return List.of("read", "write", "edit", "bash"); }
        @Override public void addToolResults(List<ToolCallResult> r) { results.addAll(r); }
        @Override public String callTool(String name, String argumentsJson) { toolsRun.add(name); return "result of " + name; }
        @Override
        public void sendPrompt(String prompt, String model, java.util.function.Consumer<ChatEvent> emitter, ProviderContext ctx) {
            int i = Math.min(prompts.size(), replies.size() - 1);
            prompts.add(prompt);
            Reply r = replies.get(i);
            if (r.tool() != null) emitter.accept(ChatEvent.toolCall("call-" + i, r.tool(), r.arguments()));
            else emitter.accept(ChatEvent.delta(r.text()));
        }
    }

    private ChatSessionIIAR newSession(ScriptedProvider provider, CapturingIoLog ioLog) {
        IIActorSystem system = new IIActorSystem("chat-session-markup-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project5", "01");
        }).join();
        return iiar;
    }

    private List<ChatEvent> runTurn(ChatSessionIIAR iiar) {
        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("change the helper in Main.java", null, emitted::add, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        return emitted;
    }

    private static String shown(List<ChatEvent> emitted) {
        return emitted.stream().filter(e -> "delta".equals(e.type())).map(ChatEvent::content).reduce("", (a, b) -> a + b);
    }

    @Test
    void markupInTheTextIsNotTheAnswer_theModelIsToldAndTheTurnGoesOn() {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(Reply.text(MARKUP),
                Reply.call("read", "{\"path\":\"/x/y.java\"}"), Reply.text("Done: the helper is changed.")));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(3, provider.prompts.size(), "markup, then the call, then the answer: " + ioLog.records);
        String note = provider.prompts.get(1);
        assertTrue(note.contains("Your tool call was not understood") && note.contains("tool_calls mechanism")
                && note.contains("read, write, edit, bash"), note);
        assertEquals(List.of("read"), provider.toolsRun, "only the real call ran; nothing was run from the text");
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/markup-call")
                && r.content().contains("1 in this turn") && r.content().contains("<tool_call>edit")), ioLog.records.toString());
        assertTrue(ioLog.records.stream().noneMatch(r -> r.label().equals("turn1/step1/tool")), "the markup ran nothing");
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals("Done: the helper is changed.", history.get(history.size() - 1).content());
        assertFalse(shown(emitted).contains("<tool_call>"), "the markup was never shown as an answer: " + shown(emitted));
        assertTrue(emitted.stream().noneMatch(e -> "error".equals(e.type())));
    }

    @Test
    void aModelThatKeepsWritingMarkupEndsTheTurnWithAnErrorAfterTwoRetries() {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(Reply.text(MARKUP)));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(3, provider.prompts.size(), "the question and two retries; the third reply ends the turn");
        assertTrue(provider.toolsRun.isEmpty(), "nothing is run from text");
        ChatEvent error = emitted.stream().filter(e -> "error".equals(e.type())).findFirst().orElseThrow();
        assertTrue(error.content().contains("the model's tool call could not be parsed"), error.content());
        assertFalse(shown(emitted).contains("<tool_call>"), "the markup is not the answer: " + shown(emitted));
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step3/markup-call")
                && r.content().contains("THE TURN ENDS WITH AN ERROR") && r.content().contains("3 in this turn")), ioLog.records.toString());
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/conversation")
                && r.content().contains("tool call could not be parsed")), "the failed turn is recorded: " + ioLog.records);
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertTrue(history.stream().noneMatch(h -> "assistant".equals(h.role()) && h.content().contains("<tool_call>")),
                "no assistant entry holds the markup: " + history);
        assertFalse(iiar.isBusyDirect());
    }

    @Test
    void theRetriesAreCountedPerTurn_notInARow() {
        CapturingIoLog ioLog = new CapturingIoLog();
        // markup, a real call, markup, a real call, markup: three markup replies in one turn, never two in a row.
        ScriptedProvider provider = new ScriptedProvider(List.of(Reply.text(MARKUP), Reply.call("read", "{\"path\":\"a\"}"),
                Reply.text(MARKUP), Reply.call("read", "{\"path\":\"b\"}"), Reply.text(MARKUP)));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = runTurn(iiar);

        assertTrue(emitted.stream().anyMatch(e -> "error".equals(e.type()) && e.content().contains("could not be parsed")),
                "the third markup reply of the turn ends it: " + ioLog.records);
        assertEquals(List.of("read", "read"), provider.toolsRun);
    }

    @Test
    void aCallToANameThatIsNotAToolGetsASaidNoSuchToolAndTheNamesToUse() {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(Reply.call("nonexistent_tool", "{\"path\":\"a\"}"), Reply.text("ok")));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        runTurn(iiar);

        assertTrue(provider.toolsRun.isEmpty(), "a call to no tool is not run");
        String content = provider.results.get(0).content();
        assertTrue(content.contains("there is no tool named \"nonexistent_tool\"") && content.contains("available: read, write, edit, bash."), content);
    }

    @Test
    void theRepeatedCallGuardStillEndsATurnThatAlsoHadMarkup() {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(Reply.text(MARKUP),
                Reply.call("read", "{\"path\":\"same\"}"), Reply.call("read", "{\"path\":\"same\"}"),
                Reply.call("read", "{\"path\":\"same\"}"), Reply.call("read", "{\"path\":\"same\"}"), Reply.text("I give up.")));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        runTurn(iiar);

        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().contains("/repeated-call")), "the guard fired: " + ioLog.records);
        assertFalse(iiar.isBusyDirect());
    }
}
