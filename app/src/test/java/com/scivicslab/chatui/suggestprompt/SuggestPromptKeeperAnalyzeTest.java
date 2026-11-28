package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.FakeTurns;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.HandDrivenModel;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Mailbox;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Repo;
import com.scivicslab.chatui.suggestprompt.SuggestPromptKeeper.Analysis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Analyze conversation}: one update at once, under the interval's own rules. */
class SuggestPromptKeeperAnalyzeTest {

    private final Repo repo = new Repo();
    private final FakeTurns turns = new FakeTurns();
    private final HandDrivenModel model = new HandDrivenModel();
    private final Mailbox mailbox = new Mailbox();
    private final SuggestPromptKeeper keeper = new SuggestPromptKeeper("project1/chat-02", repo, turns, model, model,
            () -> SuggestFrame.STANDALONE, () -> 1000L);

    SuggestPromptKeeperAnalyzeTest() {
        mailbox.bind(keeper);
        keeper.startNewFrame();
    }

    @Test
    void unreadTurnsStartOneCallAtOnceAndSayHowManyTurns() {
        turns.finish(3);

        Analysis a = keeper.analyzeNow();

        assertEquals(Analysis.Kind.STARTED, a.kind());
        assertEquals(3, a.turns());
        assertTrue(keeper.calling());
        assertEquals(1, model.waiting.size(), "the LLM call is out and was not waited for");
    }

    @Test
    void nothingFinishedSinceDigestedTurnIsNothingNew() {
        turns.finish(0);

        Analysis a = keeper.analyzeNow();

        assertEquals(Analysis.Kind.NOTHING_NEW, a.kind());
        assertTrue(model.waiting.isEmpty());
    }

    @Test
    void whileACallIsOutItIsAlreadyRunningAndNoSecondCallStarts() {
        turns.finish(2);
        keeper.analyzeNow();

        Analysis again = keeper.analyzeNow();

        assertEquals(Analysis.Kind.ALREADY_RUNNING, again.kind());
        assertEquals(1, model.waiting.size());
    }

    @Test
    void afterTheReplyHasBeenAppliedThereIsNothingNew() {
        turns.finish(2);
        keeper.analyzeNow();
        model.finishOneCall();
        mailbox.drain();

        assertEquals(Analysis.Kind.NOTHING_NEW, keeper.analyzeNow().kind());
    }
}
