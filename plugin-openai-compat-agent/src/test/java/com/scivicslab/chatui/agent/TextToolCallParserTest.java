package com.scivicslab.chatui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextToolCallParserTest {

    @Test
    @DisplayName("an <invoke> block with two parameters and a reason becomes one call with a JSON object")
    void parsesInvokeBlock() {
        String reply = "Let me look.\n<invoke name=\"read_file\">\n<parameter name=\"path\">/a/b.txt</parameter>\n"
                + "<parameter name=\"max_length\">100</parameter>\n<reason>need the text</reason>\n</invoke>";
        List<ToolCall> calls = TextToolCallParser.parse(reply);
        assertEquals(1, calls.size());
        assertEquals("read_file", calls.get(0).name());
        assertEquals("{\"path\":\"/a/b.txt\",\"max_length\":\"100\",\"reason\":\"need the text\"}", calls.get(0).argumentsJson());
        assertEquals("Let me look.", TextToolCallParser.stripToolCallBlocks(reply));
    }

    @Test
    @DisplayName("a missing </parameter> and leaked <|\"|> tokens are tolerated")
    void tolerant() {
        String reply = "<invoke name=\"list_directory\">\n<parameter name=\"path\"><|\"|>/x<|\"|>\n</invoke>";
        List<ToolCall> calls = TextToolCallParser.parse(reply);
        assertEquals(1, calls.size());
        assertEquals("{\"path\":\"/x\"}", calls.get(0).argumentsJson());
    }

    @Test
    @DisplayName("plain prose is not a tool call")
    void prose() {
        assertTrue(TextToolCallParser.parse("The answer is 42.").isEmpty());
        assertFalse(TextToolCallParser.looksLikeTextToolCall("no tools here"));
    }
}
