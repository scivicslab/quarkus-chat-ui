package com.scivicslab.chatui.tmux;

import java.util.List;

/**
 * Pure builders for the {@code tmux} command lines used to drive an interactive
 * program inside a tmux session.
 *
 * <p>Each method returns the argv {@link List} to hand to a {@code ProcessBuilder};
 * no process is started here. Keeping command construction separate from
 * execution lets the argv be unit-tested without touching tmux.
 */
public final class TmuxCommands {

    private TmuxCommands() {
    }

    /**
     * Creates a detached session of the given pane size running the login shell.
     *
     * @param sessionId session name
     * @param width     pane width in columns (set generously to avoid line truncation)
     * @param height    pane height in rows
     * @return the argv for {@code tmux new-session}
     */
    static List<String> newSession(String sessionId, int width, int height) {
        return List.of("tmux", "new-session", "-d", "-s", sessionId,
                "-x", Integer.toString(width), "-y", Integer.toString(height));
    }

    /**
     * Creates a detached session of the given pane size running {@code program}
     * directly (instead of a shell).
     *
     * @param sessionId session name
     * @param width     pane width in columns
     * @param height    pane height in rows
     * @param program   the command line to run in the pane
     * @return the argv for {@code tmux new-session} with a program
     */
    static List<String> newSessionRunning(String sessionId, int width, int height, String program) {
        return List.of("tmux", "new-session", "-d", "-s", sessionId,
                "-x", Integer.toString(width), "-y", Integer.toString(height), program);
    }

    /**
     * Sends literal text to the pane without interpreting key names ({@code -l}).
     *
     * @param sessionId session name
     * @param text      the literal characters to inject
     * @return the argv for {@code tmux send-keys -l}
     */
    static List<String> sendText(String sessionId, String text) {
        return List.of("tmux", "send-keys", "-t", sessionId, "-l", text);
    }

    /**
     * Sends a single Enter key, submitting whatever is in the input line.
     *
     * @param sessionId session name
     * @return the argv for {@code tmux send-keys Enter}
     */
    static List<String> sendEnter(String sessionId) {
        return List.of("tmux", "send-keys", "-t", sessionId, "Enter");
    }

    /**
     * Sends a single named key (for example {@code "Escape"} to cancel generation,
     * or {@code "Enter"}). The key name is interpreted by tmux, not sent literally.
     *
     * @param sessionId session name
     * @param keyName   the tmux key name
     * @return the argv for {@code tmux send-keys <keyName>}
     */
    static List<String> sendKey(String sessionId, String keyName) {
        return List.of("tmux", "send-keys", "-t", sessionId, keyName);
    }

    /**
     * Captures the currently visible pane as plain text.
     *
     * @param sessionId session name
     * @return the argv for {@code tmux capture-pane -p}
     */
    static List<String> capturePane(String sessionId) {
        return List.of("tmux", "capture-pane", "-t", sessionId, "-p");
    }

    /**
     * Captures the pane including the full scrollback as plain text.
     *
     * @param sessionId session name
     * @return the argv for {@code tmux capture-pane -p -S -}
     */
    static List<String> captureScrollback(String sessionId) {
        return List.of("tmux", "capture-pane", "-t", sessionId, "-p", "-S", "-");
    }

    /**
     * Tests whether the session exists (exit code 0 means yes).
     *
     * @param sessionId session name
     * @return the argv for {@code tmux has-session}
     */
    static List<String> hasSession(String sessionId) {
        return List.of("tmux", "has-session", "-t", sessionId);
    }

    /**
     * Destroys the session.
     *
     * @param sessionId session name
     * @return the argv for {@code tmux kill-session}
     */
    static List<String> killSession(String sessionId) {
        return List.of("tmux", "kill-session", "-t", sessionId);
    }

    /**
     * Pipes the pane's output stream to {@code shellCommand} (run via {@code /bin/sh -c}).
     * Used to tee the raw byte stream into a fifo for case-B blocking reads.
     *
     * @param sessionId    session name
     * @param shellCommand the shell command receiving pane output on its stdin
     *                     (for example {@code "cat >> /path/pane.fifo"})
     * @return the argv for {@code tmux pipe-pane}
     */
    static List<String> pipePane(String sessionId, String shellCommand) {
        return List.of("tmux", "pipe-pane", "-t", sessionId, shellCommand);
    }

    /**
     * Closes any open pipe on the pane (a {@code pipe-pane} with no command toggles it off).
     *
     * @param sessionId session name
     * @return the argv for {@code tmux pipe-pane} with no command
     */
    static List<String> pipePaneOff(String sessionId) {
        return List.of("tmux", "pipe-pane", "-t", sessionId);
    }
}
