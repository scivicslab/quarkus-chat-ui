package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.audittrail.TokenBreakdown.Req;
import com.scivicslab.chatui.audittrail.TokenBreakdown.Table;
import com.scivicslab.chatui.core.iolog.IoLogView.LlmRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** The Tokens tab's rows: fixed, each part of a request in exactly one of them, and rows plus residual = measured. */
class TokenBreakdownTest {

    private static final List<String> IDS = TokenBreakdown.ROWS.stream().map(TokenBreakdown.RowDef::id).toList();

    private static JSONObject tool(String name) {
        return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name)
                .put("description", "d".repeat(70)).put("parameters", new JSONObject().put("type", "object")));
    }

    private static String system() {
        return "You are a model. ".repeat(10)
                + "\n\nAvailable skills — instructions you can load when the task calls for one:\n- a: " + "s".repeat(70)
                + "\n\nInstructions for this project, from its working directory. They govern the work you do here:\n\n" + "p".repeat(140)
                + "\n\nYour position in project1: you are chat-02."
                + "\n\nYou may make at most 20 tool-call steps in a turn; answer in text before you run out.";
    }

    /** The shape the log keeps for a local LLM: system, one finished turn, then a turn with one tool call. */
    private static String body() {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system()))
                .put(new JSONObject().put("role", "user").put("content", "q".repeat(35)))
                .put(new JSONObject().put("role", "assistant").put("content", "a".repeat(70)))
                .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "now".repeat(10)))
                        .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "[image 9000 chars]")))))
                .put(new JSONObject().put("role", "assistant").put("content", "let me look").put("tool_calls",
                        new JSONArray().put(new JSONObject().put("id", "c1").put("type", "function").put("function",
                                new JSONObject().put("name", "read").put("arguments", "{\"path\":\"/x\"}")))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "r".repeat(350)));
        return new JSONObject().put("model", "m").put("stream", true).put("messages", messages)
                .put("tools", new JSONArray().put(tool("read")).put(tool("list_chats")).put(tool("htmlsaurus__search")))
                .toString();
    }

    @Test
    void everyRowIsPresent_andEachPartOfTheRequestIsInTheRowItBelongsTo() {
        Map<String, Integer> rows = TokenBreakdown.splitOpenAiBody(body());

        assertEquals(IDS, new ArrayList<>(rows.keySet()), "the rows are fixed, in their order");
        for (String id : List.of("A1", "A2", "A3", "B1", "B2", "C1", "C2", "D1", "D2", "D3", "D4", "E1", "F1")) {
            assertTrue(rows.get(id) > 0, id + " holds something: " + rows);
        }
        assertEquals(0, rows.get("A4"), "no provider files for a local LLM");
        assertTrue(rows.get("B1") > rows.get("B2"), "two of chat-ui's tools, one external: " + rows);
        assertTrue(rows.get("D4") >= 100, "the 350-character tool result: " + rows);
        assertTrue(rows.get("A3") >= 40, "the 140-character instruction text: " + rows);
    }

    @Test
    void aRowWithNothingInItIsShownAsZero() {
        JSONObject minimal = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "s"))
                .put(new JSONObject().put("role", "user").put("content", "hello")));

        Map<String, Integer> rows = TokenBreakdown.splitOpenAiBody(minimal.toString());

        assertEquals(IDS, new ArrayList<>(rows.keySet()));
        assertEquals(0, rows.get("B1") + rows.get("B2") + rows.get("C1") + rows.get("D4") + rows.get("E1"));
        assertTrue(rows.get("D1") > 0);
    }

    @Test
    void aBodyThatIsNotARequestCannotBeSplit() {
        assertNull(TokenBreakdown.splitOpenAiBody("not json"));
        assertNull(TokenBreakdown.splitOpenAiBody("{\"model\":\"m\"}"));
        assertNull(TokenBreakdown.splitOpenAiBody(null));
    }

    private static Req req(int turn, int step, Map<String, Integer> rows, Integer measured) {
        return new Req(turn, step, rows, measured, 0, 0, measured == null ? null : 10, 0);
    }

    @Test
    void rowsPlusResidualEqualTheMeasuredInput_inBothColumns() {
        Map<String, Integer> rows = TokenBreakdown.splitOpenAiBody(body());
        int sum = rows.values().stream().mapToInt(Integer::intValue).sum();
        List<Req> reqs = List.of(req(1, 1, rows, sum + 500), req(2, 1, rows, sum + 800), req(2, 2, rows, sum - 20));

        Table t = TokenBreakdown.table("openai-compat", "m", 100_000, "the server's own value", reqs, "estimated", List.of());

        assertEquals(-20, t.rows().get(t.rows().size() - 1).lastRequest(), "an overestimate is a negative residual, shown as it is");
        assertEquals(500L + 800L - 20L, t.rows().get(t.rows().size() - 1).cumulative());
        assertEquals(sum - 20, t.measured().lastRequest());
        assertEquals(sum, t.rowsSum().lastRequest());
        assertTrue(t.sumsToMeasured());
        assertEquals(100_000 - (sum - 20), t.free().lastRequest());
        assertEquals(IDS.size() + 1, t.rows().size(), "the fixed rows and the residual");
    }

    @Test
    void rowsPlusResidualEqualMeasured_forRandomRequests() {
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            List<Req> reqs = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(8); i++) {
                Map<String, Integer> rows = new LinkedHashMap<>();
                for (String id : IDS) rows.put(id, random.nextInt(5000));
                reqs.add(req(1 + i / 2, 1 + i % 2, rows, random.nextInt(40) == 0 ? null : random.nextInt(60_000)));
            }
            Table t = TokenBreakdown.table("p", "m", null, "n", reqs, "estimated", List.of());
            assertTrue(t.sumsToMeasured(), "round " + round);
        }
    }

    @Test
    void aProviderThatReportsNoUsage_hasRowsButNoResidualAndNoMeasuredInput() {
        Map<String, Integer> rows = TokenBreakdown.splitOpenAiBody(body());
        Table t = TokenBreakdown.table("codex", "m", null, "not set", List.of(req(1, 1, rows, null)), "estimated", List.of());

        assertNull(t.measured().lastRequest());
        assertNull(t.rows().get(t.rows().size() - 1).lastRequest());
        assertNull(t.rows().get(t.rows().size() - 1).cumulative());
        assertNotNull(t.rowsSum().lastRequest());
        assertEquals("not available", t.measured().basis());
        assertTrue(t.sumsToMeasured());
    }

    @Test
    void aRequestThatCannotBeSplitHasNoRows_andTheColumnSaysSo() {
        Table t = TokenBreakdown.table("openai-compat", "m", 1000, "x", List.of(req(1, 1, null, 400)), "estimated", List.of());

        assertNull(t.rows().get(0).lastRequest());
        assertEquals(400, t.measured().lastRequest());
        assertNull(t.rows().get(t.rows().size() - 1).lastRequest(), "no residual without rows");
    }

    private static LlmRow cliRow(int turn, int step, String prompt, String answer, int total) {
        String request = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", prompt))).toString();
        return new LlmRow(turn, step, request, answer, "", "", total, 0, 0, 10);
    }

    @Test
    void aCliRequestHoldsThePromptsAndAnswersOfTheEarlierTurnsAndTheHeadBlockParts() {
        List<LlmRow> log = List.of(cliRow(1, 1, "q".repeat(70), "a".repeat(70), 30_000),
                cliRow(2, 1, "w".repeat(35), "b".repeat(35), 30_200));
        Map<String, Integer> head = Map.of("A2", 700, "A4", 5000, "B1", 4300);

        List<Req> reqs = TokenBreakdown.cliRequests(log, head);

        assertEquals(0, reqs.get(0).rows().get("C1"), "nothing before turn 1");
        assertEquals(20, reqs.get(1).rows().get("C1"), "turn 1's 70-character prompt");
        assertEquals(20, reqs.get(1).rows().get("C2"), "turn 1's 70-character answer");
        assertEquals(10, reqs.get(1).rows().get("D1"), "turn 2's own 35 characters");
        assertEquals(5000, reqs.get(1).rows().get("A4"));
        assertEquals(0, reqs.get(1).rows().get("A1"), "the CLI's own prompt is not visible: it is in the residual");
        Table t = TokenBreakdown.table("claude", "sonnet", 200_000, "chat-ui.window-limits", reqs, "estimated", List.of());
        assertTrue(t.rows().get(t.rows().size() - 1).lastRequest() > 20_000, "the CLI's own prompt and tools are the residual");
        assertTrue(t.sumsToMeasured());
    }

    @Test
    void theHeadBlockPartsMapToTheirRows() {
        assertEquals("A4", TokenBreakdown.rowOfHeadPart("CLAUDE.md files (1; @-imports are not followed)"));
        assertEquals("A4", TokenBreakdown.rowOfHeadPart("MEMORY.md index"));
        assertEquals("A2", TokenBreakdown.rowOfHeadPart("Skill list (7 skills, name and description)"));
        assertEquals("B1", TokenBreakdown.rowOfHeadPart("chat-ui's MCP server (tools/list, 28 tools)"));
        assertEquals("B2", TokenBreakdown.rowOfHeadPart("MCP server htmlsaurus (3 tools)"));
        assertNull(TokenBreakdown.rowOfHeadPart("CLI built-in prompt and tools (not measurable here)"));
    }

    @Test
    void theSpecialTopicsAreComputedFromTheSameFigures() {
        Map<String, Integer> rows = new LinkedHashMap<>();
        for (String id : IDS) rows.put(id, 0);
        rows.put("C1", 600);
        rows.put("C2", 400);
        rows.put("D4", 1000);
        rows.put("A1", 2000);
        List<Req> reqs = List.of(
                new Req(1, 1, rows, 10_000, 0, 0, 100, 40),
                new Req(2, 1, rows, 12_000, 6_000, 0, 100, 60),
                new Req(3, 1, rows, 14_000, 8_000, 0, 100, 0));

        Table t = TokenBreakdown.table("claude", "sonnet", 200_000, "chat-ui.window-limits", reqs, "estimated", List.of());

        Map<String, String> topics = new LinkedHashMap<>();
        t.topics().forEach(x -> topics.put(x.label(), x.value()));
        assertEquals("1000 tokens (7.1%)", topics.get("History in the last request"));
        assertEquals("2000 tokens", topics.get("Growth per turn (the last 5 turns)"));
        assertEquals("93", topics.get("Turns left before the window is full"), "(200000-14000)/2000");
        assertEquals("1000 tokens (7.1%)", topics.get("Tool results in this turn"));
        assertEquals("38.9%", topics.get("Share of the input served from the cache (all requests)"), "14000/36000");
        assertEquals("1", topics.get("Measured from turn"));
        assertEquals("33.3%", topics.get("Reasoning share of the output (all requests)"), "100/300");
        assertEquals("11 topics", t.topics().size() + " topics");
        assertEquals("28.6%", topics.get("Rows compared with the measured input (last request)"), "4000/14000");
    }
}
