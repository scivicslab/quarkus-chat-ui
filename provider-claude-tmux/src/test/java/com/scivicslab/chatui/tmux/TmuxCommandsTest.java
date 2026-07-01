package com.scivicslab.chatui.tmux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link TmuxCommands} argv construction. Pure logic, no tmux
 * process is started, so this is a unit test (no {@code @QuarkusTest}).
 */
@Tag("TmuxTuiDriver_Commands_260630_oo01")
@DisplayName("TmuxCommands — tmux argv construction")
class TmuxCommandsTest {

    @Test
    @DisplayName("newSession sets name and pane size")
    void newSession_setsNameAndSize() {
        assertEquals(
                List.of("tmux", "new-session", "-d", "-s", "s1", "-x", "200", "-y", "50"),
                TmuxCommands.newSession("s1", 200, 50));
    }

    @Test
    @DisplayName("newSessionRunning appends the program as the last argument")
    void newSessionRunning_appendsProgram() {
        assertEquals(
                List.of("tmux", "new-session", "-d", "-s", "s1", "-x", "200", "-y", "50", "claude"),
                TmuxCommands.newSessionRunning("s1", 200, 50, "claude"));
    }

    @Test
    @DisplayName("sendText uses -l so text is literal, not interpreted as key names")
    void sendText_isLiteral() {
        assertEquals(
                List.of("tmux", "send-keys", "-t", "s1", "-l", "hello world"),
                TmuxCommands.sendText("s1", "hello world"));
    }

    @Test
    @DisplayName("sendEnter sends the Enter key name (not literal)")
    void sendEnter_sendsKeyName() {
        assertEquals(
                List.of("tmux", "send-keys", "-t", "s1", "Enter"),
                TmuxCommands.sendEnter("s1"));
    }

    @Test
    @DisplayName("capturePane reads the visible pane as plain text")
    void capturePane_plainVisible() {
        assertEquals(
                List.of("tmux", "capture-pane", "-t", "s1", "-p"),
                TmuxCommands.capturePane("s1"));
    }

    @Test
    @DisplayName("captureScrollback reads the full history as plain text")
    void captureScrollback_fullHistory() {
        assertEquals(
                List.of("tmux", "capture-pane", "-t", "s1", "-p", "-S", "-"),
                TmuxCommands.captureScrollback("s1"));
    }

    @Test
    @DisplayName("hasSession and killSession target the session by name")
    void hasSessionAndKill_targetByName() {
        assertEquals(List.of("tmux", "has-session", "-t", "s1"), TmuxCommands.hasSession("s1"));
        assertEquals(List.of("tmux", "kill-session", "-t", "s1"), TmuxCommands.killSession("s1"));
    }

    @Test
    @DisplayName("sendKey sends a named key (e.g. Escape) interpreted by tmux")
    void sendKey_namedKey() {
        assertEquals(
                List.of("tmux", "send-keys", "-t", "s1", "Escape"),
                TmuxCommands.sendKey("s1", "Escape"));
    }

    @Test
    @DisplayName("pipePane tees pane output to a shell command; off closes it")
    void pipePane_teesAndOff() {
        assertEquals(
                List.of("tmux", "pipe-pane", "-t", "s1", "cat >> /tmp/p.fifo"),
                TmuxCommands.pipePane("s1", "cat >> /tmp/p.fifo"));
        assertEquals(
                List.of("tmux", "pipe-pane", "-t", "s1"),
                TmuxCommands.pipePaneOff("s1"));
    }
}
