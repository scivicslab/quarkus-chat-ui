package com.scivicslab.chatui.tmux;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for the full case-B monitor wired as actors against real tmux:
 * {@link PipePaneReader} byte activity → {@link OutputWatcher} actor → idle settle →
 * {@code capture-pane} → {@link ScreenExtractor} → event sink.
 *
 * <p>The reader thread tells {@code recordActivity}; the test loop tells {@code tick}.
 * All watcher state is mutated only on the actor thread (no locks). Uses a benign
 * bash substrate that prints a {@code ●}-marked line the extractor recognises, so no
 * Claude Code or auth is needed. Skipped when tmux is absent.
 */
@Tag("TmuxTuiDriver_WatcherWiring_260630_oo01")
@DisplayName("OutputWatcher — real tmux pipe-pane to extracted event, wired as actors")
class OutputWatcherIT {

    @Test
    @DisplayName("a marked line printed in the pane surfaces as an AssistantMessage")
    void paneOutput_throughWatcher_yieldsAssistantMessage() throws Exception {
        assumeTrue(tmuxAvailable(), "tmux not installed; skipping integration test");

        String sessionId = "chatui-ow-it-" + UUID.randomUUID();
        TmuxSession session = new TmuxSession(sessionId, 100, 30);
        List<ExtractedEvent> collected = Collections.synchronizedList(new ArrayList<>());

        ActorSystem system = new ActorSystem("output-watcher-it");
        OutputWatcher watcher = new OutputWatcher(
                session::captureAll, new ScreenExtractor(), new IdleDebouncer(400));
        ActorRef<OutputWatcher> watcherRef = system.actorOf("watcher-" + sessionId, watcher);

        PipePaneReader reader = new PipePaneReader(
                sessionId,
                () -> watcherRef.tell(w -> w.recordActivity(System.currentTimeMillis())));

        try {
            session.createRunning("/bin/bash --noprofile --norc");
            Thread.sleep(500);          // shell prompt
            reader.start();
            Thread.sleep(300);          // pipe attaches

            session.sendLine("echo '● hello from watcher'");   // ● hello from watcher

            long deadline = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < deadline) {
                TickResult r = watcherRef.ask(w -> w.tick(System.currentTimeMillis())).join();
                collected.addAll(r.events());
                if (hasAssistantMessage(collected)) {
                    break;
                }
                Thread.sleep(100);
            }

            assertTrue(hasAssistantMessage(collected),
                    "watcher should surface an AssistantMessage; collected=" + collected);
            AssistantMessage msg = collected.stream()
                    .filter(e -> e instanceof AssistantMessage)
                    .map(e -> (AssistantMessage) e)
                    .findFirst().orElseThrow();
            assertTrue(msg.text().contains("hello from watcher"),
                    "unexpected message text: " + msg.text());
        } finally {
            reader.close();
            session.kill();
            system.terminate();
        }
    }

    private static boolean hasAssistantMessage(List<ExtractedEvent> events) {
        synchronized (events) {
            return events.stream().anyMatch(e -> e instanceof AssistantMessage);
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
