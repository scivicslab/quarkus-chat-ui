package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.FakeTurns;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.HandDrivenModel;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Mailbox;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Repo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update rules of {@link SuggestPromptKeeper} ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}, the update and
 * test sections), with the LLM, the repository and the finished turns replaced by doubles, and the message queue by a
 * list the test drains.
 */
class SuggestPromptKeeperTest {

    private static final String SQUAD = "project1/chat-02";

    private final Repo repo = new Repo();
    private final FakeTurns turns = new FakeTurns();
    private final HandDrivenModel model = new HandDrivenModel();
    private final Mailbox mailbox = new Mailbox();
    private final SuggestPromptKeeper keeper = new SuggestPromptKeeper(SQUAD, repo, turns, model, model,
            () -> SuggestFrame.STANDALONE, () -> 1000L);

    SuggestPromptKeeperTest() {
        mailbox.bind(keeper);
    }

    private void startedAndRestored() {
        keeper.startNewFrame();
    }

    // ---- one update ----

    @Test
    void oneUpdateAppliesThePatchAdvancesDigestedTurnAndSaves() {
        startedAndRestored();
        turns.finish(2);

        keeper.bringUpToDate();
        assertTrue(keeper.calling());
        model.finishOneCall();
        mailbox.drain();

        assertEquals(1, model.prompts.size());
        assertFalse(keeper.calling());
        assertEquals(2, keeper.frame().digestedTurn());
        assertEquals("We rebuild it.", keeper.frame().overview());
        assertEquals(1, keeper.frame().topics().size());
        assertEquals(1, keeper.frame().revision());
        assertEquals(keeper.frame(), repo.loadCurrent(SQUAD).orElseThrow(), "what is in hand is what was saved");
        assertEquals(1000L, keeper.frame().updatedAt());
    }

    @Test
    void noModelCallWhenTheFinishedTurnIsNotAboveDigestedTurn() {
        startedAndRestored();
        turns.finish(0);

        keeper.bringUpToDate();

        assertTrue(model.waiting.isEmpty());
        assertTrue(turns.reads.isEmpty(), "nothing is read when there is nothing new");
    }

    @Test
    void aSecondUpdateWithNothingNewMakesNoCall() {
        startedAndRestored();
        turns.finish(1);
        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        keeper.bringUpToDate();

        assertTrue(model.waiting.isEmpty());
        assertEquals(1, model.prompts.size());
    }

    @Test
    void onlyFinishedTurnsAreReadWhileAnotherTurnRuns() {
        startedAndRestored();
        turns.finish(3);
        turns.rows.add(new SuggestPatchPrompt.Message(4, true, "the running turn's question"));   // turn 4 has not finished

        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(3, keeper.frame().digestedTurn());
        assertEquals(3, turns.reads.get(0)[1], "the read stops at the finished turn");
        assertFalse(model.prompts.get(0).contains("the running turn's question"));
    }

    @Test
    void turnsWithoutAnyReplyOnlyMoveTheMark() {
        startedAndRestored();
        turns.finished = 3;   // cancelled or failed turns: no rows to learn from

        keeper.bringUpToDate();

        assertTrue(model.waiting.isEmpty(), "no call when there is nothing to read");
        assertEquals(3, keeper.frame().digestedTurn());
        assertEquals(3, repo.loadCurrent(SQUAD).orElseThrow().digestedTurn());
        assertEquals(0, keeper.frame().revision(), "no patch was applied");
    }

    // ---- one call at a time ----

    @Test
    void anUpdateArrivingDuringACallMakesNoSecondCall() {
        startedAndRestored();
        turns.finish(2);

        keeper.bringUpToDate();
        keeper.bringUpToDate();
        keeper.bringUpToDate();

        assertEquals(1, model.waiting.size());
        model.finishOneCall();
        mailbox.drain();
        assertEquals(1, model.prompts.size());
    }

    // ---- catch-up ----

