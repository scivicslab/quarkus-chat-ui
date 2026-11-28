package com.scivicslab.chatui.core.iolog;

import com.scivicslab.chatui.agent.OwnLogTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code search_own_log} and {@code read_own_log} ({@code SearchChatLogsThenReadAround_261128_oo01}): a conversation reads
 * only its own log. It reads a real I/O log database in a temporary file, so it is an integration test.
 */
class OwnConversationLogIT {

    private static final String ME = "project1/chat-01";
    private static final String OTHER = "project1/chat-02";

    @TempDir
    Path dir;

    private IoLogStore log;
    private IoLogSearch search;
    private IoLogHistory history;

    @BeforeEach
    void open() {
        log = new IoLogStore();
        log.dbPath = dir.resolve("own-log-it").toString();
        log.httpPort = 28096;
        log.compress = false;
        search = new IoLogSearch(log);
        history = new IoLogHistory(log);
    }

    @AfterEach
    void close() throws Exception {
        log.shutdown();
    }

    /** Records an entry and gives it a known time, so that the period filter and the order do not depend on the clock. */
    private void record(long session, String label, String message, String at) throws Exception {
        log.record(session, "agent", label, message);
        log.flush();
        try (Connection c = DriverManager.getConnection(log.jdbcUrl());
             PreparedStatement ps = c.prepareStatement("UPDATE logs SET timestamp = ? WHERE session_id = ? AND label = ? AND message = ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.parse(at)));
            ps.setLong(2, session);
            ps.setString(3, label);
            ps.setString(4, message);
            assertEquals(1, ps.executeUpdate(), label);
        }
    }

    private long idOf(long session, String label) throws Exception {
        log.flush();
        try (Connection c = DriverManager.getConnection(log.jdbcUrl());
             PreparedStatement ps = c.prepareStatement("SELECT id FROM logs WHERE session_id = ? AND label = ?")) {
            ps.setLong(1, session);
            ps.setString(2, label);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void anotherConversationsRowsNeverHit() throws Exception {
        long mine = log.ensureSession(ME);
        long theirs = log.ensureSession(OTHER);
        record(mine, "turn1/question", "job 6257116 started", "2026-11-28T10:02:00");
        record(theirs, "turn1/question", "job 6257116 is theirs", "2026-11-28T10:03:00");

        IoLogSearch.Result r = search.searchConversation(ME, "6257116", null, null, 20);

        assertEquals(List.of("project1/chat-01"), r.hits().stream().map(IoLogSearch.Hit::conversation).toList());
        assertEquals(1, r.hits().size());
        assertEquals("turn1/question", r.hits().get(0).label());
    }

    @Test
    void theEntriesBeforeAClearAreSearchedToo() throws Exception {
        long before = log.ensureSession(ME);
        record(before, "turn1/question", "needle before the clear", "2026-11-28T09:00:00");
        log.resetSession(ME);
        long after = log.ensureSession(ME);
        record(after, "turn1/question", "needle after the clear", "2026-11-28T11:00:00");

        IoLogSearch.Result r = search.searchConversation(ME, "needle", null, null, 20);

        assertEquals(2, r.hits().size());
        assertTrue(r.hits().get(0).snippet().contains("after"), "newest first");
    }

    @Test
    void thePeriodFiltersByTime() throws Exception {
        long s = log.ensureSession(ME);
        record(s, "turn1/question", "needle one", "2026-11-28T09:00:00");
        record(s, "turn2/question", "needle two", "2026-11-28T10:00:00");
        record(s, "turn3/question", "needle three", "2026-11-28T11:00:00");

        assertEquals(List.of("turn3/question", "turn2/question"), search.searchConversation(ME, "needle",
                LocalDateTime.parse("2026-11-28T09:30"), null, 20).hits().stream().map(IoLogSearch.Hit::label).toList());
        assertEquals(List.of("turn2/question", "turn1/question"), search.searchConversation(ME, "needle",
                null, LocalDateTime.parse("2026-11-28T10:30"), 20).hits().stream().map(IoLogSearch.Hit::label).toList());
        assertEquals(List.of("turn2/question"), search.searchConversation(ME, "needle",
                LocalDateTime.parse("2026-11-28T09:30"), LocalDateTime.parse("2026-11-28T10:30"), 20)
                .hits().stream().map(IoLogSearch.Hit::label).toList());
    }

    @Test
    void moreMatchesThanTheLimitAreSaidSoWithTheWayToNarrowThem() throws Exception {
        long s = log.ensureSession(ME);
        for (int i = 1; i <= 3; i++) record(s, "turn" + i + "/question", "needle " + i, "2026-11-28T0" + i + ":00:00");

        IoLogSearch.Result r = search.searchConversation(ME, "needle", null, null, 2);
        assertTrue(r.limited());
        assertEquals(2, r.hits().size());
        assertEquals(List.of("turn3/question", "turn2/question"), r.hits().stream().map(IoLogSearch.Hit::label).toList());

        String text = new OwnLogTools(log, ME).search("needle", null, null, "2");
        assertTrue(text.contains("more matched"), text);
        assertTrue(text.contains("There are more hits than limit 2"), text);
        assertTrue(text.contains("since"), text);
        assertFalse(search.searchConversation(ME, "needle", null, null, 3).limited());
    }

    @Test
    void aSnippetIsAtMost300Characters() throws Exception {
        long s = log.ensureSession(ME);
        // A long word: the margin of 140 characters on each side of it is more than 300 in all.
        String word = "w".repeat(100);
        record(s, "turn1/question", "x".repeat(2000) + " " + word + " " + "y".repeat(2000), "2026-11-28T09:00:00");

        String snippet = search.searchConversation(ME, word, null, null, 20).hits().get(0).snippet();

        assertEquals(300, snippet.length());
        assertTrue(snippet.endsWith("…"), snippet);
    }

    @Test
    void readAroundReturnsTheNeighboursOldestFirstAndInWhole() throws Exception {
        long s = log.ensureSession(ME);
        String whole = "long ".repeat(400) + "END";
        for (int i = 1; i <= 7; i++) {
            record(s, "turn" + i + "/question", i == 4 ? whole : "text " + i, "2026-11-28T09:0" + i + ":00");
        }
        long other = log.ensureSession(OTHER);
        record(other, "turn1/question", "other conversation text", "2026-11-28T09:04:30");

        List<IoLogHistory.Around> rows = history.readAround(ME, idOf(s, "turn4/question"), 2);

        assertEquals(List.of("turn2/question", "turn3/question", "turn4/question", "turn5/question", "turn6/question"),
                rows.stream().map(IoLogHistory.Around::label).toList());
        assertEquals(List.of(false, false, true, false, false), rows.stream().map(IoLogHistory.Around::requested).toList());
        assertEquals(whole, rows.get(2).message());
        assertEquals(2, history.readAround(ME, idOf(s, "turn1/question"), 1).size(), "no entry before the first one");
    }

    @Test
    void readAroundCrossesAClear() throws Exception {
        long before = log.ensureSession(ME);
        record(before, "turn1/question", "old", "2026-11-28T09:00:00");
        log.resetSession(ME);
        long after = log.ensureSession(ME);
        record(after, "turn1/question", "new", "2026-11-28T10:00:00");

        List<IoLogHistory.Around> rows = history.readAround(ME, idOf(after, "turn1/question"), 3);

        assertEquals(List.of("old", "new"), rows.stream().map(IoLogHistory.Around::message).toList());
    }

    @Test
    void aLogIdOfAnotherConversationIsRefused() throws Exception {
        long mine = log.ensureSession(ME);
        long theirs = log.ensureSession(OTHER);
        record(mine, "turn1/question", "mine", "2026-11-28T09:00:00");
        record(theirs, "turn1/question", "secret of the other", "2026-11-28T09:01:00");
        long foreign = idOf(theirs, "turn1/question");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> history.readAround(ME, foreign, 5));
        assertEquals("not a log entry of this conversation", e.getMessage());

        String text = new OwnLogTools(log, ME).read(String.valueOf(foreign), null);
        assertEquals("error: not a log entry of this conversation", text);
        assertFalse(text.contains("secret"), text);
    }

    @Test
    void theToolsTakeTheLimitsAndAnswerInTheDocsShape() throws Exception {
        long s = log.ensureSession(ME);
        record(s, "turn152/question", "NIG job 6257116 dropped", "2026-11-28T15:08:00");
        record(s, "turn139/question", "job id 6257116 running", "2026-11-28T10:02:00");

        String text = new OwnLogTools(log, ME).search("6257116", null, null, null);

        assertTrue(text.startsWith("2 hits in project1/chat-01 (newest first)\n- logId "), text);
        assertTrue(text.contains(" | 2026-11-28 15:08 | turn 152 | turn152/question\n  NIG job 6257116 dropped\n"), text);
        assertTrue(text.endsWith("Read around a hit with read_own_log(logId)."), text);
        assertEquals("error: query is required", new OwnLogTools(log, ME).search(" ", null, null, null));
        assertEquals("error: since and until look like 2026-11-28T09:00",
                new OwnLogTools(log, ME).search("x", "yesterday", null, null));
    }

    @Test
    void aLimitAbove100IsCappedAt100() throws Exception {
        long s = log.ensureSession(ME);
        for (int i = 1; i <= 105; i++) log.record(s, "agent", "turn" + i + "/question", "needle " + i);

        String text = new OwnLogTools(log, ME).search("needle", null, null, "500");

        assertEquals(100, text.lines().filter(l -> l.startsWith("- logId")).count());
        assertTrue(text.contains("more matched"), text);
    }

    @Test
    void theCountIsCappedAt20() throws Exception {
        long s = log.ensureSession(ME);
        for (int i = 1; i <= 50; i++) record(s, "turn" + i + "/question", "t" + i, String.format("2026-11-28T09:%02d:00", i));

        String text = new OwnLogTools(log, ME).read(String.valueOf(idOf(s, "turn25/question")), "99");

        assertEquals(41, text.lines().filter(l -> l.startsWith("--- logId")).count(), "20 + 1 + 20");
    }

    @Test
    void anLlmRequestRowHoldingTheQueryIsNotHit() throws Exception {
        long s = log.ensureSession(ME);
        // The request carries the whole history again, so the word is in it; only the question and the answer count.
        record(s, "turn7/step1/llm", "request: ... job 6257116 ... history ...", "2026-11-28T09:00:00");
        record(s, "turn7/step1/tool", "tool result: job 6257116", "2026-11-28T09:00:10");
        record(s, "turn7/answer-meta", "meta 6257116", "2026-11-28T09:00:20");
        record(s, "turn8/question", "what became of job 6257116?", "2026-11-28T09:01:00");
        record(s, "turn8/conversation", "job 6257116 was dropped", "2026-11-28T09:02:00");

        IoLogSearch.Result r = search.searchConversation(ME, "6257116", null, null, 20);

        assertEquals(List.of("turn8/conversation", "turn8/question"), r.hits().stream().map(IoLogSearch.Hit::label).toList());
        String text = new OwnLogTools(log, ME).search("6257116", null, null, null);
        assertTrue(text.startsWith("2 hits in project1/chat-01"), text);
        assertFalse(text.contains("step1"), text);
        assertFalse(text.contains("answer-meta"), text);
        // A query that only the request row holds finds nothing at all.
        record(s, "turn9/step1/llm", "request holding only-in-request", "2026-11-28T09:03:00");
        assertEquals("no hits for \"only-in-request\" in " + ME, new OwnLogTools(log, ME).search("only-in-request", null, null, null));
    }

    @Test
    void aLongEntryIsCutAndSaysSo() throws Exception {
        long s = log.ensureSession(ME);
        String huge = "a".repeat(182_345);
        record(s, "turn3/question", "short before", "2026-11-28T09:00:00");
        record(s, "turn3/step1/llm", huge, "2026-11-28T09:01:00");
        record(s, "turn3/conversation", "b".repeat(4000), "2026-11-28T09:02:00");
        record(s, "turn4/question", "c".repeat(4001), "2026-11-28T09:03:00");

        String text = new OwnLogTools(log, ME).read(String.valueOf(idOf(s, "turn3/step1/llm")), "5");

        assertTrue(text.contains("a".repeat(4000) + "\n[cut: 4000 of 182,345 characters]"), "the huge row is cut and says so");
        assertFalse(text.contains("a".repeat(4001)), "no more than 4000 characters of it");
        assertTrue(text.contains("b".repeat(4000) + "\n"), "a row of exactly 4000 characters is kept whole");
        assertFalse(text.contains("[cut: 4000 of 4,000"), text);
        assertTrue(text.contains("c".repeat(4000) + "\n[cut: 4000 of 4,001 characters]"), "4001 is cut");
        assertTrue(text.contains("short before\n"), text);
        assertEquals(2, text.split("\\[cut: ", -1).length - 1, "only the two long rows carry the mark");
        assertEquals(182_345, history.readAround(ME, idOf(s, "turn3/step1/llm"), 0).get(0).message().length(),
                "the store's own read is never cut");
    }
}
