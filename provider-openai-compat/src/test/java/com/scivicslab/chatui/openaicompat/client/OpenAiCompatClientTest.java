package com.scivicslab.chatui.openaicompat.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatClientTest {

    private static final String BASE_URL = "http://localhost:8000";
    private OpenAiCompatClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiCompatClient(BASE_URL);
    }

    // ---- Constructor / getBaseUrl() ----

    @Nested
    @DisplayName("Constructor and getBaseUrl()")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor stores base URL")
        void constructor_storesBaseUrl() {
            assertEquals(BASE_URL, client.getBaseUrl());
        }

        @Test
        @DisplayName("getBaseUrl() returns URL with trailing path if provided")
        void constructor_preservesFullUrl() {
            OpenAiCompatClient c = new OpenAiCompatClient("http://example.com:9000/v1");
            assertEquals("http://example.com:9000/v1", c.getBaseUrl());
        }
    }

    // ---- servesModel() ----

    @Nested
    @DisplayName("servesModel()")
    class ServesModelTests {

        @Test
        @DisplayName("servesModel() returns false initially (no models cached)")
        void servesModel_falseInitially() {
            assertFalse(client.servesModel("any-model"));
        }

        @Test
        @DisplayName("servesModel() throws NullPointerException for null model")
        void servesModel_throwsForNull() {
            assertThrows(NullPointerException.class, () -> client.servesModel(null));
        }

        @Test
        @DisplayName("servesModel() returns false for empty string")
        void servesModel_falseForEmpty() {
            assertFalse(client.servesModel(""));
        }
    }

    // ---- getCachedModels() ----

    @Test
    @DisplayName("getCachedModels() returns empty list initially")
    void getCachedModels_emptyInitially() {
        List<String> models = client.getCachedModels();
        assertNotNull(models);
        assertTrue(models.isEmpty());
    }

    // ---- getMaxModelLen() ----

    @Test
    @DisplayName("getMaxModelLen() returns -1 for unknown model")
    void getMaxModelLen_negativeOneForUnknown() {
        assertEquals(-1, client.getMaxModelLen("nonexistent-model"));
    }

    @Test
    @DisplayName("getMaxModelLen() throws NullPointerException for null model")
    void getMaxModelLen_throwsForNull() {
        assertThrows(NullPointerException.class, () -> client.getMaxModelLen(null));
    }

    // ---- parseModelIds() (package-private static) ----

    @Nested
    @DisplayName("parseModelIds()")
    class ParseModelIdsTests {

        @Test
        @DisplayName("Parses single model from /v1/models JSON response")
        void parseModelIds_singleModel() {
            String json = """
                    {"object":"list","data":[{"id":"Qwen3.5-35B-A3B","object":"model","created":1700000000,"owned_by":"vllm"}]}""";
            List<String> ids = OpenAiCompatClient.parseModelIds(json);
            assertEquals(1, ids.size());
            assertEquals("Qwen3.5-35B-A3B", ids.get(0));
        }

        @Test
        @DisplayName("Parses multiple models from JSON response")
        void parseModelIds_multipleModels() {
            String json = """
                    {"object":"list","data":[
                      {"id":"model-a","object":"model","created":1},
                      {"id":"model-b","object":"model","created":2},
                      {"id":"model-c","object":"model","created":3}
                    ]}""";
            List<String> ids = OpenAiCompatClient.parseModelIds(json);
            assertEquals(3, ids.size());
            assertEquals("model-a", ids.get(0));
            assertEquals("model-b", ids.get(1));
            assertEquals("model-c", ids.get(2));
        }

        @Test
        @DisplayName("Returns empty list when JSON has no 'data' key")
        void parseModelIds_noDataKey() {
            List<String> ids = OpenAiCompatClient.parseModelIds("{\"models\":[]}");
            assertTrue(ids.isEmpty());
        }

        @Test
        @DisplayName("Returns empty list for empty data array")
        void parseModelIds_emptyDataArray() {
            List<String> ids = OpenAiCompatClient.parseModelIds("{\"data\":[]}");
            assertTrue(ids.isEmpty());
        }

        @Test
        @DisplayName("Returns empty list for empty string")
        void parseModelIds_emptyString() {
            List<String> ids = OpenAiCompatClient.parseModelIds("");
            assertTrue(ids.isEmpty());
        }

        @Test
        @DisplayName("Parses model ID containing special characters")
        void parseModelIds_specialCharsInId() {
            String json = """
                    {"data":[{"id":"org/model-name_v2.1","object":"model"}]}""";
            List<String> ids = OpenAiCompatClient.parseModelIds(json);
            assertEquals(1, ids.size());
            assertEquals("org/model-name_v2.1", ids.get(0));
        }
    }

    // ---- parseMaxModelLens() (package-private static) ----

    @Nested
    @DisplayName("parseMaxModelLens()")
    class ParseMaxModelLensTests {

        @Test
        @DisplayName("Parses max_model_len from /v1/models response")
        void parseMaxModelLens_singleModel() {
            String json = """
                    {"data":[{"id":"my-model","max_model_len":32768}]}""";
            Map<String, Integer> lens = OpenAiCompatClient.parseMaxModelLens(json);
            assertEquals(1, lens.size());
            assertEquals(32768, lens.get("my-model"));
        }

        @Test
        @DisplayName("Parses multiple models with different max_model_len")
        void parseMaxModelLens_multipleModels() {
            String json = """
                    {"data":[
                      {"id":"small","max_model_len":4096},
                      {"id":"large","max_model_len":131072}
                    ]}""";
            Map<String, Integer> lens = OpenAiCompatClient.parseMaxModelLens(json);
            assertEquals(2, lens.size());
            assertEquals(4096, lens.get("small"));
            assertEquals(131072, lens.get("large"));
        }

        @Test
        @DisplayName("Returns empty map when no max_model_len field present")
        void parseMaxModelLens_noLenField() {
            String json = """
                    {"data":[{"id":"model-a","object":"model"}]}""";
            Map<String, Integer> lens = OpenAiCompatClient.parseMaxModelLens(json);
            assertTrue(lens.isEmpty());
        }

        @Test
        @DisplayName("Returns empty map for empty data array")
        void parseMaxModelLens_emptyData() {
            Map<String, Integer> lens = OpenAiCompatClient.parseMaxModelLens("{\"data\":[]}");
            assertTrue(lens.isEmpty());
        }

        @Test
        @DisplayName("Returns empty map when no data key")
        void parseMaxModelLens_noDataKey() {
            Map<String, Integer> lens = OpenAiCompatClient.parseMaxModelLens("{}");
            assertTrue(lens.isEmpty());
        }
    }

    // ---- isContextLengthError() (package-private static) ----

    @Nested
    @DisplayName("isContextLengthError()")
    class IsContextLengthErrorTests {

        @Test
        @DisplayName("Detects 'context length' in error body")
        void detectsContextLength() {
            assertTrue(OpenAiCompatClient.isContextLengthError(
                    "This model's maximum context length is 32768 tokens"));
        }

        @Test
        @DisplayName("Detects 'input_tokens' in error body")
        void detectsInputTokens() {
            assertTrue(OpenAiCompatClient.isContextLengthError(
                    "{\"error\":{\"message\":\"input_tokens exceeds limit\"}}"));
        }

        @Test
        @DisplayName("Detects 'maximum input length' in error body")
        void detectsMaximumInputLength() {
            assertTrue(OpenAiCompatClient.isContextLengthError(
                    "Prompt exceeds maximum input length"));
        }

        @Test
        @DisplayName("Returns false for unrelated error message")
        void falseForUnrelatedError() {
            assertFalse(OpenAiCompatClient.isContextLengthError("Internal server error"));
        }

        @Test
        @DisplayName("Returns false for null body")
        void falseForNull() {
            assertFalse(OpenAiCompatClient.isContextLengthError(null));
        }

        @Test
        @DisplayName("Returns false for empty string")
        void falseForEmpty() {
            assertFalse(OpenAiCompatClient.isContextLengthError(""));
        }
    }

    // ---- buildRequestBody() (package-private static) ----

    @Nested
    @DisplayName("buildRequestBody()")
    class BuildRequestBodyTests {

        @Test
        @DisplayName("Builds basic request with model and single user message")
        void basicRequest_singleUserMessage() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Hello"));
            String body = OpenAiCompatClient.buildRequestBody("my-model", msgs, false, 0);

            assertTrue(body.contains("\"model\":\"my-model\""));
            assertTrue(body.contains("\"stream\":true"));
            assertTrue(body.contains("\"role\":\"user\""));
            assertTrue(body.contains("\"content\":\"Hello\""));
            assertFalse(body.contains("max_tokens"));
            assertFalse(body.contains("chat_template_kwargs"));
        }

        @Test
        @DisplayName("Includes max_tokens when maxTokens > 0")
        void includesMaxTokens() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Hi"));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 512);

            assertTrue(body.contains("\"max_tokens\":512"));
        }

        @Test
        @DisplayName("Does not include max_tokens when maxTokens is 0")
        void excludesMaxTokensWhenZero() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Hi"));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 0);

            assertFalse(body.contains("max_tokens"));
        }

        @Test
        @DisplayName("Includes chat_template_kwargs when noThink is true")
        void includesNoThinkKwargs() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Hi"));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, true, 0);

            assertTrue(body.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"));
        }

        @Test
        @DisplayName("Does not include chat_template_kwargs when noThink is false")
        void excludesNoThinkKwargs() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Hi"));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 0);

            assertFalse(body.contains("chat_template_kwargs"));
        }

        @Test
        @DisplayName("Builds request with multiple messages (user + assistant + user)")
        void multipleMessages() {
            List<ChatMessage> msgs = List.of(
                    new ChatMessage.User("Q1"),
                    new ChatMessage.Assistant("A1"),
                    new ChatMessage.User("Q2"));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 0);

            // Verify all three messages appear in order
            int idx1 = body.indexOf("\"role\":\"user\"");
            int idx2 = body.indexOf("\"role\":\"assistant\"", idx1 + 1);
            int idx3 = body.indexOf("\"role\":\"user\"", idx2 + 1);
            assertTrue(idx1 >= 0, "First user message should be present");
            assertTrue(idx2 > idx1, "Assistant message should follow first user");
            assertTrue(idx3 > idx2, "Second user message should follow assistant");
        }

        @Test
        @DisplayName("Escapes special characters in model name")
        void escapesModelName() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Hi"));
            String body = OpenAiCompatClient.buildRequestBody("model\"with\"quotes", msgs, false, 0);

            assertTrue(body.contains("model\\\"with\\\"quotes"));
        }

        @Test
        @DisplayName("Escapes special characters in message content")
        void escapesMessageContent() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Line1\nLine2\tTab"));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 0);

            assertTrue(body.contains("Line1\\nLine2\\tTab"));
        }

        @Test
        @DisplayName("Builds request with user message containing images")
        void userMessageWithImages() {
            List<String> images = List.of("data:image/png;base64,abc123");
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Describe this", images));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 0);

            assertTrue(body.contains("\"type\":\"image_url\""));
            assertTrue(body.contains("data:image/png;base64,abc123"));
            assertTrue(body.contains("\"type\":\"text\""));
            assertTrue(body.contains("Describe this"));
        }

        @Test
        @DisplayName("User message with multiple images includes all image entries")
        void userMessageWithMultipleImages() {
            List<String> images = List.of("data:image/png;base64,img1", "data:image/jpeg;base64,img2");
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Compare", images));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 0);

            assertTrue(body.contains("img1"));
            assertTrue(body.contains("img2"));
        }

        @Test
        @DisplayName("User message without images uses simple content string")
        void userMessageNoImages_simpleContent() {
            List<ChatMessage> msgs = List.of(new ChatMessage.User("Plain text"));
            String body = OpenAiCompatClient.buildRequestBody("m", msgs, false, 0);

            // Should NOT contain "type":"image_url" or content array structure
            assertFalse(body.contains("\"type\":\"image_url\""));
            assertTrue(body.contains("\"content\":\"Plain text\""));
        }

        @Test
        @DisplayName("Handles empty message list")
        void emptyMessageList() {
            String body = OpenAiCompatClient.buildRequestBody("m", List.of(), false, 0);

            assertTrue(body.contains("\"messages\":[]"));
        }
    }

    // ---- parseSseLine() (package-private static) ----

    @Nested
    @DisplayName("parseSseLine()")
    class ParseSseLineTests {

        @Test
        @DisplayName("Returns null for null input")
        void nullInput() {
            assertNull(OpenAiCompatClient.parseSseLine(null));
        }

        @Test
        @DisplayName("Returns null for line not starting with 'data: '")
        void nonDataLine() {
            assertNull(OpenAiCompatClient.parseSseLine("event: message"));
        }

        @Test
        @DisplayName("Returns null for empty line")
        void emptyLine() {
            assertNull(OpenAiCompatClient.parseSseLine(""));
        }

        @Test
        @DisplayName("Returns null for [DONE] marker")
        void doneMarker() {
            assertNull(OpenAiCompatClient.parseSseLine("data: [DONE]"));
        }

        @Test
        @DisplayName("Extracts delta content from valid SSE data line")
        void validDeltaContent() {
            String line = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}";
            String result = OpenAiCompatClient.parseSseLine(line);
            assertEquals("Hello", result);
        }

        @Test
        @DisplayName("Returns null when delta has no content field")
        void deltaWithoutContent() {
            String line = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}";
            String result = OpenAiCompatClient.parseSseLine(line);
            assertNull(result);
        }
    }

    // ---- extractDeltaContent() (package-private static) ----

    @Nested
    @DisplayName("extractDeltaContent()")
    class ExtractDeltaContentTests {

        @Test
        @DisplayName("Extracts content from standard OpenAI delta JSON")
        void standardDelta() {
            String json = "{\"choices\":[{\"delta\":{\"content\":\"world\"}}]}";
            assertEquals("world", OpenAiCompatClient.extractDeltaContent(json));
        }

        @Test
        @DisplayName("Returns null when no delta key present")
        void noDeltaKey() {
            String json = "{\"choices\":[{\"message\":{\"content\":\"text\"}}]}";
            assertNull(OpenAiCompatClient.extractDeltaContent(json));
        }

        @Test
        @DisplayName("Extracts content with escaped characters")
        void escapedContent() {
            String json = "{\"delta\":{\"content\":\"line1\\nline2\"}}";
            String result = OpenAiCompatClient.extractDeltaContent(json);
            assertEquals("line1\nline2", result);
        }

        @Test
        @DisplayName("Extracts empty string content")
        void emptyContent() {
            String json = "{\"delta\":{\"content\":\"\"}}";
            assertEquals("", OpenAiCompatClient.extractDeltaContent(json));
        }
    }

    // ---- escapeJson() (package-private static) ----

    @Nested
    @DisplayName("escapeJson()")
    class EscapeJsonTests {

        @Test
        @DisplayName("Returns empty string for null input")
        void nullInput() {
            assertEquals("", OpenAiCompatClient.escapeJson(null));
        }

        @Test
        @DisplayName("Returns same string for plain text")
        void plainText() {
            assertEquals("hello world", OpenAiCompatClient.escapeJson("hello world"));
        }

        @Test
        @DisplayName("Escapes double quotes")
        void escapesQuotes() {
            assertEquals("say \\\"hi\\\"", OpenAiCompatClient.escapeJson("say \"hi\""));
        }

        @Test
        @DisplayName("Escapes backslashes")
        void escapesBackslash() {
            assertEquals("a\\\\b", OpenAiCompatClient.escapeJson("a\\b"));
        }

        @Test
        @DisplayName("Escapes newline characters")
        void escapesNewline() {
            assertEquals("line1\\nline2", OpenAiCompatClient.escapeJson("line1\nline2"));
        }

        @Test
        @DisplayName("Escapes tab characters")
        void escapesTab() {
            assertEquals("col1\\tcol2", OpenAiCompatClient.escapeJson("col1\tcol2"));
        }

        @Test
        @DisplayName("Escapes carriage return")
        void escapesCarriageReturn() {
            assertEquals("a\\rb", OpenAiCompatClient.escapeJson("a\rb"));
        }

        @Test
        @DisplayName("Escapes backspace and form feed")
        void escapesBackspaceAndFormFeed() {
            assertEquals("\\b\\f", OpenAiCompatClient.escapeJson("\b\f"));
        }

        @Test
        @DisplayName("Escapes control characters below 0x20")
        void escapesControlChars() {
            String input = String.valueOf((char) 0x01);
            String result = OpenAiCompatClient.escapeJson(input);
            assertEquals("\\u0001", result);
        }
    }

    // ---- unescapeJsonString() (package-private static) ----

    @Nested
    @DisplayName("unescapeJsonString()")
    class UnescapeJsonStringTests {

        @Test
        @DisplayName("Unescapes simple string terminated by quote")
        void simpleString() {
            // Simulates reading from: "hello" starting at index 0
            assertEquals("hello", OpenAiCompatClient.unescapeJsonString("hello\"rest", 0));
        }

        @Test
        @DisplayName("Unescapes escaped newline")
        void escapedNewline() {
            assertEquals("a\nb", OpenAiCompatClient.unescapeJsonString("a\\nb\"", 0));
        }

        @Test
        @DisplayName("Unescapes escaped tab")
        void escapedTab() {
            assertEquals("a\tb", OpenAiCompatClient.unescapeJsonString("a\\tb\"", 0));
        }

        @Test
        @DisplayName("Unescapes escaped backslash")
        void escapedBackslash() {
            assertEquals("a\\b", OpenAiCompatClient.unescapeJsonString("a\\\\b\"", 0));
        }

        @Test
        @DisplayName("Unescapes escaped quote")
        void escapedQuote() {
            assertEquals("say\"hi", OpenAiCompatClient.unescapeJsonString("say\\\"hi\"", 0));
        }

        @Test
        @DisplayName("Unescapes unicode escape sequence")
        void unicodeEscape() {
            // \u0041 = 'A'
            assertEquals("A", OpenAiCompatClient.unescapeJsonString("\\u0041\"", 0));
        }

        @Test
        @DisplayName("Handles startIdx offset")
        void startIdxOffset() {
            // Starting at index 5 in "xxxxxhello"
            assertEquals("hello", OpenAiCompatClient.unescapeJsonString("xxxxxhello\"", 5));
        }

        @Test
        @DisplayName("Returns empty string for immediate closing quote")
        void emptyString() {
            assertEquals("", OpenAiCompatClient.unescapeJsonString("\"rest", 0));
        }

        @Test
        @DisplayName("Unescapes escaped forward slash")
        void escapedForwardSlash() {
            assertEquals("a/b", OpenAiCompatClient.unescapeJsonString("a\\/b\"", 0));
        }
    }
}
