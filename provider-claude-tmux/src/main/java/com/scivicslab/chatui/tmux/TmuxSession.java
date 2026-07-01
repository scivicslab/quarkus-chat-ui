package com.scivicslab.chatui.tmux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A single tmux session that hosts one interactive program (in production, the
 * Claude Code TUI). This is the I/O boundary of the cluster: it shells out to
 * {@code tmux} to inject input ({@code send-keys}) and read output
 * ({@code capture-pane}).
 *
 * <p>This class performs blocking process calls and holds no concurrency
 * control of its own. In the actor design a single {@code PtyWriterActor} owns
 * the write side and a single {@code ScreenExtractorActor} owns the read side,
 * so calls into one instance are already serialised by the actor mailboxes.
 */
public final class TmuxSession {

    private static final long DEFAULT_TIMEOUT_MS = 5_000;

    private final String sessionId;
    private final int width;
    private final int height;

    /**
     * @param sessionId tmux session name (must be unique on the host)
     * @param width     pane width in columns (use a generous width to avoid line truncation)
     * @param height    pane height in rows
     */
    public TmuxSession(String sessionId, int width, int height) {
        this.sessionId = sessionId;
        this.width = width;
        this.height = height;
    }

    /** @return the session name */
    public String sessionId() {
        return sessionId;
    }

    /** Starts a detached session running the login shell. */
    public void create() {
        runChecked(TmuxCommands.newSession(sessionId, width, height));
    }

    /**
     * Starts a detached session running {@code program} directly.
     *
     * @param program the command line to run in the pane (for example {@code "claude"})
     */
    public void createRunning(String program) {
        runChecked(TmuxCommands.newSessionRunning(sessionId, width, height, program));
    }

    /**
     * Injects literal text into the pane (no key-name interpretation, no Enter).
     *
     * @param text the characters to type
     */
    public void sendText(String text) {
        runChecked(TmuxCommands.sendText(sessionId, text));
    }

    /** Sends a single Enter, submitting the current input line. */
    public void sendEnter() {
        runChecked(TmuxCommands.sendEnter(sessionId));
    }

    /**
     * Sends a single named key (for example {@code "Escape"}).
     *
     * @param keyName the tmux key name
     */
    public void sendKey(String keyName) {
        runChecked(TmuxCommands.sendKey(sessionId, keyName));
    }

    /**
     * Types {@code text} and then submits it with Enter. The two are separate
     * tmux calls so the program sees the text settle before the newline.
     *
     * @param text the line to type and submit
     */
    public void sendLine(String text) {
        sendText(text);
        sendEnter();
    }

    /**
     * Captures the currently visible pane as plain text.
     *
     * @return the rendered pane content (ANSI already interpreted by tmux)
     */
    public String capture() {
        return runChecked(TmuxCommands.capturePane(sessionId)).stdout();
    }

    /**
     * Captures the pane including the full scrollback as plain text.
     *
     * @return the rendered pane content plus scrollback history
     */
    public String captureAll() {
        return runChecked(TmuxCommands.captureScrollback(sessionId)).stdout();
    }

    /** @return {@code true} if the session currently exists */
    public boolean isAlive() {
        return run(TmuxCommands.hasSession(sessionId), DEFAULT_TIMEOUT_MS).exitCode() == 0;
    }

    /** Destroys the session. Does not throw if the session is already gone. */
    public void kill() {
        run(TmuxCommands.killSession(sessionId), DEFAULT_TIMEOUT_MS);
    }

    // --- process execution ---

    private static Result runChecked(List<String> argv) {
        Result r = run(argv, DEFAULT_TIMEOUT_MS);
        if (r.exitCode() != 0) {
            throw new TmuxException("tmux command failed (exit " + r.exitCode() + "): "
                    + String.join(" ", argv) + System.lineSeparator() + r.stderr());
        }
        return r;
    }

    private static Result run(List<String> argv, long timeoutMs) {
        try {
            Process p = new ProcessBuilder(argv).start();
            // Output of tmux commands here is small and bounded, so reading fully
            // before waitFor does not risk a pipe-buffer deadlock.
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new TmuxException("tmux command timed out: " + String.join(" ", argv));
            }
            return new Result(p.exitValue(), out, err);
        } catch (IOException e) {
            throw new TmuxException("failed to run tmux command: " + String.join(" ", argv), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmuxException("interrupted while running tmux command: " + String.join(" ", argv), e);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
