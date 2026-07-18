package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CliProcess} event routing — the guard that keeps autonomous output from
 * being mis-delivered as the response to the next prompt. Exercises the queue-routing logic
 * directly, without starting a subprocess.
 */
class CliProcessRoutingTest {

    private CliProcess proc;

    @BeforeEach
    void setUp() {
        proc = new CliProcess("claude", "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet"));
    }

    @Test
    @DisplayName("with no turn active, events route to the autonomous queue")
    void noTurn_routesToAutonomous() throws Exception {
        assertFalse(proc.isTurnActive());
        StreamEvent e = StreamEvent.text("assistant", "hi");
        proc.routeEvent(e);

        assertTrue(proc.hasAutonomousEvent());
        assertSame(e, proc.pollAutonomousEvent(0));
        assertFalse(proc.hasAutonomousEvent());
    }

    @Test
    @DisplayName("during a turn, events route to the turn queue, not the autonomous queue")
    void duringTurn_routesToTurnQueue() throws Exception {
        proc.beginTurn();
        assertTrue(proc.isTurnActive());

        StreamEvent e = StreamEvent.text("assistant", "part");
        proc.routeEvent(e);

        assertFalse(proc.hasAutonomousEvent());
        assertSame(e, proc.pollTurnEvent(100));
    }

    @Test
    @DisplayName("the result event ends the turn; post-result events become autonomous (the guard)")
    void resultEndsTurn_postResultIsAutonomous() throws Exception {
        proc.beginTurn();
        proc.routeEvent(StreamEvent.text("assistant", "answer"));
        proc.routeEvent(StreamEvent.result(null, -1, -1)); // turn boundary

        assertFalse(proc.isTurnActive());

        // A completion notification arriving after the turn must NOT be consumable as turn output;
        // it belongs to the autonomous queue so the next prompt cannot inherit it.
        StreamEvent late = StreamEvent.text("assistant", "background job finished");
        proc.routeEvent(late);

        assertTrue(proc.hasAutonomousEvent());
        assertSame(late, proc.pollAutonomousEvent(0));
    }

    @Test
    @DisplayName("autonomous queue is empty by default")
    void autonomous_emptyByDefault() throws Exception {
        assertFalse(proc.hasAutonomousEvent());
        assertNull(proc.pollAutonomousEvent(0));
    }
}
