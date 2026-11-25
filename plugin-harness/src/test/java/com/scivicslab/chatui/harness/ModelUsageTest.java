package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.scivicslab.chatui.core.rest.ModelUsage;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** The resolved model and its limits come from the {@code modelUsage} of the CLI's {@code result} line. */
class ModelUsageTest {

    /** As CLI 2.1.331 printed it for {@code --model sonnet} (trimmed to the keys read here). */
    private static final String RESULT = """
            {"type":"result","subtype":"success","is_error":false,"duration_ms":1145,"result":"ok","session_id":"s1","total_cost_usd":0.0476642,"usage":{"input_tokens":2,"cache_creation_input_tokens":11609,"cache_read_input_tokens":11842,"output_tokens":4},"modelUsage":{"claude-sonnet-5-5":{"inputTokens":2,"outputTokens":4,"cacheReadInputTokens":11842,"cacheCreationInputTokens":11609,"contextWindow":1000000,"maxOutputTokens":128000,"canonicalModel":"claude-sonnet-5-5"}}}""";

    @Test
    void theResultLineGivesTheResolvedModelItsWindowAndItsMaxOutput() {
        StreamEvent result = new StreamEventParser().parse(RESULT).stream().filter(e -> "result".equals(e.type())).findFirst().orElseThrow();
        assertEquals(new ModelUsage("claude-sonnet-5-5", 1_000_000, 128_000), result.modelUsage());
    }

    @Test
    void aResultWithoutModelUsageHasNone() {
        StreamEvent result = new StreamEventParser().parse(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"x\",\"session_id\":\"s\"}").get(0);
        assertNull(result.modelUsage());
    }

    @Test
    void aTurnThatUsedTwoModelsIsReportedAsTheOneThatTookMostTokens() {
        JSONObject usage = new JSONObject("""
                {"claude-haiku-5-5":{"inputTokens":10,"outputTokens":5,"contextWindow":200000,"maxOutputTokens":64000},
                 "claude-opus-5-5":{"inputTokens":3,"outputTokens":50,"cacheReadInputTokens":9000,"contextWindow":1000000,"maxOutputTokens":128000}}""");
        assertEquals(new ModelUsage("claude-opus-5-5", 1_000_000, 128_000), StreamEventParser.readModelUsage(usage));
    }

    @Test
    void anEntryWithoutLimitsGivesZeros_andNothingIsGuessed() {
        assertEquals(new ModelUsage("m", 0, 0), StreamEventParser.readModelUsage(new JSONObject("{\"m\":{\"inputTokens\":1}}")));
        assertNull(StreamEventParser.readModelUsage(new JSONObject("{}")));
        assertNull(StreamEventParser.readModelUsage(null));
    }

    @Test
    void theProviderPutsItOnTheResultEvent() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("provider-model-usage-test");
        ClaudeCodeProvider provider = new ClaudeCodeProvider(
                new HarnessSettings("default", dir, 1, "sonnet", null, dir), "p", "c", dir,
                com.scivicslab.chatui.agent.ToolSet.FULL);
        java.util.ArrayList<com.scivicslab.chatui.core.rest.ChatEvent> sent = new java.util.ArrayList<>();
        for (StreamEvent e : new StreamEventParser().parse(RESULT)) provider.dispatch(e, sent::add, new boolean[1], new boolean[1]);
        List<com.scivicslab.chatui.core.rest.ChatEvent> results = sent.stream().filter(e -> "result".equals(e.type())).toList();
        assertEquals(new ModelUsage("claude-sonnet-5-5", 1_000_000, 128_000), results.get(0).modelUsage());
    }
}