    @Test
    void catchUpGoesOnWithoutWaitingForTheInterval() {
        startedAndRestored();
        // Each turn holds 3000 characters of answer, so one request holds four turns (the 12000-character cap) and the
        // first update cannot reach turn 10.
        for (int t = 1; t <= 10; t++) {
            turns.rows.add(new SuggestPatchPrompt.Message(t, true, "q" + t));
            turns.rows.add(new SuggestPatchPrompt.Message(t, false, "a".repeat(2900)));
        }
        turns.finished = 10;

        keeper.bringUpToDate();   // the only message the interval ever sends
        int calls = 0;
        while (!model.waiting.isEmpty()) {
            assertEquals(1, model.waiting.size(), "exactly one call is out at a time");
            model.finishOneCall();
            mailbox.drain();   // the keeper sends itself bringUpToDate and it runs here
            calls++;
            assertTrue(calls < 20, "the catch-up ends");
        }

        assertEquals(10, keeper.frame().digestedTurn());
        assertTrue(calls >= 3, "the cap splits ten turns into several requests: " + calls);
        assertEquals(calls, model.prompts.size());
    }

    @Test
    void atTheInputCapDigestedTurnAdvancesToWhatWasRead() {
        startedAndRestored();
        for (int t = 1; t <= 6; t++) {
            turns.rows.add(new SuggestPatchPrompt.Message(t, false, "a".repeat(2900)));
        }
        turns.finished = 6;

        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.step();   // the reply

        assertTrue(keeper.frame().digestedTurn() < 6 && keeper.frame().digestedTurn() >= 1,
                "the first request did not reach turn 6: " + keeper.frame().digestedTurn());
        assertEquals(1, mailbox.messages.size(), "and the keeper sent itself another update");
    }

    @Test
    void whenItHasCaughtUpItSendsNothingMore() {
        startedAndRestored();
        turns.finish(2);

        keeper.bringUpToDate();
        model.finishOneCall();

        assertEquals(1, mailbox.messages.size(), "only the reply itself is waiting");
        mailbox.drain();
        assertTrue(mailbox.messages.isEmpty());
        assertTrue(model.waiting.isEmpty());
    }

    // ---- failure ----

    @Test
    void afterAnLlmFailureNothingIsReadUntilTheNextInterval() {
        startedAndRestored();
        turns.finish(5);
        model.then(new RuntimeException("connection refused"));

        keeper.bringUpToDate();
        int readsBefore = turns.reads.size();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(0, keeper.frame().digestedTurn(), "digested_turn does not move");
        assertTrue(mailbox.messages.isEmpty(), "it does not send itself another update");
        assertTrue(model.waiting.isEmpty(), "and no second call is out");
        assertEquals(readsBefore, turns.reads.size(), "and nothing more was read");
        assertFalse(keeper.calling());

        keeper.bringUpToDate();   // the next interval
        assertEquals(1, model.waiting.size(), "the next interval retries");
        model.finishOneCall();
        mailbox.drain();
        assertEquals(5, keeper.frame().digestedTurn());
    }

    @Test
    void aTimeoutIsAFailureAndDigestedTurnDoesNotAdvance() {
        startedAndRestored();
        turns.finish(2);
        model.then(new TimeoutException("2 minutes"));

        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(0, keeper.frame().digestedTurn());
        assertEquals(0, keeper.frame().revision());
        assertTrue(mailbox.messages.isEmpty());
    }

    @Test
    void aReplyWithNoPatchLeavesTheTurnsUnreadAndStops() {
        startedAndRestored();
        turns.finish(2);
        model.then("I cannot do this.");

        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(0, keeper.frame().digestedTurn());
        assertTrue(mailbox.messages.isEmpty());
        assertTrue(model.waiting.isEmpty());
    }

