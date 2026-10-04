package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.openaicompat.ToolDefinition;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.pojoactor.action.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 tests: the bundled agent-loop-react.yaml run by a real Interpreter over a scripted
 * model and a scripted tool. No HTTP.
 */
class AgentLoopWorkflowTest {

    static final String INVOKE = "<invoke name=\"list_directory\">\n<parameter name=\"path\">/x</parameter>\n</invoke>";

    static class ScriptedLlm implements LlmCall {
        final List<String> replies = new ArrayList<>();
        final List<List<ChatMessage>> requests = new ArrayList<>();
        boolean fail;
        ScriptedLlm(String... r) { replies.addAll(List.of(r)); }
        @Override public String complete(String model, List<ChatMessage> messages, boolean noThink, Consumer<String> onDelta) {
            requests.add(List.copyOf(messages));
            if (fail) throw new IllegalStateException("HTTP 500");
            String reply = replies.isEmpty() ? INVOKE : replies.remove(0);
            onDelta.accept(reply);
            return reply;
        }
    }

    static class ScriptedTools implements ToolCaller {
        final List<String> calls = new ArrayList<>();
        @Override public List<ToolDefinition> listTools() {
            return List.of(new ToolDefinition("list_directory", "Lists a directory.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));
        }
        @Override public String call(String name, String argumentsJson) {
            calls.add(name + " " + argumentsJson);
            return "a.txt\nb.txt";
        }
    }

    private static String yaml() {
        String y = AgentLoopRun.readBundledYaml("agent-loop-react");
        assertNotNull(y);
        return y;
    }

    private static List<ChatEvent> ofType(List<ChatEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.type())).toList();
    }

    @Test
    @DisplayName("tool then answer: the observation reaches the model and the final answer reaches the browser once")
    void toolThenAnswer() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(INVOKE, "Two files: a.txt and b.txt");
        ScriptedTools tools = new ScriptedTools();
        LinkedList<ChatMessage> history = new LinkedList<>();
        history.add(new ChatMessage.User("what is in /x?"));
        List<ChatEvent> events = new ArrayList<>();
        AgentTurn turn = new AgentTurn(llm, tools, "m", history, events::add, ProviderContext.simple(null), null, 1);

        ActionResult result = AgentLoopRun.run(yaml(), turn, "what is in /x?", null, null);

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(2, llm.requests.size(), "two model calls");
        assertEquals(List.of("list_directory {\"path\":\"/x\"}"), tools.calls);
        assertTrue(llm.requests.get(0).get(0) instanceof ChatMessage.System s
                && s.content().contains("list_directory(path)"), "the system prompt lists the tool");
        assertTrue(llm.requests.get(1).stream().anyMatch(m -> m instanceof ChatMessage.User u && u.content().contains("a.txt")),
                "the second call carries the observation");
        assertEquals(4, history.size(), "user, assistant(invoke), user(observation), assistant(answer)");
        List<ChatEvent> deltas = ofType(events, "delta");
        assertEquals(1, deltas.size());
        assertEquals("Two files: a.txt and b.txt", deltas.get(0).content());
        List<ChatEvent> results = ofType(events, "result");
        assertEquals(1, results.size());
        assertEquals(Boolean.FALSE, results.get(0).busy());
        assertTrue(ofType(events, "thinking").stream().anyMatch(e -> e.content().contains("<invoke")),
                "the intermediate reply is shown as thinking");
    }

    @Test
    @DisplayName("a plain reply is the answer after one model call and no tool")
    void plainAnswer() throws Exception {
        ScriptedLlm llm = new ScriptedLlm("Hello.");
        ScriptedTools tools = new ScriptedTools();
        LinkedList<ChatMessage> history = new LinkedList<>(List.of(new ChatMessage.User("hi")));
        List<ChatEvent> events = new ArrayList<>();
        AgentTurn turn = new AgentTurn(llm, tools, "m", history, events::add, ProviderContext.simple(null), null, 1);
        assertTrue(AgentLoopRun.run(yaml(), turn, "hi", null, null).isSuccess());
        assertEquals(1, llm.requests.size());
        assertTrue(tools.calls.isEmpty());
        assertEquals("Hello.", ofType(events, "delta").get(0).content());
    }

    @Test
    @DisplayName("the step limit written in the YAML ends a turn that keeps asking for tools")
    void stepLimit() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();   // every reply is an invoke
        ScriptedTools tools = new ScriptedTools();
        LinkedList<ChatMessage> history = new LinkedList<>(List.of(new ChatMessage.User("loop")));
        List<ChatEvent> events = new ArrayList<>();
        AgentTurn turn = new AgentTurn(llm, tools, "m", history, events::add, ProviderContext.simple(null), null, 1);
        String y = yaml().replace(">= 30", ">= 3");
        assertTrue(AgentLoopRun.run(y, turn, "loop", null, null).isSuccess());
        assertEquals(3, llm.requests.size(), "three model calls, then the limit");
        assertEquals(3, tools.calls.size());
        assertTrue(ofType(events, "delta").get(0).content().contains("step limit"));
        assertEquals(1, ofType(events, "result").size());
    }

    @Test
    @DisplayName("a model call that fails still ends the turn with one result event")
    void modelFailure() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.fail = true;
        LinkedList<ChatMessage> history = new LinkedList<>(List.of(new ChatMessage.User("hi")));
        List<ChatEvent> events = new ArrayList<>();
        AgentTurn turn = new AgentTurn(llm, new ScriptedTools(), "m", history, events::add, ProviderContext.simple(null), null, 1);
        assertTrue(AgentLoopRun.run(yaml(), turn, "hi", null, null).isSuccess());
        assertEquals(1, ofType(events, "result").size());
    }

    @Test
    @DisplayName("a cancelled turn ends with '(cancelled)' and one result event")
    void cancelled() throws Exception {
        ScriptedLlm llm = new ScriptedLlm("never");
        LinkedList<ChatMessage> history = new LinkedList<>(List.of(new ChatMessage.User("hi")));
        List<ChatEvent> events = new ArrayList<>();
        AgentTurn turn = new AgentTurn(llm, new ScriptedTools(), "m", history, events::add, ProviderContext.simple(null), null, 1);
        turn.cancel();
        assertTrue(AgentLoopRun.run(yaml(), turn, "hi", null, null).isSuccess());
        assertEquals(0, llm.requests.size());
        assertEquals("(cancelled)", ofType(events, "delta").get(0).content());
        assertEquals(1, ofType(events, "result").size());
    }
}
