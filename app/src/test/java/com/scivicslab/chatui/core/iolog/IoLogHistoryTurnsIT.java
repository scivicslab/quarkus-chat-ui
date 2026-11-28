package com.scivicslab.chatui.core.iolog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IoLogHistory#readTurns}, the turn-range read the work frame uses ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}):
 * only finished turns, only the current session, oldest first, and it opens no session. It reads a real I/O log database,
 * so it is an integration test.
 */
class IoLogHistoryTurnsIT {

    private static final String SQUAD = "project1/chat-07";

    @TempDir
    Path dir;

    private IoLogStore log;
    private IoLogHistory history;

    @BeforeEach
    void open() {
        log = new IoLogStore();
        log.dbPath = dir.resolve("turns-it").toString();
        log.httpPort = 28097;
        log.compress = false;
        history = new IoLogHistory(log);
    }

    @AfterEach
    void close() throws Exception {
        log.shutdown();
    }

    private static String entry(String question, String answer) {
        return "QUESTION:\n" + question + "\n\nANSWER:\n" + answer;
    }

    private void finished(long session, int turn, String question, String answer) {
        log.record(session, "agent", "turn" + turn + "/conversation", entry(question, answer));
    }

    @Test
    void readsOnlyTheTurnsAfterTheMarkAndUpToTheFinishedOneOldestFirst() {
        long s = log.ensureSession(SQUAD);
        for (int t = 1; t <= 5; t++) finished(s, t, "q" + t, "a" + t);

        List<IoLogHistory.TurnRow> rows = history.readTurns(SQUAD, 1, 4);

        assertEquals(List.of(2, 3, 4), rows.stream().map(IoLogHistory.TurnRow::turn).toList());
        assertEquals("q2", rows.get(0).question());
        assertEquals("a4", rows.get(2).answer());
    }

    @Test
    void aTurnThatHasOnlyItsQuestionHasNotFinishedAndIsNotRead() {
        long s = log.ensureSession(SQUAD);
        finished(s, 1, "q1", "a1");
        log.record(s, "agent", "turn2/question", "QUESTION:\nq2");

        assertEquals(List.of(1), history.readTurns(SQUAD, 0, 9).stream().map(IoLogHistory.TurnRow::turn).toList());
    }

    @Test
    void aFailedTurnComesBackWithItsErrorAndNoAnswer() {
        long s = log.ensureSession(SQUAD);
        log.record(s, "agent", "turn1/conversation", "QUESTION:\nq1\n\nERROR:\ntimeout");

        IoLogHistory.TurnRow r = history.readTurns(SQUAD, 0, 1).get(0);

        assertEquals("timeout", r.error());
        assertEquals(null, r.answer());
    }

    @Test
    void afterAClearOnlyTheCurrentSessionIsReadAndItsNumbersStartAtOne() {
        long old = log.ensureSession(SQUAD);
        for (int t = 1; t <= 3; t++) finished(old, t, "old q" + t, "old a" + t);
        log.resetSession(SQUAD);
        long fresh = log.ensureSession(SQUAD);
        finished(fresh, 1, "new q1", "new a1");

        List<IoLogHistory.TurnRow> rows = history.readTurns(SQUAD, 0, 3);

        assertEquals(1, rows.size());
        assertEquals("new q1", rows.get(0).question());
    }

    @Test
    void aConversationWithNoSessionGivesNothingAndOpensNone() {
        assertTrue(history.readTurns(SQUAD, 0, 9).isEmpty());
        assertEquals(-1L, log.currentSessionId(SQUAD), "a read does not open a session");
    }

    @Test
    void anEmptyRangeGivesNothing() {
        long s = log.ensureSession(SQUAD);
        finished(s, 1, "q1", "a1");

        assertTrue(history.readTurns(SQUAD, 1, 1).isEmpty());
        assertTrue(history.readTurns(SQUAD, 5, 2).isEmpty());
    }

    @Test
    void anInstanceWithoutAnIoLogGivesNothing() {
        assertTrue(new IoLogHistory(null).readTurns(SQUAD, 0, 9).isEmpty());
    }
}