    @Test
    void aFailureToReadTheLogMakesNoCall() {
        startedAndRestored();
        turns.finish(2);
        turns.readFails = new IllegalStateException("the I/O log could not be read");

        keeper.bringUpToDate();

        assertTrue(model.waiting.isEmpty());
        assertFalse(keeper.calling());
        assertEquals(0, keeper.frame().digestedTurn());
    }

    // ---- /clear and the generations ----

    @Test
    void aNewFrameRaisesTheEpochStartsEmptyAndKeepsTheOldRows() {
        startedAndRestored();
        turns.finish(2);
        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();
        SuggestFrame before = keeper.frame();

        keeper.startNewFrame();

        assertEquals(before.epoch() + 1, keeper.frame().epoch());
        assertTrue(keeper.frame().isEmpty());
        assertEquals(0, keeper.frame().digestedTurn());
        assertEquals(before, repo.frames.get(SQUAD).get(before.epoch()), "the rows of the generation before stay");
        assertEquals(keeper.frame(), repo.loadCurrent(SQUAD).orElseThrow());
    }

    @Test
    void anAnswerForTheGenerationBeforeIsDropped() {
        startedAndRestored();
        turns.finish(2);
        keeper.bringUpToDate();   // the call is out
        keeper.startNewFrame();   // /clear while it waits
        int saves = repo.saves.size();

        model.finishOneCall();
        mailbox.drain();

        assertTrue(keeper.frame().isEmpty(), "the new generation has nothing of the old answer");
        assertEquals(0, keeper.frame().digestedTurn());
        assertEquals(saves, repo.saves.size(), "and nothing was saved");
        assertFalse(keeper.calling());
    }

    @Test
    void afterAClearTheTurnNumbersStartAgainAndAreReadFromTheStart() {
        startedAndRestored();
        turns.finish(3);
        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();
        assertEquals(3, keeper.frame().digestedTurn());

        keeper.startNewFrame();
        turns.rows.clear();
        turns.finished = 0;
        turns.finish(1);   // turn 1 of the new session
        keeper.bringUpToDate();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(1, keeper.frame().digestedTurn());
        assertEquals(2, keeper.frame().epoch());
    }

    // ---- start and restart ----

    @Test
    void aNewConversationStartsAtEpochOne() {
        keeper.startIfNew();

        assertEquals(1, keeper.frame().epoch());
        assertEquals(1, repo.loadCurrent(SQUAD).orElseThrow().epoch());
    }

    @Test
    void aRecreatedConversationWithTheNameOfADeletedOneStartsANewEpoch() {
        repo.save(SuggestFrame.empty(SQUAD, 1, SuggestFrame.STANDALONE));
        repo.save(SuggestFrame.empty(SQUAD, 2, SuggestFrame.STANDALONE));

        keeper.startIfNew();

        assertEquals(3, keeper.frame().epoch());
        assertEquals(2, repo.frames.get(SQUAD).get(2).epoch(), "the old rows are not touched");
    }

    @Test
    void aRestoredConversationContinuesTheNewestEpochAndStartIfNewDoesNothing() {
        SuggestFrame saved = new SuggestFrame(SQUAD, 4, SuggestFrame.SUPERIOR, "kept", 7, 3, 5L, java.util.List.of());
        repo.save(saved);
        int saves = repo.saves.size();

        keeper.restore();
        keeper.startIfNew();

        assertEquals(saved, keeper.frame());
        assertEquals(saves, repo.saves.size());
    }

    @Test
    void theRoleIsTheOneOfTheOrganizationWhenAFrameStarts() {
        SuggestPromptKeeper asSuperior = new SuggestPromptKeeper(SQUAD, repo, turns, model, model,
                () -> SuggestFrame.SUPERIOR, () -> 1L);

        asSuperior.startNewFrame();

        assertEquals(SuggestFrame.SUPERIOR, asSuperior.frame().role());
    }

    @Test
    void aKeeperHasNoFrameUntilItIsStartedOrRestored() {
        assertNull(keeper.frame());
        keeper.startNewFrame();
        assertNotNull(keeper.frame());
    }
}
