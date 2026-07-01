package com.scivicslab.chatui.tmux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link PipePaneReader}: proves the case-B path works end
 * to end against real tmux — pane output is teed through a fifo and blocking-read
 * from Java, with activity firing on byte arrival.
 *
 * <p>Touches tmux, so it is an integration test ({@code *IT.java}, {@code mvn verify}).
 * Uses a benign shell (no Claude Code, no auth) and is skipped when tmux is absent.
 */
@Tag("TmuxTuiDriver_PipePane_260630_oo01")
@DisplayName("PipePaneReader — real tmux pipe-pane byte stream")
class PipePaneReaderIT {

    @Test
    @DisplayName("pane output bytes are delivered to the blocking reader")
    void paneOutput_reachesReader() throws Exception {
        assumeTrue(tmuxAvailable(), "tmux not installed; skipping integration test");

        String sessionId = "chatui-pipe-it-" + UUID.randomUUID();
        TmuxSession session = new TmuxSession(sessionId, 80, 24);
        AtomicInteger activityCount = new AtomicInteger();
        StringBuilder seen = new StringBuilder();
        PipePaneReader reader = new PipePaneReader(
                sessionId,
                activityCount::incrementAndGet,
                bytes -> {
                    synchronized (seen) {
                        seen.append(new String(bytes, StandardCharsets.UTF_8));
                    }
                });

        try {
            session.createRunning("/bin/bash --noprofile --norc");
            Thread.sleep(500);          // shell prompt
            reader.start();
            Thread.sleep(300);          // let the pipe attach

            String token = "PIPE_" + UUID.randomUUID().toString().replace("-", "");
            session.sendLine("echo " + token);

            long deadline = System.currentTimeMillis() + 8_000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (seen) {
                    if (seen.toString().contains(token)) {
                        break;
                    }
                }
                Thread.sleep(100);
            }

            assertTrue(activityCount.get() > 0, "byte activity should have fired");
            synchronized (seen) {
                assertTrue(seen.toString().contains(token),
                        "pipe-pane should carry pane output '" + token + "'; got:\n" + seen);
            }
        } finally {
            reader.close();
            session.kill();
        }
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
