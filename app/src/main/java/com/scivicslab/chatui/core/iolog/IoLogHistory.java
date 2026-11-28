package com.scivicslab.chatui.core.iolog;

import com.scivicslab.chatui.core.provider.PersistedLog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads a conversation's history back from the I/O log, for {@code get_chat_log} after a restart
 * when the conversation's provider keeps no record of its own
 * ({@code GetChatLogReadsPersistedHistory_261119_oo01}).
 *
 * <p>Each turn's question and answer ({@code turnN/conversation}) become a {@code user} and an
 * {@code assistant} entry; a turn that has only its question ({@code turnN/question}, written when it started)
 * becomes a {@code user} entry and nothing else; each step's record ({@code turnN/stepM/...}) becomes a {@code step}
 * entry. Records that are not a turn's ({@code settings}, {@code name}) and a turn's bookkeeping
 * ({@code ids}, {@code answer-meta}) are left out. Every session the conversation's name was
 * recorded under is read, ended ones included, newest record first and no more than asked for.</p>
 */
public final class IoLogHistory {

    /** The {@code source} every entry read here is shown with. */
    public static final String SOURCE = "history:iolog";

    /** What {@link IoLogStore} prefixes a ConversationSquad's name with to name its session. */
    private static final String CONVERSATION_PREFIX = "chat-ui-conversation-";

    private final IoLogStore ioLog;

    /** @param ioLog the store the conversations are recorded in; {@code null} where there is none */
    public IoLogHistory(IoLogStore ioLog) {
        this.ioLog = ioLog;
    }

