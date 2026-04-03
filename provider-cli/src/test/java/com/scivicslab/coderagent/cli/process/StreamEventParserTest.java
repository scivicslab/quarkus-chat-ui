package com.scivicslab.coderagent.cli.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamEventParserTest {

    private StreamEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new StreamEventParser();
    }

    @Test
    @DisplayName("null input returns null")
    void parse_null_returnsNull() {
        assertNull(parser.parse(null));
    }

    @Test
    @DisplayName("blank input returns null")
    void parse_blank_returnsNull() {
        assertNull(parser.parse("   "));
    }

    @Test
    @DisplayName("invalid JSON returns error event")
    void parse_invalidJson_returnsError() {
        StreamEvent e = parser.parse("not-json");
        assertEquals("error", e.type());
        assertTrue(e.isError());
        assertTrue(e.content().startsWith("Failed to parse JSON:"));
    }

    @Test
    @DisplayName("system init event extracts sessionId and content")
    void parse_systemInit_extractsSessionAndContent() {
        String json = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-123\",\"model\":\"claude-sonnet-4-5\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("system", e.type());
        assertEquals("s-123", e.sessionId());
        assertTrue(e.content().contains("Session initialized"));
        assertTrue(e.content().contains("claude-sonnet-4-5"));
    }

    @Test
    @DisplayName("system non-init event extracts message content")
    void parse_systemNonInit_extractsMessage() {
        String json = "{\"type\":\"system\",\"subtype\":\"info\",\"message\":\"hello from system\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("system", e.type());
        assertEquals("hello from system", e.content());
    }

    @Test
    @DisplayName("assistant event with text block returns assistant event")
    void parse_assistantWithText_returnsText() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"text","text":"Hello world"}]}}
            """.trim();
        StreamEvent e = parser.parse(json);
        assertEquals("assistant", e.type());
        assertEquals("Hello world", e.content());
        assertTrue(e.hasContent());
    }

    @Test
    @DisplayName("assistant event with thinking block returns thinking event")
    void parse_assistantWithThinking_returnsThinking() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"reasoning..."}]}}
            """.trim();
        StreamEvent e = parser.parse(json);
        assertEquals("thinking", e.type());
    }

    @Test
    @DisplayName("assistant event with tool_use block returns tool_activity event")
    void parse_assistantWithToolUse_returnsToolActivity() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","id":"t-1"}]}}
            """.trim();
        StreamEvent e = parser.parse(json);
        assertEquals("tool_activity", e.type());
        assertEquals("Bash", e.content());
    }

    @Test
    @DisplayName("assistant event with AskUserQuestion returns prompt event")
    void parse_assistantWithAskUser_returnsPrompt() {
        String json = """
            {"type":"assistant","message":{"content":[
              {"type":"tool_use","name":"AskUserQuestion","id":"t-2",
               "input":{"question":"Proceed?","options":[{"label":"Yes"},{"label":"No"}]}}
            ]}}
            """.trim();
        StreamEvent e = parser.parse(json);
        assertEquals("prompt", e.type());
        assertTrue(e.isPrompt());
        assertEquals("Proceed?", e.content());
        assertEquals(2, e.options().size());
        assertEquals("Yes", e.options().get(0));
        assertEquals("No", e.options().get(1));
    }

    @Test
    @DisplayName("result event extracts sessionId, cost, duration")
    void parse_result_extractsFields() {
        String json = "{\"type\":\"result\",\"session_id\":\"s-456\",\"total_cost_usd\":0.005,\"duration_ms\":1500,\"is_error\":false}";
        StreamEvent e = parser.parse(json);
        assertEquals("result", e.type());
        assertEquals("s-456", e.sessionId());
        assertEquals(0.005, e.costUsd(), 1e-9);
        assertEquals(1500L, e.durationMs());
        assertFalse(e.isError());
    }

    @Test
    @DisplayName("result event with is_error=true returns error event")
    void parse_resultWithError_returnsError() {
        String json = "{\"type\":\"result\",\"is_error\":true,\"result\":\"Something went wrong\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("error", e.type());
        assertTrue(e.isError());
        assertEquals("Something went wrong", e.content());
    }

    @Test
    @DisplayName("error event with error object extracts message")
    void parse_errorWithObject_extractsMessage() {
        String json = "{\"type\":\"error\",\"error\":{\"message\":\"Rate limited\"}}";
        StreamEvent e = parser.parse(json);
        assertEquals("error", e.type());
        assertTrue(e.isError());
        assertEquals("Rate limited", e.content());
    }

    @Test
    @DisplayName("error event with flat error string extracts message")
    void parse_errorWithString_extractsMessage() {
        String json = "{\"type\":\"error\",\"error\":\"Connection refused\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("error", e.type());
        assertTrue(e.isError());
        assertEquals("Connection refused", e.content());
    }

    @Test
    @DisplayName("rate_limit_event returns rate_limit_event type")
    void parse_rateLimitEvent_returnsCorrectType() {
        String json = "{\"type\":\"rate_limit_event\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("rate_limit_event", e.type());
        assertFalse(e.isError());
    }

    @Test
    @DisplayName("unknown event type returns event with that type")
    void parse_unknownType_returnsWithType() {
        String json = "{\"type\":\"future_event\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("future_event", e.type());
        assertFalse(e.isError());
    }

    @Test
    @DisplayName("user event with tool_use_result extracts summary")
    void parse_userWithToolResult_extractsSummary() {
        String json = "{\"type\":\"user\",\"tool_use_result\":{\"output\":\"Done\",\"status\":\"ok\"}}";
        StreamEvent e = parser.parse(json);
        assertEquals("tool_result", e.type());
        assertNotNull(e.content());
    }

    @Test
    @DisplayName("assistant event with multiple text blocks concatenates text")
    void parse_assistantMultipleTextBlocks_concatenates() {
        String json = """
            {"type":"assistant","message":{"content":[
              {"type":"text","text":"Hello "},
              {"type":"text","text":"world"}
            ]}}
            """.trim();
        StreamEvent e = parser.parse(json);
        assertEquals("assistant", e.type());
        assertEquals("Hello world", e.content());
    }
}
