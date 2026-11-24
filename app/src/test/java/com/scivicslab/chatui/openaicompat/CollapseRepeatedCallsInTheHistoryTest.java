package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * What the provider keeps of a turn: a turn that ended with an answer keeps every call, and one that ended without
 * an answer keeps one call of each run of identical calls and a note, which is what the next request carries.
 */
class CollapseRepeatedCallsInTheHistoryTest {

    HttpServer server;
    final Deque<String> replies = new ConcurrentLinkedDeque<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> reply(ex, "{\"data\":[{\"id\":\"m\",\"max_model_len\":262144}]}", "application/json"));
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            reply(ex, replies.pollFirst(), "text/event-stream");
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static void reply(HttpExchange ex, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String callReply(String id) {
        JSONObject call = new JSONObject().put("index", 0).put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", "bash").put("arguments", "{\"command\":\"echo done\"}"));
        return "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("delta", new JSONObject().put("tool_calls", new JSONArray().put(call))))) + "\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
    }

    /** A turn of {@code steps} identical calls, each with the result {@code exit 0 done}; the turn is not yet ended. */
    private OpenAiCompatProvider provider(int steps) {
        OpenAiCompatProvider p = new OpenAiCompatProvider(
                List.of("http://127.0.0.1:" + server.getAddress().getPort()), "m");
        p.getAvailableModels();
        p.setSystemMessage("SYSTEM");
        for (int i = 1; i <= steps; i++) {
            replies.add(callReply("call_" + i));
            List<ChatEvent> events = new ArrayList<>();
            p.sendPrompt(i == 1 ? "Run echo done." : "", null, events::add,
                    new ProviderContext(null, List.of(), true, () -> {}, null, true));
            assertTrue(events.stream().anyMatch(e -> "tool_call".equals(e.type())), events.toString());
            p.addToolResults(List.of(new LlmProvider.ToolCallResult("call_" + i, "bash", "exit 0\ndone")));
        }
        return p;
    }

    private static List<String> roles(OpenAiCompatProvider p) {
        JSONArray h = new JSONArray(p.requestMessagesJson());
        List<String> roles = new ArrayList<>();
        for (int i = 0; i < h.length(); i++) roles.add(h.getJSONObject(i).getString("role"));
        return roles;
    }

    @Test
    void aTurnThatEndedWithoutAnAnswerKeepsOneOfFortyFourIdenticalCallsAndANote() {
        OpenAiCompatProvider p = provider(44);
        assertEquals(2 + 88, roles(p).size(), "the system message, the question and 44 calls with their results");

        p.collapseTurn("Run echo done.", null, true);

        assertEquals(List.of("system", "user", "assistant", "tool", "assistant"), roles(p));
        JSONArray h = new JSONArray(p.requestMessagesJson());
        assertEquals("call_1", h.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        assertEquals("call_1", h.getJSONObject(3).getString("tool_call_id"));
        assertEquals("The same call was repeated 43 more times with the same result and was removed from the history.",
                h.getJSONObject(4).getString("content"));
    }

    @Test
    void aTurnThatEndedWithAnAnswerIsNotTouched() {
        OpenAiCompatProvider p = provider(44);

        p.collapseTurn("Run echo done.", "Done.", false);

        List<String> roles = roles(p);
        assertEquals(2 + 88 + 1, roles.size());
        assertEquals("assistant", roles.get(roles.size() - 1));
    }

    @Test
    void theTwoArgumentFormKeepsEveryCall() {
        OpenAiCompatProvider p = provider(5);

        p.collapseTurn("Run echo done.", null);

        assertEquals(2 + 10, roles(p).size());
    }
}
