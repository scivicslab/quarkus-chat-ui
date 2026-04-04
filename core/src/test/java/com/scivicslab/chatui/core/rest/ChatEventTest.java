package com.scivicslab.chatui.core.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatEventTest {

    @Test
    @DisplayName("delta sets type=delta and content")
    void delta_setsTypeAndContent() {
        ChatEvent e = ChatEvent.delta("hello");
        assertEquals("delta", e.type());
        assertEquals("hello", e.content());
        assertNull(e.sessionId());
        assertNull(e.costUsd());
        assertNull(e.model());
        assertNull(e.busy());
    }

    @Test
    @DisplayName("result(sessionId,cost,duration) sets fields correctly")
    void result_threeArgs_setsFields() {
        ChatEvent e = ChatEvent.result("sess-1", 0.005, 1234L);
        assertEquals("result", e.type());
        assertEquals("sess-1", e.sessionId());
        assertEquals(0.005, e.costUsd(), 1e-9);
        assertEquals(1234L, e.durationMs());
        assertNull(e.content());
    }

    @Test
    @DisplayName("result(sessionId,cost,duration,model,busy) sets model and busy")
    void result_fiveArgs_setsModelAndBusy() {
        ChatEvent e = ChatEvent.result("sess-2", 0.01, 2000L, "claude-sonnet-4-5", false);
        assertEquals("result", e.type());
        assertEquals("claude-sonnet-4-5", e.model());
        assertEquals(false, e.busy());
    }

    @Test
    @DisplayName("error sets type=error and content")
    void error_setsTypeAndContent() {
        ChatEvent e = ChatEvent.error("something went wrong");
        assertEquals("error", e.type());
        assertEquals("something went wrong", e.content());
    }

    @Test
    @DisplayName("info sets type=info and content")
    void info_setsTypeAndContent() {
        ChatEvent e = ChatEvent.info("Processing");
        assertEquals("info", e.type());
        assertEquals("Processing", e.content());
    }

    @Test
    @DisplayName("status sets model, sessionId, busy")
    void status_setsModelSessionBusy() {
        ChatEvent e = ChatEvent.status("claude-opus-4-5", "sess-xyz", true);
        assertEquals("status", e.type());
        assertEquals("claude-opus-4-5", e.model());
        assertEquals("sess-xyz", e.sessionId());
        assertEquals(true, e.busy());
        assertNull(e.content());
    }

    @Test
    @DisplayName("thinking sets type=thinking and content")
    void thinking_setsTypeAndContent() {
        ChatEvent e = ChatEvent.thinking("reasoning...");
        assertEquals("thinking", e.type());
        assertEquals("reasoning...", e.content());
    }

    @Test
    @DisplayName("heartbeat sets type=heartbeat with no other fields")
    void heartbeat_noOtherFields() {
        ChatEvent e = ChatEvent.heartbeat();
        assertEquals("heartbeat", e.type());
        assertNull(e.content());
        assertNull(e.model());
        assertNull(e.sessionId());
        assertNull(e.busy());
    }

    @Test
    @DisplayName("prompt sets promptId, promptType, options")
    void prompt_setsPromptFields() {
        List<String> opts = List.of("Yes", "No");
        ChatEvent e = ChatEvent.prompt("pid-1", "Allow tool?", "ask_user", opts);
        assertEquals("prompt", e.type());
        assertEquals("pid-1", e.promptId());
        assertEquals("Allow tool?", e.content());
        assertEquals("ask_user", e.promptType());
        assertEquals(opts, e.options());
    }

    @Test
    @DisplayName("log sets level, loggerName, content, timestamp")
    void log_setsLogFields() {
        long ts = System.currentTimeMillis();
        ChatEvent e = ChatEvent.log("WARNING", "com.example.Foo", "watch out", ts);
        assertEquals("log", e.type());
        assertEquals("WARNING", e.logLevel());
        assertEquals("com.example.Foo", e.loggerName());
        assertEquals("watch out", e.content());
        assertEquals(ts, e.timestamp());
    }
}
