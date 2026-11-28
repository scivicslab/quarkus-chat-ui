package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.core.iolog.IoLogHistory.TurnRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the turn rows {@code IoLogHistory} returns become the messages the work frame reads. */
class IoLogTurnsTest {

    @Test
    void aTurnGivesItsQuestionFromThePersonThenItsAnswer() {
        List<SuggestPatchPrompt.Message> m = IoLogTurns.messagesOf(List.of(new TurnRow(3, "Why?", "Because.", null)));

        assertEquals(List.of(new SuggestPatchPrompt.Message(3, true, "Why?"), new SuggestPatchPrompt.Message(3, false, "Because.")), m);
    }

    @Test
    void aFailedTurnGivesItsQuestionOnly() {
        List<SuggestPatchPrompt.Message> m = IoLogTurns.messagesOf(List.of(new TurnRow(4, "Do it", null, "timeout")));

        assertEquals(List.of(new SuggestPatchPrompt.Message(4, true, "Do it")), m);
    }

    @Test
    void anAutonomousContinuationHasNoQuestionOfAPerson() {
        List<SuggestPatchPrompt.Message> m = IoLogTurns.messagesOf(List.of(new TurnRow(5, "(autonomous continuation)", "More work.", null)));

        assertEquals(List.of(new SuggestPatchPrompt.Message(5, false, "More work.")), m);
    }

    @Test
    void blankTextsAreLeftOutAndOrderIsKept() {
        List<SuggestPatchPrompt.Message> m = IoLogTurns.messagesOf(List.of(
                new TurnRow(1, "a", "  ", null), new TurnRow(2, " ", "b", null), new TurnRow(2, "c", "d", null)));

        assertEquals(List.of(1, 2, 2, 2), m.stream().map(SuggestPatchPrompt.Message::turn).toList());
        assertEquals(List.of("a", "b", "c", "d"), m.stream().map(SuggestPatchPrompt.Message::text).toList());
    }

    @Test
    void noRowsGiveNoMessages() {
        assertTrue(IoLogTurns.messagesOf(List.of()).isEmpty());
    }
}
