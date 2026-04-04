package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamEventTest {

    @Test
    @DisplayName("text factory sets type and content")
    void text_setsTypeAndContent() {
        StreamEvent e = StreamEvent.text("assistant", "Hello!");
        assertEquals("assistant", e.type());
        assertEquals("Hello!", e.content());
        assertNull(e.sessionId());
        assertEquals(-1, e.costUsd(), 1e-9);
        assertEquals(-1, e.durationMs());
        assertFalse(e.isError());
        assertNull(e.rawJson());
    }

    @Test
    @DisplayName("result factory sets sessionId, cost, duration")
    void result_setsSessionCostDuration() {
        StreamEvent e = StreamEvent.result("sess-abc", 0.003, 800L);
        assertEquals("result", e.type());
        assertEquals("sess-abc", e.sessionId());
        assertEquals(0.003, e.costUsd(), 1e-9);
        assertEquals(800L, e.durationMs());
        assertFalse(e.isError());
        assertNull(e.content());
    }

    @Test
    @DisplayName("error factory sets type=error and isError=true")
    void error_setsTypeAndFlag() {
        StreamEvent e = StreamEvent.error("parse failed");
        assertEquals("error", e.type());
        assertEquals("parse failed", e.content());
        assertTrue(e.isError());
    }

    @Test
    @DisplayName("prompt factory sets all prompt fields")
    void prompt_setsPromptFields() {
        List<String> opts = List.of("Yes", "No");
        StreamEvent e = StreamEvent.prompt("pid-1", "Allow?", "ask_user", opts, "{\"raw\":true}");
        assertEquals("prompt", e.type());
        assertEquals("pid-1", e.promptId());
        assertEquals("Allow?", e.content());
        assertEquals("ask_user", e.promptType());
        assertEquals(opts, e.options());
        assertEquals("{\"raw\":true}", e.rawJson());
        assertTrue(e.isPrompt());
    }

    @Test
    @DisplayName("hasContent returns false for null or empty content")
    void hasContent_nullOrEmpty_returnsFalse() {
        StreamEvent nullContent = new StreamEvent("assistant", null, null, -1, -1, false, null);
        StreamEvent emptyContent = new StreamEvent("assistant", "", null, -1, -1, false, null);
        assertFalse(nullContent.hasContent());
        assertFalse(emptyContent.hasContent());
    }

    @Test
    @DisplayName("hasContent returns true for non-empty content")
    void hasContent_nonEmpty_returnsTrue() {
        StreamEvent e = StreamEvent.text("assistant", "text");
        assertTrue(e.hasContent());
    }

    @Test
    @DisplayName("isPrompt returns false for non-prompt types")
    void isPrompt_nonPrompt_returnsFalse() {
        StreamEvent e = StreamEvent.text("assistant", "hello");
        assertFalse(e.isPrompt());
    }
}
