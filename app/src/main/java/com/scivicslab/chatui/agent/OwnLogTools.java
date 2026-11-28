package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.iolog.IoLogHistory;
import com.scivicslab.chatui.core.iolog.IoLogSearch;
import com.scivicslab.chatui.core.iolog.IoLogStore;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The {@code search_own_log} and {@code read_own_log} tools: a ConversationSquad actor searches its own conversation's
 * I/O log for a word, then reads the entries around a hit in whole ({@code SearchChatLogsThenReadAround_261128_oo01}).
 *
 * <p>The conversation is the caller's own, fixed when this is made; no argument names one, so a conversation cannot read
 * another's log through these tools. Rows come only through {@link IoLogSearch} and {@link IoLogHistory}.</p>
 */
public final class OwnLogTools {

    /** How many hits {@code search_own_log} returns when {@code limit} is left out. */
    public static final int DEFAULT_LIMIT = 20;
    /** The most hits one search returns. */
    public static final int MAX_LIMIT = 100;
    /** How many entries {@code read_own_log} reads on each side when {@code count} is left out. */
    public static final int DEFAULT_COUNT = 5;
    /** The most entries read on each side. */
    public static final int MAX_COUNT = 20;

    private final IoLogSearch search;
    private final IoLogHistory history;
    private final String conversation;

    /**
     * @param ioLog        the I/O log the conversations are recorded in; {@code null} where there is none
     * @param conversation the calling conversation's name, e.g. {@code project1/chat-01}
     */
    public OwnLogTools(IoLogStore ioLog, String conversation) {
        this.search = ioLog == null ? null : new IoLogSearch(ioLog);
        this.history = ioLog == null ? null : new IoLogHistory(ioLog);
        this.conversation = conversation;
    }

    /**
     * @param query what to look for, a substring, case-insensitive
     * @param since optional start of the period, e.g. {@code 2026-11-28T09:00}
     * @param until optional end of the period
     * @param limit optional, default {@value #DEFAULT_LIMIT}, at most {@value #MAX_LIMIT}
     * @return the hits, newest first, or an {@code error: ...} string
     */
    public String search(String query, String since, String until, String limit) {
        if (search == null) return "error: this instance keeps no I/O log";
        if (query == null || query.isBlank()) return "error: query is required";
        LocalDateTime from;
        LocalDateTime to;
        try {
            from = time(since);
            to = time(until);
        } catch (DateTimeParseException e) {
            return "error: since and until look like 2026-11-28T09:00";
        }
        Integer wanted = whole(limit);
        if (wanted != null && wanted < 1) return "error: limit must be at least 1";
        if (wanted == null && limit != null && !limit.isBlank()) return "error: limit is a whole number";
        int max = wanted == null ? DEFAULT_LIMIT : Math.min(wanted, MAX_LIMIT);
        IoLogSearch.Result found = search.searchConversation(conversation, query, from, to, max);
        if (!found.error().isEmpty()) return "error: the I/O log could not be searched: " + found.error();
        if (found.hits().isEmpty()) return "no hits for \"" + query + "\" in " + conversation
                + (from != null || to != null ? " in that period" : "");
        StringBuilder out = new StringBuilder();
        out.append(found.limited()
                ? "the newest " + found.hits().size() + " hits in " + conversation + " (newest first); more matched"
                : found.hits().size() + " hits in " + conversation + " (newest first)").append('\n');
        for (IoLogSearch.Hit h : found.hits()) {
            out.append("- logId ").append(h.logId()).append(" | ").append(minute(h.when())).append(" | ")
                    .append(h.turn() < 0 ? "no turn" : "turn " + h.turn()).append(" | ").append(h.label()).append('\n')
                    .append("  ").append(h.snippet()).append('\n');
        }
        if (found.limited()) {
            out.append("There are more hits than limit ").append(max).append(". Narrow the period with since and until, ")
                    .append("e.g. since=2026-11-28T09:00, or search a longer word.\n");
        }
        out.append("Read around a hit with read_own_log(logId).");
        return out.toString();
    }

    /**
     * @param logId the number {@code search_own_log} gave
     * @param count optional, how many entries on each side; default {@value #DEFAULT_COUNT}, at most {@value #MAX_COUNT}
     * @return the entry and its neighbours in whole, oldest first, or an {@code error: ...} string
     */
    public String read(String logId, String count) {
        if (history == null) return "error: this instance keeps no I/O log";
        Long id = logId == null || logId.isBlank() ? null : wholeLong(logId);
        if (id == null) return "error: logId is required, a whole number as search_own_log shows it";
        Integer n = whole(count);
        if (n == null && count != null && !count.isBlank()) return "error: count is a whole number";
        int each = n == null ? DEFAULT_COUNT : Math.max(0, Math.min(n, MAX_COUNT));
        List<IoLogHistory.Around> rows;
        try {
            rows = history.readAround(conversation, id, each);
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        StringBuilder out = new StringBuilder("entries around logId ").append(id).append(" in ").append(conversation)
                .append(" (oldest first; ").append(each).append(" on each side)\n");
        for (IoLogHistory.Around r : rows) {
            out.append("--- logId ").append(r.logId()).append(" | ").append(r.when()).append(" | ").append(r.label())
                    .append(r.requested() ? " | <== the one asked for" : "").append('\n')
                    .append(r.message()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static LocalDateTime time(String text) {
        return text == null || text.isBlank() ? null : LocalDateTime.parse(text.strip());
    }

    private static String minute(String when) {
        return when.length() > 16 ? when.substring(0, 16) : when;
    }

    private static Integer whole(String text) {
        Long v = text == null || text.isBlank() ? null : wholeLong(text);
        return v == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private static Long wholeLong(String text) {
        try {
            return Long.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
