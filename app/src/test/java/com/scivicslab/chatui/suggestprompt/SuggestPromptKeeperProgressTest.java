package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.FakeTurns;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.HandDrivenModel;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Mailbox;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Repo;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state the keeper reports for the Suggest prompt dialog ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}, the
 * progress of an update): whatever started the update, the dialog can read how far it is.
 */
class SuggestPromptKeeperProgressTest {

    private static final String SQUAD = "project1/chat-02";

    private final Repo repo = new Repo();
    private final FakeTurns turns = new FakeTurns();
    private final HandDrivenModel model = new HandDrivenModel();
    private final Mailbox mailbox = new Mailbox();
    private final AtomicLong now = new AtomicLong(5000L);
    private final SuggestPromptKeeper keeper = new SuggestPromptKeeper(SQUAD, repo, turns, model, model,
            () -> SuggestFrame.STANDALONE, now::get);

    SuggestPromptKeeperProgressTest() {
        mailbox.bind(keeper);
        keeper.startNewFrame();
    }

    @Test
    void beforeAnyUpdateTheKeeperIsIdle() {
        assertEquals(SuggestPromptKeeper.Progress.IDLE, keeper.progress());
    }

    @Test
    void anUpdateStartedByTheIntervalReportsACallOutFromTheStartingTurn() {
        turns.finish(3);

        keeper.bringUpToDate();   // what the timekeeper's tick sends

        SuggestPromptKeeper.Progress p = keeper.progress();
        assertTrue(p.calling());
        assertTrue(p.running());
        assertEquals(5000L, p.startedAt());
        assertEquals(0, p.fromTurn());
    }

    @Test
    void anUpdateStartedByTheButtonReportsTheSame() {
        turns.finish(3);

        assertEquals(SuggestPromptKeeper.Analysis.Kind.STARTED, keeper.analyzeNow().kind());

        SuggestPromptKeeper.Progress p = keeper.progress();
        assertTrue(p.calling());
        assertTrue(p.running());
        assertEquals(0, p.fromTurn());
    }

    @Test
    void theUpdateEndsWhenTheFrameHasCaughtUp() {
        turns.finish(3);
        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(SuggestPromptKeeper.Progress.IDLE, keeper.progress());
        assertEquals(3, keeper.frame().digestedTurn());
    }

    @Test
    void theUpdateStaysRunningBetweenTheCallsOfACatchUp() {
        turns.finish(2);
        keeper.bringUpToDate();
        now.set(9000L);
        turns.finish(5);          // turns finished while the first call was out
        model.finishOneCall();
        mailbox.step();           // the reply is applied; the keeper has sent itself the next update

        SuggestPromptKeeper.Progress between = keeper.progress();
        assertFalse(between.calling(), "no call is out in the gap");
        assertTrue(between.running(), "but the update goes on");
        assertEquals(5000L, between.startedAt(), "it began at the first call");
        assertEquals(0, between.fromTurn());

        mailbox.step();           // the next update starts its call
        assertTrue(keeper.progress().calling());
        assertEquals(5000L, keeper.progress().startedAt());
        model.finishOneCall();
        mailbox.drain();
        assertEquals(SuggestPromptKeeper.Progress.IDLE, keeper.progress());
    }

    @Test
    void aFailedCallEndsTheUpdate() {
        turns.finish(2);
        model.then(new java.io.IOException("the LLM timed out"));
        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(SuggestPromptKeeper.Progress.IDLE, keeper.progress());
        assertEquals(0, keeper.frame().digestedTurn(), "nothing was read");
    }

    @Test
    void aReplyWithoutAPatchEndsTheUpdate() {
        turns.finish(2);
        model.then("I cannot do that.");
        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(SuggestPromptKeeper.Progress.IDLE, keeper.progress());
    }

    @Test
    void aClearDuringTheCallEndsTheUpdateAndTheLateReplyChangesNothing() {
        turns.finish(2);
        keeper.bringUpToDate();
        keeper.startNewFrame();

        assertFalse(keeper.progress().running(), "the old frame's update does not show on the new frame");
        model.finishOneCall();
        mailbox.drain();
        assertEquals(SuggestPromptKeeper.Progress.IDLE, keeper.progress());
    }

    @Test
    void theViewCountsTurnsReadOutOfTurnsToRead() {
        SuggestFrame frame = new SuggestFrame(SQUAD, 1, SuggestFrame.STANDALONE, "", 12, 3, 0L, java.util.List.of());
        SuggestPromptKeeper.Progress running = new SuggestPromptKeeper.Progress(true, true, 7000L, 4);

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) SuggestFrameView.of(frame, 30, running).get("analysis");

        assertEquals(true, a.get("calling"));
        assertEquals(true, a.get("running"));
        assertEquals(7000L, a.get("startedAt"));
        assertEquals(4, a.get("fromTurn"));
        assertEquals(8, a.get("readTurns"), "12 digested, started from 4");
        assertEquals(26, a.get("totalTurns"), "30 finished, started from 4");
    }

    @Test
    void theViewReportsNothingToReadWhenNoUpdateRuns() {
        SuggestFrame frame = new SuggestFrame(SQUAD, 1, SuggestFrame.STANDALONE, "", 12, 3, 0L, java.util.List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) SuggestFrameView.of(frame, 30).get("analysis");

        assertEquals(false, a.get("running"));
        assertEquals(0, a.get("readTurns"));
        assertEquals(0, a.get("totalTurns"));
    }
}
