package com.scivicslab.chatui.openaicompat.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.openaicompat.ToolDefinition;

/** The request that asks for an answer in text keeps the tools and sends {@code tool_choice: "none"}. */
class ToolChoiceTest {

    private static final List<ChatMessage> ONE_TURN = List.of(new ChatMessage.User("hello", List.of()));
    private static final List<ToolDefinition> TOOLS = List.of(new ToolDefinition("bash", "Runs a command",
            "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}"));

    @Test
    void anOrdinaryRequestLetsTheModelChooseATool() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true, TOOLS, null);
        assertTrue(body.contains("\"tool_choice\":\"auto\""), body);
    }

    @Test
    void aRequestForAnAnswerInTextKeepsTheToolsAndForbidsCallingThem() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true, TOOLS, null, true);
        assertTrue(body.contains("\"tools\":["), body);
        assertTrue(body.contains("\"tool_choice\":\"none\""), body);
        assertFalse(body.contains("\"tool_choice\":\"auto\""), body);
    }

    @Test
    void aRequestWithNoToolsSendsNoToolChoice() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true, List.of(), null, true);
        assertFalse(body.contains("tool_choice"), body);
    }
}
