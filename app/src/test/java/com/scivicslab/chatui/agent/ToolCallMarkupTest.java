package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The markers of the formats open models write their calls in, and nothing else. */
class ToolCallMarkupTest {

    @Test
    void theGlmFormatIsFound_withOrWithoutLineBreaks() {
        assertTrue(ToolCallMarkup.in("<tool_call>edit<arg_key>path</arg_key><arg_value>/a</arg_value></tool_call>"));
        assertTrue(ToolCallMarkup.in("<tool_call>edit\n<arg_key>path</arg_key>\n<arg_value>/a</arg_value>\n</tool_call>"));
        assertTrue(ToolCallMarkup.in("I will edit the file.\n<tool_call>edit<arg_key>p</arg_key></tool_call>"));
    }

    @Test
    void otherKnownFormatsAreFound() {
        assertTrue(ToolCallMarkup.in("<function_call>{\"name\":\"x\"}</function_call>"));
        assertTrue(ToolCallMarkup.in("[TOOL_CALLS][{\"name\":\"x\",\"arguments\":{}}]"));
        assertTrue(ToolCallMarkup.in("<|python_tag|>{\"name\":\"x\"}"));
        assertTrue(ToolCallMarkup.in("<tool_calls>[]</tool_calls>"));
    }

    @Test
    void anAnswerIsNotMarkup() {
        assertFalse(ToolCallMarkup.in("The edit is done; I changed the helper and rebuilt."));
        assertFalse(ToolCallMarkup.in(""));
        assertFalse(ToolCallMarkup.in(null));
        assertFalse(ToolCallMarkup.in("Use <invoke name=\"read\"> to read."), "the withdrawn text format is not looked for here");
    }

    @Test
    void anAnswerThatQuotesTheMarkupInACodeBlockIsStillAnAnswer() {
        assertFalse(ToolCallMarkup.in("GLM writes its calls like this:\n```\n<tool_call>edit<arg_key>p</arg_key></tool_call>\n```\nThat is why."));
        assertTrue(ToolCallMarkup.in("```\nfine\n```\n<tool_call>edit</tool_call>"));
    }
}
