package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.ChatMessage.ToolCallRequest;
import com.scivicslab.chatui.openaicompat.client.ChatMessage.ToolCallRequest.ToolCall;
import com.scivicslab.chatui.openaicompat.client.ChatMessage.ToolResult;

/**
 * A turn that ends without an answer leaves its tool calls in the history, and a run of the same call is what the next
 * turn's model imitates. {@code collapseRepeatedCalls} keeps the first call of a run, its result and one note.
 */
class CollapseRepeatedCallsTest {

    private static final String BASH = "{\"command\":\"echo done\"}";
    private static final String RESULT = "exit 0\ndone";

    /** One reply that calls one tool, and the result of that call. */
    private static List<ChatMessage> step(int n, String tool, String arguments, String result) {
        return List.of(new ToolCallRequest("", List.of(new ToolCall("call_" + n, tool, arguments))),
                new ToolResult("call_" + n, tool, result));
    }

    private static List<ChatMessage> steps(int from, int count, String tool, String arguments, String result) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.addAll(step(from + i, tool, arguments, result));
        return out;
    }

    /** What the server checks: every call has its result right after it, every result has its call, and nothing else sits between. */
    private static void assertValidPairing(List<ChatMessage> messages) {
        Set<String> open = new HashSet<>();
        Set<String> called = new HashSet<>();
        for (ChatMessage m : messages) {
            if (m instanceof ToolCallRequest t) {
                assertTrue(open.isEmpty(), "a reply came before the results of the one before: " + open);
                t.toolCalls().forEach(c -> { open.add(c.id()); called.add(c.id()); });
            } else if (m instanceof ToolResult r) {
                assertTrue(open.remove(r.toolCallId()), "a result without its call: " + r.toolCallId());
            } else {
                assertTrue(open.isEmpty(), "a message sits between a call and its result: " + m);
            }
        }
        assertTrue(open.isEmpty(), "a call without its result: " + open);
    }

    private static boolean isNote(ChatMessage m) {
        return m instanceof ChatMessage.Assistant a && a.content().startsWith("The same call was repeated ");
    }

    @Test
    void fortyFourIdenticalCallsBecomeOneCallItsResultAndOneNote() {
        List<ChatMessage> out = OpenAiCompatProvider.collapseRepeatedCalls(steps(1, 44, "bash", BASH, RESULT));

        assertEquals(3, out.size(), out.toString());
        assertEquals("call_1", ((ToolCallRequest) out.get(0)).toolCalls().get(0).id());
        assertEquals("call_1", ((ToolResult) out.get(1)).toolCallId());
        assertEquals(new ChatMessage.Assistant("The same call was repeated 43 more times with the same result and "
                + "was removed from the history."), out.get(2));
        assertValidPairing(out);
    }

    @Test
    void twoDifferentCallsInBetweenAreKeptAndEachRunIsCollapsedOnItsOwn() {
        List<ChatMessage> in = new ArrayList<>();
        in.addAll(steps(1, 5, "bash", BASH, RESULT));
        in.addAll(step(6, "read", "{\"path\":\"a.txt\"}", "hello"));
        in.addAll(step(7, "read", "{\"path\":\"b.txt\"}", "world"));
        in.addAll(steps(8, 4, "bash", BASH, RESULT));

        List<ChatMessage> out = OpenAiCompatProvider.collapseRepeatedCalls(in);

        // call_1 + result + note(4), call_6 + result, call_7 + result, call_8 + result + note(3)
        assertEquals(10, out.size(), out.toString());
        assertTrue(isNote(out.get(2)) && ((ChatMessage.Assistant) out.get(2)).content().contains(" 4 more times"));
        assertEquals("call_6", ((ToolCallRequest) out.get(3)).toolCalls().get(0).id());
        assertEquals("call_7", ((ToolCallRequest) out.get(5)).toolCalls().get(0).id());
        assertEquals("call_8", ((ToolCallRequest) out.get(7)).toolCalls().get(0).id());
        assertTrue(isNote(out.get(9)) && ((ChatMessage.Assistant) out.get(9)).content().contains(" 3 more times"));
        assertValidPairing(out);
    }

    @Test
    void theSameCallWithADifferentResultOrArgumentIsNotARepeat() {
        List<ChatMessage> in = new ArrayList<>();
        in.addAll(step(1, "bash", BASH, "r1"));
        in.addAll(step(2, "bash", BASH, "r2"));
        in.addAll(step(3, "bash", "{\"command\":\"echo other\"}", "r2"));

        assertSame(in.size(), OpenAiCompatProvider.collapseRepeatedCalls(in).size());
        assertEquals(in, OpenAiCompatProvider.collapseRepeatedCalls(in));
    }

    @Test
    void theArgumentsAreComparedAsJsonNotAsText() {
        List<ChatMessage> in = new ArrayList<>();
        in.addAll(step(1, "grep", "{\"pattern\":\"x\",\"path\":\"src\"}", RESULT));
        in.addAll(step(2, "grep", "{ \"path\": \"src\", \"pattern\": \"x\" }", RESULT));

        List<ChatMessage> out = OpenAiCompatProvider.collapseRepeatedCalls(in);

        assertEquals(3, out.size());
        assertTrue(isNote(out.get(2)) && ((ChatMessage.Assistant) out.get(2)).content().contains(" 1 more times"));
    }

    @Test
    void textWrittenWithACallEndsTheRunAndStaysInTheHistory() {
        List<ChatMessage> in = new ArrayList<>();
        in.addAll(steps(1, 3, "bash", BASH, RESULT));
        in.add(new ToolCallRequest("Let me try once more.", List.of(new ToolCall("call_4", "bash", BASH))));
        in.add(new ToolResult("call_4", "bash", RESULT));
        in.addAll(steps(5, 3, "bash", BASH, RESULT));

        List<ChatMessage> out = OpenAiCompatProvider.collapseRepeatedCalls(in);

        // call_1 + result + note(2), call_4 (with its text) + result + note(3: call_5..7 repeat call_4)
        assertEquals(6, out.size(), out.toString());
        assertEquals("Let me try once more.", ((ToolCallRequest) out.get(3)).content());
        assertTrue(isNote(out.get(2)) && ((ChatMessage.Assistant) out.get(2)).content().contains(" 2 more times"));
        assertTrue(isNote(out.get(5)) && ((ChatMessage.Assistant) out.get(5)).content().contains(" 3 more times"));
        assertValidPairing(out);
    }

    @Test
    void parallelCallsOfOneReplyKeepTheirPairing() {
        List<ChatMessage> in = new ArrayList<>();
        in.add(new ToolCallRequest("", List.of(new ToolCall("a1", "bash", BASH), new ToolCall("a2", "read", "{\"path\":\"x\"}"))));
        in.add(new ToolResult("a1", "bash", RESULT));
        in.add(new ToolResult("a2", "read", "x"));
        in.addAll(steps(3, 3, "read", "{\"path\":\"x\"}", "x"));

        List<ChatMessage> out = OpenAiCompatProvider.collapseRepeatedCalls(in);

        assertEquals(4, out.size(), out.toString());
        assertTrue(isNote(out.get(3)) && ((ChatMessage.Assistant) out.get(3)).content().contains(" 3 more times"));
        assertValidPairing(out);
    }

    @Test
    void aTurnWithNoToolCallsIsReturnedAsItIs() {
        List<ChatMessage> in = List.of();
        assertEquals(in, OpenAiCompatProvider.collapseRepeatedCalls(in));
    }
}