    /**
     * @param conversationSquadName the conversation's name, e.g. {@code project1/chat-02}
     * @param limit                 how many entries at most
     * @param before                epoch milliseconds; only records written earlier
     * @return the entries, oldest first; never {@code null}, and an exception is never thrown
     */
    public PersistedLog read(String conversationSquadName, int limit, long before) {
        if (ioLog == null) return PersistedLog.none(SOURCE, "this instance keeps no I/O log");
        if (limit <= 0) return PersistedLog.none(SOURCE, null);
        String workflowName = CONVERSATION_PREFIX + conversationSquadName;
        String sql = """
                SELECT l.id, l.timestamp, l.label, l.message
                FROM logs l JOIN sessions s ON s.id = l.session_id
                WHERE s.workflow_name = ?
                  AND (l.label LIKE 'turn%/conversation' OR l.label LIKE 'turn%/question' OR l.label LIKE 'turn%/step%')
                """ + (before == Long.MAX_VALUE ? "" : "  AND l.timestamp < ?\n") + """
                ORDER BY l.timestamp DESC, l.id DESC
                LIMIT ?
                """;
        // A short-lived connection of its own, as IoLogSearch takes one: a read here never waits
        // on, or holds up, the writer the conversations are being recorded through.
        ioLog.flush();   // what was told before this read is in the file the connection below reads
        try (Connection c = DriverManager.getConnection(ioLog.jdbcUrl())) {
            if (!hasSession(c, workflowName)) {
                return PersistedLog.none(SOURCE, "no I/O log session for this conversation");
            }
            List<PersistedLog.Entry> newestFirst = new ArrayList<>();
            List<Object[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, workflowName);
                if (before != Long.MAX_VALUE) ps.setTimestamp(i++, new Timestamp(before));
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Object[] {rs.getTimestamp("timestamp").getTime(), rs.getString("label"),
                                rs.getString("message")});
                    }
                }
            }
            // A turn's question entry stands for it only until its conversation entry exists: when both were read,
            // the conversation entry is the turn and the question entry is left out.
            java.util.Set<String> finished = new java.util.HashSet<>();
            for (Object[] row : rows) {
                String label = (String) row[1];
                if (label.endsWith("/conversation")) finished.add(label.substring(0, label.length() - "/conversation".length()));
            }
            for (Object[] row : rows) {
                String label = (String) row[1];
                if (label.endsWith("/question") && finished.contains(label.substring(0, label.length() - "/question".length()))) continue;
                addNewestFirst(newestFirst, (Long) row[0], label, (String) row[2]);
            }
            List<PersistedLog.Entry> kept = newestFirst.subList(0, Math.min(limit, newestFirst.size()));
            List<PersistedLog.Entry> oldestFirst = new ArrayList<>(kept);
            Collections.reverse(oldestFirst);
            return new PersistedLog(SOURCE, oldestFirst, null);
        } catch (Exception e) {
            return PersistedLog.none(SOURCE, "the I/O log could not be read: " + e.getMessage());
        }
    }

    /**
     * One finished turn's record: what was asked and what came back.
     *
     * @param turn     the turn's number within its I/O log session
     * @param question what was asked
     * @param answer   the answer, or {@code null} when the turn failed
     * @param error    the failure, or {@code null} when the turn was answered
     */
    public record TurnRow(int turn, String question, String answer, String error) {
    }

    private static final java.util.regex.Pattern TURN_CONVERSATION_LABEL =
            java.util.regex.Pattern.compile("turn(\\d+)/conversation");

    /**
     * Reads finished turns by number, for the work frame ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}). Only the
     * conversation's current I/O log session is read: after {@code /clear} the turn numbers start again at 1, so rows
     * of an ended session are not this conversation's turns. A turn that has no {@code conversation} entry has not
     * finished and is not returned.
     *
     * @param conversationSquadName the conversation's name, e.g. {@code project1/chat-02}
     * @param afterTurn             only turns numbered above this
     * @param upToTurn              only turns numbered up to and including this
     * @return the rows, oldest first; empty when there is no current session; the same turn may appear more than once
     *         (an autonomous continuation writes one more entry under its number)
     * @throws IllegalStateException when the log could not be read, so a caller does not take a failure for "no turns"
     */
    public List<TurnRow> readTurns(String conversationSquadName, int afterTurn, int upToTurn) {
        if (ioLog == null || upToTurn <= afterTurn) return List.of();
        // Neither call creates a session: a read must not open one.
        long session = ioLog.currentSessionId(conversationSquadName);
        if (session < 0) session = ioLog.findResumableSession(conversationSquadName);
        if (session < 0) return List.of();
        ioLog.flush();
        String sql = "SELECT l.label, l.message FROM logs l WHERE l.session_id = ? AND l.label LIKE 'turn%/conversation' "
                + "ORDER BY l.timestamp ASC, l.id ASC";
        List<TurnRow> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(ioLog.jdbcUrl()); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, session);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.regex.Matcher m = TURN_CONVERSATION_LABEL.matcher(rs.getString("label"));
                    if (!m.matches()) continue;
                    int turn = Integer.parseInt(m.group(1));
                    if (turn <= afterTurn || turn > upToTurn) continue;
                    IoLogView.Turn t = IoLogView.parseConversationEntry(rs.getString("message"));
                    if (t != null) rows.add(new TurnRow(turn, t.question(), t.answer(), t.error()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("the I/O log could not be read: " + e.getMessage(), e);
        }
        // Stable: rows of one turn keep the order they were written in.
        rows.sort(java.util.Comparator.comparingInt(TurnRow::turn));
        return rows;
    }

    /**
     * One entry of {@link #readAround}.
     *
     * @param logId     the row in {@code logs}
     * @param when      when it was recorded, to the second
     * @param label     its label, e.g. {@code turn7/question}
     * @param message   its whole text, never cut
     * @param requested whether this is the entry {@code logId} named
     */
    public record Around(long logId, String when, String label, String message, boolean requested) {}

    /** What {@link #readAround} answers when {@code logId} is not one of the conversation's entries. */
    public static final String NOT_OF_THIS_CONVERSATION = "not a log entry of this conversation";

    /**
     * Reads an entry of a conversation and the entries around it ({@code SearchChatLogsThenReadAround_261128_oo01}),
     * in whole. The neighbours are the conversation's own entries in the order they were recorded, across every session
     * its name was recorded under ({@code /clear} does not stop the read), whatever their labels.
     *
     * @param conversationSquadName the conversation's name, e.g. {@code project1/chat-02}
     * @param logId                 the entry to read around, as {@link IoLogSearch.Hit#logId()} gave it
     * @param count                 how many entries to read before it and after it, at least 0
     * @return the entries, oldest first
     * @throws IllegalArgumentException with {@link #NOT_OF_THIS_CONVERSATION} when {@code logId} is no entry of the
     *         conversation (another conversation's entry is refused the same way as one that does not exist)
     * @throws IllegalStateException when there is no I/O log or it could not be read
     */
    public List<Around> readAround(String conversationSquadName, long logId, int count) {
        if (ioLog == null) throw new IllegalStateException("this instance keeps no I/O log");
        String workflowName = CONVERSATION_PREFIX + conversationSquadName;
        String pick = "SELECT l.id, l.timestamp, l.label, l.message FROM logs l JOIN sessions s ON s.id = l.session_id "
                + "WHERE s.workflow_name = ? AND ";
        ioLog.flush();   // what was told before this read is in the file the connection below reads
        try (Connection c = DriverManager.getConnection(ioLog.jdbcUrl())) {
            Timestamp at;
            List<Around> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(pick + "l.id = ?")) {
                ps.setString(1, workflowName);
                ps.setLong(2, logId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException(NOT_OF_THIS_CONVERSATION);
                    at = rs.getTimestamp("timestamp");
                    out.add(around(rs, true));
                }
            }
            // The same order the history is read in: by time, and by row number within one instant.
            List<Around> before = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(pick
                    + "(l.timestamp < ? OR (l.timestamp = ? AND l.id < ?)) ORDER BY l.timestamp DESC, l.id DESC LIMIT ?")) {
                ps.setString(1, workflowName);
                ps.setTimestamp(2, at);
                ps.setTimestamp(3, at);
                ps.setLong(4, logId);
                ps.setInt(5, count);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) before.add(around(rs, false));
                }
            }
            Collections.reverse(before);
            List<Around> after = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(pick
                    + "(l.timestamp > ? OR (l.timestamp = ? AND l.id > ?)) ORDER BY l.timestamp ASC, l.id ASC LIMIT ?")) {
                ps.setString(1, workflowName);
                ps.setTimestamp(2, at);
                ps.setTimestamp(3, at);
                ps.setLong(4, logId);
                ps.setInt(5, count);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) after.add(around(rs, false));
                }
            }
            List<Around> all = new ArrayList<>(before);
            all.addAll(out);
            all.addAll(after);
            return all;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("the I/O log could not be read: " + e.getMessage(), e);
        }
    }

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static Around around(ResultSet rs, boolean requested) throws Exception {
        Timestamp t = rs.getTimestamp("timestamp");
        String message = rs.getString("message");
        return new Around(rs.getLong("id"), t == null ? "" : t.toLocalDateTime().format(WHEN),
                rs.getString("label") == null ? "" : rs.getString("label"), message == null ? "" : message, requested);
    }

    private static boolean hasSession(Connection c, String workflowName) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sessions WHERE workflow_name = ? LIMIT 1")) {
            ps.setString(1, workflowName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Adds the entries one record makes, newest first: an answer goes before its question. */
    private static void addNewestFirst(List<PersistedLog.Entry> out, long time, String label, String message) {
        if (label.endsWith("/conversation")) {
            IoLogView.Turn turn = IoLogView.parseConversationEntry(message);
            if (turn == null) return;
            out.add(new PersistedLog.Entry(time, "assistant",
                    turn.error() != null ? "Error: " + turn.error() : turn.answer()));
            out.add(new PersistedLog.Entry(time, "user", turn.question()));
        } else if (label.endsWith("/question")) {
            String question = IoLogView.parseQuestionEntry(message);
            if (question != null) out.add(new PersistedLog.Entry(time, "user", question));
        } else {
            out.add(new PersistedLog.Entry(time, "step", label + " " + (message == null ? "" : message)));
        }
    }
}
