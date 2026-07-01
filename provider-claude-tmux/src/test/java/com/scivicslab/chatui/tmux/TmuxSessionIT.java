package com.scivicslab.chatui.tmux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link TmuxSession}: drives a real tmux session and
 * proves the send-keys / capture-pane round trip works from Java.
 *
 * <p>Touches an external program (tmux), so by the testing standard it is an
 * integration test ({@code *IT.java}, run with {@code mvn verify}). It uses a
 * benign shell command (not Claude Code) so it needs no LLM auth, and it is
 * skipped when tmux is absent.
 */
@Tag("TmuxTuiDriver_Session_260630_oo01")
@DisplayName("TmuxSession — real tmux send-keys / capture-pane round trip")
class TmuxSessionIT {

    @Test
    @DisplayName("a shell command typed via sendLine appears in capture output")
    void sendLine_thenCapture_seesCommandOutput() throws InterruptedException {
        assumeTrue(tmuxAvailable(), "tmux not installed; skipping integration test");

        String sessionId = "chatui-tmux-it-" + UUID.randomUUID();
        String token = "RT_" + UUID.randomUUID().toString().replace("-", "");
        TmuxSession session = new TmuxSession(sessionId, 80, 24);

        try {
            session.createRunning("/bin/bash --noprofile --norc");
            assertTrue(session.isAlive(), "session should be alive after create");

            // Give the shell a moment to initialise its prompt before typing.
            Thread.sleep(500);
            session.sendLine("echo " + token);

            String capture = pollUntilOutputLine(session, token, 8_000);
            assertTrue(containsOutputLine(capture, token),
                    "the command output line '" + token + "' should appear in capture; got:\n" + capture);
        } finally {
            session.kill();
        }
        assertFalse(session.isAlive(), "session should be gone after kill");
    }

    /** Polls capture until a standalone output line equal to {@code token} appears or time runs out. */
    private static String pollUntilOutputLine(TmuxSession session, String token, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String capture = "";
        while (System.currentTimeMillis() < deadline) {
            capture = session.captureAll();
            if (containsOutputLine(capture, token)) {
                return capture;
            }
            Thread.sleep(200);
        }
        return capture;
    }

    /**
     * Returns true if a line (stripped) equals {@code token}. The shell echoes
     * the typed command "echo TOKEN" too, so we require a standalone TOKEN line
     * to prove the command actually executed, not merely that input was typed.
     */
    private static boolean containsOutputLine(String capture, String token) {
        return capture.lines().map(String::strip).anyMatch(token::equals);
    }

    private static boolean tmuxAvailable() {
        try {
            Process p = new ProcessBuilder("tmux", "-V").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
