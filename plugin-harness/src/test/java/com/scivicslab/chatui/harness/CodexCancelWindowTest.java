package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancel that arrives while a Codex run is being started is not lost (the run is stopped), and an event of an older run
 * never ends a later one.
 */
class CodexCancelWindowTest {

    @Test
    void aCancelWhileTheRunStartsIsNotLost(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("fake-codex");
        // exec: the process that is stopped is the one that holds stdout; a child left behind by a stopped shell would keep
        // the pipe open and the turn would wait for it to end
        Files.writeString(fake, "#!/bin/sh\ncat >/dev/null\nexec sleep 30\n");
        assertTrue(fake.toFile().setExecutable(true));
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 1, "sonnet", null, dir);
        for (int round = 0; round < 200; round++) {
            CodexProvider provider = new CodexProvider(settings, "project", "chat" + round, dir, List.of(fake.toString()));
            CountDownLatch began = new CountDownLatch(1);
            provider.startBegan = began::countDown;
            List<ChatEvent> events = new ArrayList<>();
            CompletableFuture<Void> turn = CompletableFuture.runAsync(
                    () -> provider.sendPrompt("hello", null, events::add, ProviderContext.simple(null)),
                    command -> Thread.ofVirtual().start(command));
            assertTrue(began.await(10, TimeUnit.SECONDS));   // the turn's start has begun: a cancel now comes during it
            if (round % 4 != 0) Thread.sleep(java.time.Duration.ofNanos((round % 4) * 700_000L));
            provider.cancel();
            try {
                turn.get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException hung) {
                throw new AssertionError("round " + round + ": the cancel was lost, the run went on");
            }
            assertFalse(provider.processAlive(), "round " + round + ": the process still runs");
            assertTrue(events.stream().noneMatch(e -> "error".equals(e.type())), "round " + round + ": " + events);
        }
    }

    @Test
    void anEventOfAnOlderRunIsDroppedAndOneOfTheCurrentRunIsQueued() throws Exception {
        CodexProvider.CodexState state = new CodexProvider.CodexState();
        state.runId = 2;
        state.offerOfRun(1, StreamEvent.text("output_closed", null));   // a late close of run 1
        state.offerOfRun(2, StreamEvent.text("assistant", "of run 2"));
        assertEquals("of run 2", state.events.pollNow().content());
        assertTrue(state.events.isEmpty(), "run 1's close did not reach run 2's queue");
        state.appendStderrOfRun(1, "late");
        state.appendStderrOfRun(2, "now");
        assertEquals("now", state.stderr.toString());
    }
}
