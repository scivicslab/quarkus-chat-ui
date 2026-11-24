package com.scivicslab.chatui.core.iolog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.chatui.core.provider.PersistedLog;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;

/**
 * A turn writes its question ({@code turnN/question}) when it starts. A turn that has that entry and no
 * {@code turnN/conversation} yet has not finished: it is listed and restored with its question and no answer. When both
 * exist, the conversation entry is the turn. Logs written before the entry existed read as they always did.
 */
class QuestionAtTurnStartTest {

    private static long nextId = 1;

    private static LogEntry row(String label, String message) {
        return new LogEntry(nextId++, 1L, LocalDateTime.of(2026, 11, 25, 0, 0).plusSeconds(nextId), "agent", label,
                null, LogLevel.INFO, message, null, null);
    }

    private static LogEntry question(int turn, String q) { return row("turn" + turn + "/question", "QUESTION:\n" + q); }

    private static LogEntry conversation(int turn, String q, String a) {
        return row("turn" + turn + "/conversation", "QUESTION:\n" + q + "\n\nANSWER:\n" + a);
    }

    private static LogEntry step(int turn, String requestPrompt) {
        return row("turn" + turn + "/step1/llm", "REQUEST:\n{\"messages\":[{\"role\":\"user\",\"content\":\""
                + requestPrompt + "\"}]}\n\nRESPONSE:\nok\n\nUSAGE: promptTokens=0 completionTokens=0");
    }

    @Test
    void anUnfinishedTurnComesBackWithItsQuestionAndNoAnswer() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                question(1, "first?"), conversation(1, "first?", "first."),
                question(2, "second, still running")), 50);

        assertEquals(2, turns.size());
        assertEquals("second, still running", turns.get(1).question());
        assertNull(turns.get(1).answer());
        assertNull(turns.get(1).error());
    }

    @Test
    void aFinishedTurnIsListedOnceFromItsConversationEntry() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                question(1, "q"), step(1, "q"), conversation(1, "q", "a")), 50);

        assertEquals(1, turns.size());
        assertEquals("q", turns.get(0).question());
        assertEquals("a", turns.get(0).answer());
    }

    @Test
    void aFailedTurnStaysAFailure() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(question(1, "q"),
                row("turn1/conversation", "QUESTION:\nq\n\nERROR:\nconnection refused")), 50);

        assertEquals(1, turns.size());
        assertEquals("connection refused", turns.get(0).error());
    }

    @Test
    void aLogWithoutTheQuestionEntryReadsAsItAlwaysDid() {
        List<LogEntry> old = List.of(conversation(1, "a", "b"), conversation(2, "c", "d"));
        List<IoLogView.Turn> turns = IoLogView.conversationOf(old, 50);

        assertEquals(List.of("a", "c"), turns.stream().map(IoLogView.Turn::question).toList());
        assertEquals(List.of("b", "d"), turns.stream().map(IoLogView.Turn::answer).toList());
    }

    @Test
    void theTurnWindowKeepsTheMostRecentTurnsUnfinishedOneIncluded() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                conversation(1, "q1", "a1"), conversation(2, "q2", "a2"), question(3, "q3")), 2);

        assertEquals(List.of("q2", "q3"), turns.stream().map(IoLogView.Turn::question).toList());
    }

    @Test
    void theTurnsListShowsTheQuestionOfATurnThatHasNoStepYet() {
        List<IoLogView.TurnHead> heads = IoLogView.turnHeadsOf(List.of(
                step(1, "first"), conversation(1, "first", "a"), question(2, "asked a moment ago")), 0, 10);

        assertEquals(2, heads.size());
        assertEquals(2, heads.get(0).turn());
        assertEquals("asked a moment ago", heads.get(0).question());
        assertEquals(1, heads.get(1).turn());
    }

    @Test
    void theTurnsListUsesTheStartedQuestionNotTheConstructedPromptOfARunningTurn() {
        List<IoLogView.TurnHead> heads = IoLogView.turnHeadsOf(List.of(
                question(1, "what is the weather"), step(1, "SYSTEM PROMPT ... and the tools ... what is the weather")), 0, 10);

        assertEquals("what is the weather", heads.get(0).question());
    }

    @Test
    void aTurnsListFromAnOldLogIsUnchanged() {
        List<IoLogView.TurnHead> heads = IoLogView.turnHeadsOf(List.of(
                step(1, "old question"), conversation(1, "old question", "a")), 0, 10);

        assertEquals(1, heads.size());
        assertEquals("old question", heads.get(0).question());
    }

    @Test
    void theTraceKeepsTheQuestionWhenAStepHasNoRequestToReadItFrom() {
        List<IoLogView.TraceTurn> trace = IoLogView.traceOf(List.of(question(1, "the question"),
                row("turn1/step1/tool", "TOOL: bash\nINPUT:\n{}\nOBSERVATION:\nok")));

        assertEquals(1, trace.size());
        assertEquals("the question", trace.get(0).userPrompt());
    }

    @Test
    void theHistoryReadFromTheLogGivesOneUserLineForAnUnfinishedTurnAndTwoLinesForAFinishedOne(@TempDir Path dir) {
        IoLogStore store = new IoLogStore();
        store.dbPath = dir.resolve("question-history-test").toString();
        store.httpPort = 28098;
        store.compress = false;
        long s = store.ensureSession("project1/chat-03");
        store.record(s, "agent", "turn1/question", "QUESTION:\nfinished?");
        store.record(s, "agent", "turn1/step1/llm", "REQUEST:\n{}\nRESPONSE:\nyes");
        store.record(s, "agent", "turn1/conversation", "QUESTION:\nfinished?\n\nANSWER:\nyes");
        store.record(s, "agent", "turn2/question", "QUESTION:\nnot finished");
        store.shutdown();

        PersistedLog log = new IoLogHistory(store).read("project1/chat-03", 200, Long.MAX_VALUE);

        List<String> lines = log.entries().stream().filter(e -> !"step".equals(e.type()))
                .map(e -> e.type() + ": " + e.text()).toList();
        assertEquals(List.of("user: finished?", "assistant: yes", "user: not finished"), lines);
        assertTrue(log.entries().stream().noneMatch(e -> e.text().startsWith("turn1/question")), "no question entry shown as a step");
    }
}
