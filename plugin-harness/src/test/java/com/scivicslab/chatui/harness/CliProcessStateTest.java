package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the state the reader, the turn and the tools share promises per way of sending to its owner: an event told by the reader
 * reaches the turn or the autonomous queue by the turn flag as it is when the event arrives (a {@code tell} keeps the order),
 * a wait for the turn's next event is cancelled by {@code cancel()} and ends as an interruption, as the thread interrupt did,
 * and a call parked from another thread is answered through the owner.
 */
class CliProcessStateTest {

    @Test
    void anEventToldWhileNoTurnRunsIsAutonomousAndOneToldDuringATurnIsTheTurns() throws Exception {
        CliProcess process = new CliProcess("claude", "K", CliConfig.defaults(null));
        process.routeEvent(StreamEvent.text("assistant", "by itself"));
        assertTrue(process.hasAutonomousEvent());
        assertEquals("by itself", process.pollAutonomousEvent(0).content());
        process.routeEvent(StreamEvent.text("result", null));   // the CLI's own turn ends, so it is not part-way through one
        assertEquals("result", process.pollAutonomousEvent(0).type());

        process.beginTurn();
        process.routeEvent(StreamEvent.text("assistant", "for the prompt"));
        assertEquals("for the prompt", process.pollTurnEvent(1_000).content());
        assertTrue(!process.hasAutonomousEvent());
    }

    @Test
    void theResultEndsTheTurnForTheEventsAfterIt() throws Exception {
        CliProcess process = new CliProcess("claude", "K", CliConfig.defaults(null));
        process.beginTurn();
        process.routeEvent(StreamEvent.text("result", null));
        assertTrue(!process.isTurnActive(), "the result is the turn boundary");
        process.routeEvent(StreamEvent.text("assistant", "after"));
        assertTrue(process.hasAutonomousEvent());
    }

    @Test
    void aParkedCallFromAnotherThreadIsAnsweredThroughTheOwner() throws Exception {
        CliProcess process = new CliProcess("claude", "K", CliConfig.defaults(null));
        process.beginTurn();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> process.park("calc", "{}"),
                command -> Thread.ofVirtual().start(command)).get(5, TimeUnit.SECONDS);
        StreamEvent parked = process.pollTurnEvent(5_000);
        assertEquals(StreamEvent.PARKED_CALL, parked.type());
        process.answer(parked.toolUseId(), "42");
        assertEquals("42", call.get(5, TimeUnit.SECONDS));
    }

    @Test
    void aCallParkedWhenNoTurnRunsIsAnsweredAtOnceWithAnError() throws Exception {
        CliProcess process = new CliProcess("claude", "K", CliConfig.defaults(null));
        String answer = process.park("calc", "{}").get(5, TimeUnit.SECONDS);
        assertTrue(answer.startsWith("error: no turn"), answer);
    }

    @Test
    void cancelEndsAWaitForTheTurnsNextEventAsAnInterruption(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("fake-claude");
        Files.writeString(script, "#!/bin/bash\nread line\nsleep 60\n");
        assertTrue(script.toFile().setExecutable(true));
        CliProcess process = new CliProcess(script.toString(), "K", CliConfig.defaults(null));
        CompletableFuture<Integer> turn = CompletableFuture.supplyAsync(() -> {
            try {
                return process.sendPrompt("hello", e -> { });
            } catch (java.io.IOException e) {
                return -99;
            }
        }, command -> Thread.ofVirtual().start(command));
        Thread.sleep(500);   // the turn waits for the CLI's first event
        long cancelledAt = System.nanoTime();
        process.cancel();
        assertEquals(-1, turn.get(5, TimeUnit.SECONDS), "an interrupted turn returns -1");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt) < 2_000);
        assertNull(process.pollTurnEvent(10), "nothing is left in the turn queue");
    }
}
