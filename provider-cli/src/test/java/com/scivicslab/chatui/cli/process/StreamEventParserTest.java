package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamEventParserTest {

    private StreamEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new StreamEventParser();
    }

    // --- Edge cases ---

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

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
        @DisplayName("empty string returns null")
        void parse_empty_returnsNull() {
            assertNull(parser.parse(""));
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
        @DisplayName("JSON without type field returns 'unknown' type")
        void parse_noTypeField_returnsUnknown() {
            StreamEvent e = parser.parse("{\"foo\":\"bar\"}");
            assertEquals("unknown", e.type());
        }

        @Test
        @DisplayName("JSON with empty type field returns empty string type")
        void parse_emptyTypeField_returnsDefault() {
            StreamEvent e = parser.parse("{\"type\":\"\"}");
            // empty string is not matched by any case, goes to default
            assertEquals("", e.type());
        }
    }

    // --- System events ---

    @Nested
    @DisplayName("System events")
    class SystemEvents {

        @Test
        @DisplayName("system init event extracts sessionId and model")
        void parse_systemInit_extractsSessionAndContent() {
            String json = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-123\",\"model\":\"claude-sonnet-4-5\"}";
            StreamEvent e = parser.parse(json);
            assertEquals("system", e.type());
            assertEquals("s-123", e.sessionId());
            assertTrue(e.content().contains("Session initialized"));
            assertTrue(e.content().contains("claude-sonnet-4-5"));
        }

        @Test
        @DisplayName("system init without model omits model text")
        void parse_systemInit_noModel() {
            String json = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}";
            StreamEvent e = parser.parse(json);
            assertEquals("Session initialized", e.content());
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
        @DisplayName("system hook_started event returns system type")
        void parse_systemHookStarted() {
            String json = "{\"type\":\"system\",\"subtype\":\"hook_started\",\"hook_id\":\"h-1\"}";
            StreamEvent e = parser.parse(json);
            assertEquals("system", e.type());
        }

        @Test
        @DisplayName("system hook_response event returns system type")
        void parse_systemHookResponse() {
            String json = "{\"type\":\"system\",\"subtype\":\"hook_response\",\"hook_id\":\"h-1\",\"message\":\"done\"}";
            StreamEvent e = parser.parse(json);
            assertEquals("system", e.type());
            assertEquals("done", e.content());
        }
    }

    // --- Permission request events ---

    @Nested
    @DisplayName("Permission request events")
    class PermissionRequestEvents {

        @Test
        @DisplayName("permission_request returns prompt event with type=permission")
        void parse_permissionRequest_returnsPrompt() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"toolu_123","tool_name":"Write",
                 "tool_input":{"file_path":"/tmp/test.txt"}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("prompt", e.type());
            assertTrue(e.isPrompt());
            assertEquals("permission", e.promptType());
            assertEquals("toolu_123", e.promptId());
        }

        @Test
        @DisplayName("permission_request for Write includes file path in message")
        void parse_permissionRequest_writeToolMessage() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"t-1","tool_name":"Write",
                 "tool_input":{"file_path":"/home/user/file.txt"}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertTrue(e.content().contains("/home/user/file.txt"));
            assertTrue(e.content().toLowerCase().contains("write"));
        }

        @Test
        @DisplayName("permission_request for Edit includes file path in message")
        void parse_permissionRequest_editToolMessage() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"t-2","tool_name":"Edit",
                 "tool_input":{"file_path":"/src/Main.java"}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertTrue(e.content().contains("/src/Main.java"));
            assertTrue(e.content().toLowerCase().contains("edit"));
        }

        @Test
        @DisplayName("permission_request for Bash includes command in message")
        void parse_permissionRequest_bashToolMessage() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"t-3","tool_name":"Bash",
                 "tool_input":{"command":"rm -rf /tmp/test"}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertTrue(e.content().contains("rm -rf /tmp/test"));
        }

        @Test
        @DisplayName("permission_request for unknown tool shows generic message")
        void parse_permissionRequest_unknownTool() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"t-4","tool_name":"CustomTool"}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertTrue(e.content().contains("CustomTool"));
        }

        @Test
        @DisplayName("permission_request includes default Yes/No options when none provided")
        void parse_permissionRequest_defaultOptions() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"t-5","tool_name":"Bash",
                 "tool_input":{"command":"ls"}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertNotNull(e.options());
            assertEquals(3, e.options().size());
            assertEquals("Yes", e.options().get(0));
            assertEquals("Yes, don't ask again", e.options().get(1));
            assertEquals("No", e.options().get(2));
        }

        @Test
        @DisplayName("permission_request uses provided options when available")
        void parse_permissionRequest_customOptions() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"t-6","tool_name":"Bash",
                 "options":["Allow","Deny","Always allow"],
                 "tool_input":{"command":"ls"}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals(3, e.options().size());
            assertEquals("Allow", e.options().get(0));
            assertEquals("Deny", e.options().get(1));
            assertEquals("Always allow", e.options().get(2));
        }

        @Test
        @DisplayName("permission_request uses explicit message if provided")
        void parse_permissionRequest_explicitMessage() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"t-7","tool_name":"Write",
                 "message":"Allow writing to config file?"}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("Allow writing to config file?", e.content());
        }

        @Test
        @DisplayName("permission_request generates UUID when tool_use_id missing")
        void parse_permissionRequest_missingToolUseId() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_name":"Read"}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertNotNull(e.promptId());
            assertFalse(e.promptId().isEmpty());
        }
    }

    // --- Assistant events ---

    @Nested
    @DisplayName("Assistant events")
    class AssistantEvents {

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
        @DisplayName("assistant event with multiple tool_use blocks joins names")
        void parse_assistantMultipleToolUse_joinsNames() {
            String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","name":"Read","id":"t-1"},
                  {"type":"tool_use","name":"Write","id":"t-2"}
                ]}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("tool_activity", e.type());
            assertTrue(e.content().contains("Read"));
            assertTrue(e.content().contains("Write"));
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
        @DisplayName("AskUserQuestion with questions array uses first question")
        void parse_askUserWithQuestionsArray() {
            String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","name":"AskUserQuestion","id":"q-1",
                   "input":{"questions":[
                     {"question":"First question?","options":["A","B"]},
                     {"question":"Second question?"}
                   ]}}
                ]}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("prompt", e.type());
            assertEquals("First question?", e.content());
            assertEquals(2, e.options().size());
        }

        @Test
        @DisplayName("AskUserQuestion with no input returns default message")
        void parse_askUserNoInput() {
            String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","name":"AskUserQuestion","id":"q-2"}
                ]}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("prompt", e.type());
            assertEquals("Question from LLM", e.content());
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

        @Test
        @DisplayName("assistant event with empty content array returns empty assistant")
        void parse_assistantEmptyContent() {
            String json = "{\"type\":\"assistant\",\"message\":{\"content\":[]}}";
            StreamEvent e = parser.parse(json);
            assertEquals("assistant", e.type());
            assertEquals("", e.content());
        }

        @Test
        @DisplayName("assistant event without message object returns assistant with content")
        void parse_assistantNoMessage() {
            String json = "{\"type\":\"assistant\",\"content\":\"direct text\"}";
            StreamEvent e = parser.parse(json);
            assertEquals("assistant", e.type());
            assertEquals("direct text", e.content());
        }

        @Test
        @DisplayName("assistant event with null message content returns empty")
        void parse_assistantNullMessageContent() {
            String json = "{\"type\":\"assistant\",\"message\":{}}";
            StreamEvent e = parser.parse(json);
            assertEquals("assistant", e.type());
        }
    }

    // --- User events (tool results) ---

    @Nested
    @DisplayName("User events")
    class UserEvents {

        @Test
        @DisplayName("user event with tool_use_result extracts summary")
        void parse_userWithToolResult_extractsSummary() {
            String json = "{\"type\":\"user\",\"tool_use_result\":{\"output\":\"Done\",\"status\":\"ok\"}}";
            StreamEvent e = parser.parse(json);
            assertEquals("tool_result", e.type());
            assertNotNull(e.content());
        }

        @Test
        @DisplayName("user event with long tool_use_result is truncated")
        void parse_userWithLongToolResult_truncated() {
            String longResult = "{\"type\":\"user\",\"tool_use_result\":{\"output\":\"" + "x".repeat(300) + "\"}}";
            StreamEvent e = parser.parse(longResult);
            assertEquals("tool_result", e.type());
            assertTrue(e.content().length() <= 203); // 200 + "..."
        }

        @Test
        @DisplayName("user event without tool_use_result returns null summary")
        void parse_userWithoutToolResult() {
            String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}";
            StreamEvent e = parser.parse(json);
            assertEquals("tool_result", e.type());
            assertNull(e.content());
        }
    }

    // --- Result events ---

    @Nested
    @DisplayName("Result events")
    class ResultEvents {

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
        @DisplayName("result event without session_id has null sessionId")
        void parse_result_noSessionId() {
            String json = "{\"type\":\"result\",\"is_error\":false}";
            StreamEvent e = parser.parse(json);
            assertEquals("result", e.type());
            assertNull(e.sessionId());
        }

        @Test
        @DisplayName("result event without cost/duration uses defaults")
        void parse_result_noCostDuration() {
            String json = "{\"type\":\"result\",\"is_error\":false}";
            StreamEvent e = parser.parse(json);
            assertEquals(-1.0, e.costUsd(), 1e-9);
            assertEquals(-1L, e.durationMs());
        }
    }

    // --- Error events ---

    @Nested
    @DisplayName("Error events")
    class ErrorEvents {

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
        @DisplayName("error event with message field extracts it")
        void parse_errorWithMessageField() {
            String json = "{\"type\":\"error\",\"message\":\"timeout\"}";
            StreamEvent e = parser.parse(json);
            assertEquals("error", e.type());
            assertEquals("timeout", e.content());
        }

        @Test
        @DisplayName("error event with no details returns Unknown error")
        void parse_errorNoDetails() {
            String json = "{\"type\":\"error\"}";
            StreamEvent e = parser.parse(json);
            assertEquals("error", e.type());
            assertEquals("Unknown error", e.content());
        }
    }

    // --- Rate limit events ---

    @Test
    @DisplayName("rate_limit_event returns rate_limit_event type")
    void parse_rateLimitEvent_returnsCorrectType() {
        String json = "{\"type\":\"rate_limit_event\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("rate_limit_event", e.type());
        assertFalse(e.isError());
    }

    // --- Unknown events ---

    @Test
    @DisplayName("unknown event type returns event with that type")
    void parse_unknownType_returnsWithType() {
        String json = "{\"type\":\"future_event\"}";
        StreamEvent e = parser.parse(json);
        assertEquals("future_event", e.type());
        assertFalse(e.isError());
    }

    @Test
    @DisplayName("unknown event preserves rawJson")
    void parse_unknownType_preservesRawJson() {
        String json = "{\"type\":\"future_event\",\"data\":123}";
        StreamEvent e = parser.parse(json);
        assertEquals(json, e.rawJson());
    }

    // --- Real-world CLI output patterns ---

    @Nested
    @DisplayName("Real-world CLI output patterns")
    class RealWorldPatterns {

        @Test
        @DisplayName("permission denial tool_result is parsed as tool_result")
        void parse_permissionDenialToolResult() {
            // In real CLI output, tool_use_result is a string (not JSON object),
            // so parseUser's optJSONObject returns null. The event still gets rawJson.
            String json = """
                {"type":"user","message":{"role":"user","content":[
                  {"type":"tool_result",
                   "content":"Claude requested permissions to write to /tmp/test.txt, but you haven't granted it yet.",
                   "is_error":true,
                   "tool_use_id":"toolu_01X"}
                ]},
                "tool_use_result":"Error: Claude requested permissions to write to /tmp/test.txt, but you haven't granted it yet."}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("tool_result", e.type());
            // tool_use_result is a string, not a JSON object, so content is null
            // but rawJson preserves the full event for inspection
            assertNotNull(e.rawJson());
            assertTrue(e.rawJson().contains("permission"));
        }

        @Test
        @DisplayName("result with permission_denials array is parsed correctly")
        void parse_resultWithPermissionDenials() {
            String json = """
                {"type":"result","subtype":"success","is_error":false,
                 "duration_ms":7000,"total_cost_usd":0.35,
                 "session_id":"s-abc",
                 "permission_denials":[
                   {"tool_name":"Write","tool_use_id":"t-1","tool_input":{"file_path":"/tmp/x"}}
                 ]}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("result", e.type());
            assertFalse(e.isError());
            assertEquals("s-abc", e.sessionId());
        }

        @Test
        @DisplayName("assistant with Write tool_use is parsed as tool_activity")
        void parse_assistantWriteToolUse() {
            String json = """
                {"type":"assistant","message":{"model":"claude-opus-4-6",
                 "content":[{"type":"tool_use","id":"toolu_01","name":"Write",
                 "input":{"file_path":"/tmp/test.txt","content":"hello"}}]}}
                """.trim();
            StreamEvent e = parser.parse(json);
            assertEquals("tool_activity", e.type());
            assertEquals("Write", e.content());
        }
    }
}
