package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.iolog.IoLogView.LlmRow;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What fills a model's context window, row by row, for the Tokens tab ({@code tasks/tokens-tab-design.md}).
 *
 * <p>The rows are fixed: {@link #ROWS} lists them in the order they are shown, and a row with nothing in it is shown
 * with 0. They are the coordinates, so they do not change with the situation. Every part of one request's input is
 * counted in exactly one of them, and what no row takes is the explicit residual row ({@link #RESIDUAL}): the input
 * the provider reported for the request minus the sum of the rows. Rows and residual therefore add up to the measured
 * input, for the last request and for the cumulative column alike ({@link Table#sumsToMeasured}).</p>
 *
 * <p>Plain Java: the log rows, the head-block parts and the window limit are handed in by
 * {@link TokensBreakdownResource}. The row values are estimates (characters divided by a fixed factor,
 * {@link HeadBlock#estimateTokens}); only the measured input and output come from the provider.</p>
 */
public final class TokenBreakdown {

    /** The rows' ids, in the order they are shown. */
    public static final String A1 = "A1", A2 = "A2", A3 = "A3", A4 = "A4", B1 = "B1", B2 = "B2", C1 = "C1", C2 = "C2",
            D1 = "D1", D2 = "D2", D3 = "D3", D4 = "D4", E1 = "E1", F1 = "F1";
    /** The residual row: measured input minus the sum of {@link #A1} to {@link #F1}. */
    public static final String RESIDUAL = "F2";

    /**
     * One row of the table.
     *
     * @param id         e.g. {@code "D4"}
     * @param group      the group it is shown under
     * @param label      what a person reads
     * @param definition what the row holds, in terms of what chat-ui sends
     */
    public record RowDef(String id, String group, String label, String definition) {}

    /** The input rows, top to bottom. */
    public static final List<RowDef> ROWS = List.of(
            new RowDef(A1, "Head of every request", "System prompt",
                    "The provider's own preface, the harness notes, where the file tools may reach, the step budget note."),
            new RowDef(A2, "Head of every request", "Skill list", "The skill catalog (name and description of each skill)."),
            new RowDef(A3, "Head of every request", "Project instructions and position",
                    "The project's AGENTS.md or CLAUDE.md and the conversation's place in the organization."),
            new RowDef(A4, "Head of every request", "Files the provider reads itself",
                    "Claude Code only: the CLAUDE.md files and the MEMORY.md index the CLI reads."),
            new RowDef(B1, "Tool definitions", "chat-ui's MCP tools", "The definitions chat-ui's MCP server lists for the conversation."),
            new RowDef(B2, "Tool definitions", "External MCP servers' tools",
                    "Tools of the conversation's external MCP servers (<server>__<tool>), or the servers the CLI is configured with."),
            new RowDef(C1, "Earlier turns", "Earlier turns: what was asked", "The user messages of the turns before this one."),
            new RowDef(C2, "Earlier turns", "Earlier turns: what was answered",
                    "The assistant answers of the turns before this one (a past turn is kept as question and answer)."),
            new RowDef(D1, "This turn", "This turn: the input", "The user message that opened the turn."),
            new RowDef(D2, "This turn", "This turn: assistant text", "The text the model wrote beside its tool calls."),
            new RowDef(D3, "This turn", "This turn: tool calls", "The tool calls (name and arguments) made so far."),
            new RowDef(D4, "This turn", "This turn: tool results", "What the tools returned."),
            new RowDef(E1, "Other", "Images", "Attached images; the log keeps only their size, so their tokens show up in the residual."),
            new RowDef(F1, "Other", "Framing", "Request fields and message wrappers that no row above takes."));

    /** The output rows. They are not part of the input, but become rows C2, D2 and D3 of the next request. */
    public static final List<RowDef> OUTPUT_ROWS = List.of(
            new RowDef("G1", "Output", "Output (reported)", "What the provider reported as the reply's tokens."),
            new RowDef("G2", "Output", "of which reasoning", "Estimated from the recorded reasoning text."),
            new RowDef("G3", "Output", "of which answer and tool calls", "Output minus reasoning."));

    private static final String RESIDUAL_DEFINITION =
            "Measured input minus the rows above. A large positive value is something chat-ui cannot see (a CLI's own prompt and "
                    + "tools, images); a negative value means the estimates are too high.";

    private TokenBreakdown() {}

    /**
     * One request, as the table needs it.
     *
     * @param turn            its turn
     * @param step            its step
     * @param rows            the estimated tokens per row id ({@link #A1} to {@link #F1}), or {@code null} when the request
     *                        cannot be split
     * @param measuredTotal   the input the provider reported, or {@code null} when it reported none
     * @param cacheRead       tokens read from the provider's cache
     * @param cacheCreation   tokens written to it
     * @param output          the output tokens reported, or {@code null} when none
     * @param reasoningEstimate the estimated tokens of the reasoning text
     */
    public record Req(int turn, int step, Map<String, Integer> rows, Integer measuredTotal, int cacheRead, int cacheCreation,
                      Integer output, int reasoningEstimate) {}

    /**
     * One line of the table. {@code lastRequest} and {@code cumulative} are {@code null} where nothing can be said.
     *
     * @param basis {@code "estimated"}, {@code "measured"}, {@code "calculated"} or {@code "not available"}
     */
    public record Line(String id, String group, String label, String definition, Integer lastRequest, Long cumulative,
                       String basis) {}

    /** One entry of the table of special topics. */
    public record Topic(String label, String value, String note) {}

    /**
     * The whole answer.
     *
     * @param rows       {@link #ROWS} and the residual row last
     * @param rowsSum    the sum of the input rows, without the residual
     * @param measured   the input the provider reported
     * @param free       the window left in the last request
     * @param output     {@link #OUTPUT_ROWS}
     * @param cache      the cache figures of the measured input, each its own line
     * @param topics     the special topics
     * @param notes      what a reader needs to know about how these numbers were got
     */
    public record Table(String provider, String model, Integer windowLimit, String windowLimitSource, int requests,
                        int cumulativeRequests, Integer lastTurn, List<Line> rows, Line rowsSum, Line measured, Line free,
                        List<Line> output, List<Line> cache, List<Topic> topics, List<String> notes) {

        /** @return whether rows plus residual equal the measured input in both columns (true when nothing was measured) */
        public boolean sumsToMeasured() {
            Line residual = rows.get(rows.size() - 1);
            boolean last = measured.lastRequest() == null || rowsSum.lastRequest() == null || residual.lastRequest() == null
                    || rowsSum.lastRequest() + residual.lastRequest() == measured.lastRequest().intValue();
            boolean cumulative = measured.cumulative() == null || rowsSum.cumulative() == null || residual.cumulative() == null
                    || rowsSum.cumulative() + residual.cumulative() == measured.cumulative().longValue();
            return last && cumulative;
        }
    }

    // ── Splitting one request ────────────────────────────────────────────────────────────────────────────────────

    private static final String SKILLS_MARK = "Available skills — instructions you can load";
    private static final String INSTRUCTIONS_MARK = "Instructions for this project, from its working directory";
    private static final String POSITION_MARK = "Your position in ";
    private static final String BUDGET_MARK = "You may make at most ";

    /** Collects the text of each row, then turns each row's text into tokens once. */
    private static final class Parts {
        final Map<String, StringBuilder> text = new LinkedHashMap<>();
        long attributedChars;

        Parts() {
            for (RowDef r : ROWS) text.put(r.id(), new StringBuilder());
        }

        void add(String row, String s) {
            if (s == null || s.isEmpty()) return;
            text.get(row).append(s);
            attributedChars += s.codePointCount(0, s.length());
        }

        Map<String, Integer> tokens(long wholeChars) {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, StringBuilder> e : text.entrySet()) {
                if (F1.equals(e.getKey())) continue;
                out.put(e.getKey(), HeadBlock.estimateTokens(e.getValue().toString()));
            }
            long framing = Math.max(0, wholeChars - attributedChars);
            out.put(F1, (int) Math.ceil(framing / HeadBlock.CHARS_PER_TOKEN_ENGLISH));
            return out;
        }
    }

    /**
     * Splits the body of a request to an OpenAI-compatible server over the rows: the system message into
     * {@link #A1}, {@link #A2} and {@link #A3} by the marks of its parts, each tool definition into {@link #B1} or
     * {@link #B2} by whether its name carries a server prefix ({@code <server>__<tool>}), the messages before the last
     * user message into {@link #C1} and {@link #C2}, and the last user message and what follows it into {@link #D1} to
     * {@link #D4}. Whatever no row takes is {@link #F1}.
     *
     * @param body the request body as the log keeps it
     * @return tokens per row id, or {@code null} when the body is not a request with {@code messages}
     */
    public static Map<String, Integer> splitOpenAiBody(String body) {
        if (body == null || body.isBlank()) return null;
        JSONObject request;
        try {
            request = new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
        JSONArray messages = request.optJSONArray("messages");
        if (messages == null) return null;
        Parts parts = new Parts();
        JSONArray tools = request.optJSONArray("tools");
        for (int i = 0; tools != null && i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            String name = tool == null || tool.optJSONObject("function") == null ? "" : tool.getJSONObject("function").optString("name", "");
            parts.add(name.contains("__") ? B2 : B1, tool == null ? "" : tool.toString());
        }
        int lastUser = -1;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) lastUser = i;
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            if ("system".equals(role) && i == 0) {
                splitSystem(parts, contentText(m, parts, E1));
                continue;
            }
            boolean earlier = i < lastUser;
            if ("user".equals(role)) {
                parts.add(earlier ? C1 : D1, contentText(m, parts, E1));
            } else if ("tool".equals(role)) {
                parts.add(earlier ? C2 : D4, contentText(m, parts, E1));
            } else {
                String text = contentText(m, parts, E1);
                JSONArray calls = m.optJSONArray("tool_calls");
                if (earlier) {
                    parts.add(C2, text);
                    if (calls != null) parts.add(C2, calls.toString());
                } else {
                    parts.add(D2, text);
                    if (calls != null) parts.add(D3, calls.toString());
                }
            }
        }
        return parts.tokens(body.codePointCount(0, body.length()));
    }

    /** The text of a message's content; an image part goes to {@code imageRow} (its marker is all the log keeps of it). */
    private static String contentText(JSONObject message, Parts parts, String imageRow) {
        Object content = message.opt("content");
        if (content instanceof String s) return s;
        if (!(content instanceof JSONArray array)) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            JSONObject part = array.optJSONObject(i);
            if (part == null) continue;
            if ("image_url".equals(part.optString("type"))) parts.add(imageRow, part.toString());
            else text.append(part.optString("text", ""));
        }
        return text.toString();
    }

    private static void splitSystem(Parts parts, String system) {
        int skills = system.indexOf(SKILLS_MARK);
        int instructions = indexOfAny(system, INSTRUCTIONS_MARK, POSITION_MARK);
        int budget = system.indexOf(BUDGET_MARK);
        int end = system.length();
        int budgetAt = budget >= 0 && budget >= Math.max(skills, instructions) ? budget : end;
        int a3 = instructions >= 0 ? instructions : -1;
        int a2 = skills >= 0 && (a3 < 0 || skills < a3) ? skills : -1;
        int firstSpecial = a2 >= 0 ? a2 : (a3 >= 0 ? a3 : budgetAt);
        parts.add(A1, system.substring(0, firstSpecial));
        if (a2 >= 0) parts.add(A2, system.substring(a2, a3 >= 0 ? a3 : budgetAt));
        if (a3 >= 0) parts.add(A3, system.substring(a3, budgetAt));
        if (budgetAt < end) parts.add(A1, system.substring(budgetAt));
    }

    private static int indexOfAny(String s, String a, String b) {
        int x = s.indexOf(a), y = s.indexOf(b);
        if (x < 0) return y;
        if (y < 0) return x;
        return Math.min(x, y);
    }

    /**
     * What chat-ui sends a CLI harness is one prompt per request, and what the CLI holds besides (its own prompt and
     * tools, its history) is not visible: the rows that can be got are the head-block parts, the turns' text in the
     * log, and the prompt of this request. The rest of the measured input is the residual.
     *
     * @param head           the head-block parts' tokens by row id ({@link #A2}, {@link #A4}, {@link #B1}, {@link #B2})
     * @param earlierAsked   the prompts of the turns before this one
     * @param earlierAnswered their answers
     * @param stepsAnswered  the answers of this turn's earlier steps
     * @param stepsCalls     the tool calls of this turn's earlier steps
     * @param prompt         this request's prompt
     * @param firstStep      whether this is the turn's first request (the prompt is the turn's input then)
     */
    public static Map<String, Integer> splitCliRequest(Map<String, Integer> head, String earlierAsked, String earlierAnswered,
                                                       String stepsAnswered, String stepsCalls, String prompt, boolean firstStep) {
        Parts parts = new Parts();
        parts.add(C1, earlierAsked);
        parts.add(C2, earlierAnswered);
        parts.add(D2, stepsAnswered);
        parts.add(D3, stepsCalls);
        parts.add(firstStep ? D1 : D4, prompt);
        Map<String, Integer> rows = parts.tokens(parts.attributedChars);
        for (String id : List.of(A2, A4, B1, B2)) rows.put(id, head.getOrDefault(id, 0));
        return rows;
    }

    // ── Requests from the log ────────────────────────────────────────────────────────────────────────────────────

    /** @return the requests of a provider that sends its own request body ({@code openai-compat}), split by {@link #splitOpenAiBody} */
    public static List<Req> openAiRequests(List<LlmRow> rows) {
        List<Req> out = new ArrayList<>();
        for (LlmRow r : rows) out.add(req(r, splitOpenAiBody(r.request())));
        return out;
    }

    /** @return the requests of a CLI harness, split by {@link #splitCliRequest} from the log's own text and {@code head} */
    public static List<Req> cliRequests(List<LlmRow> rows, Map<String, Integer> head) {
        List<Req> out = new ArrayList<>();
        StringBuilder askedBefore = new StringBuilder(), answeredBefore = new StringBuilder();
        StringBuilder stepsAnswered = new StringBuilder(), stepsCalls = new StringBuilder();
        int turn = Integer.MIN_VALUE;
        StringBuilder turnAsked = new StringBuilder(), turnAnswered = new StringBuilder();
        for (LlmRow r : rows) {
            boolean first = r.turn() != turn;
            if (first) {
                askedBefore.append(turnAsked);
                answeredBefore.append(turnAnswered);
                turnAsked.setLength(0);
                turnAnswered.setLength(0);
                stepsAnswered.setLength(0);
                stepsCalls.setLength(0);
                turn = r.turn();
            }
            String prompt = promptOf(r.request());
            out.add(req(r, splitCliRequest(head, askedBefore.toString(), answeredBefore.toString(),
                    stepsAnswered.toString(), stepsCalls.toString(), prompt, first)));
            turnAsked.append(prompt);
            turnAnswered.append(r.response());
            stepsAnswered.append(r.response());
            stepsCalls.append(r.toolCalls());
        }
        return out;
    }

    /** The text of the one user message chat-ui recorded for a CLI request. */
    static String promptOf(String request) {
        try {
            JSONArray messages = new JSONObject(request).optJSONArray("messages");
            StringBuilder b = new StringBuilder();
            for (int i = 0; messages != null && i < messages.length(); i++) {
                Object c = messages.getJSONObject(i).opt("content");
                if (c instanceof String s) b.append(s);
            }
            return b.toString();
        } catch (JSONException e) {
            return request == null ? "" : request;
        }
    }

    private static Req req(LlmRow r, Map<String, Integer> rows) {
        Integer measured = r.prompt() > 0 ? r.prompt() : null;
        Integer output = r.completion() > 0 ? r.completion() : null;
        int reasoning = output == null ? 0 : Math.min(output, HeadBlock.estimateTokens(r.reasoning()));
        return new Req(r.turn(), r.step(), rows, measured, r.cacheRead(), r.cacheCreation(), output, reasoning);
    }

    // ── The table ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the table from the requests, oldest first. The last column is the last request (the turn's last: the
     * window as full as it got); the cumulative column adds the requests that have both a split and, when any request
     * has one, a measured input, so rows plus residual equal the measured input in both.
     *
     * @param windowLimit the model's window in tokens, or {@code null} when it is not known
     * @param basis       what the row values are: {@code "estimated"} for a provider that can be split, {@code "not available"}
     *                    for one that cannot
     */
    public static Table table(String provider, String model, Integer windowLimit, String windowLimitSource, List<Req> reqs,
                              String basis, List<String> notes) {
        Req last = reqs.isEmpty() ? null : reqs.get(reqs.size() - 1);
        boolean anyMeasured = reqs.stream().anyMatch(r -> r.measuredTotal() != null);
        List<Req> counted = new ArrayList<>();
        for (Req r : reqs) if (r.rows() != null && (!anyMeasured || r.measuredTotal() != null)) counted.add(r);
        boolean hasRows = last != null && last.rows() != null;

        List<Line> lines = new ArrayList<>();
        int sumLast = 0;
        long sumCum = 0;
        for (RowDef d : ROWS) {
            Integer lastValue = hasRows ? last.rows().getOrDefault(d.id(), 0) : null;
            long cum = 0;
            for (Req r : counted) cum += r.rows().getOrDefault(d.id(), 0);
            if (lastValue != null) sumLast += lastValue;
            sumCum += cum;
            lines.add(new Line(d.id(), d.group(), d.label(), d.definition(), lastValue, counted.isEmpty() ? null : cum, basis));
        }
        Integer measuredLast = last == null ? null : last.measuredTotal();
        Long measuredCum = null;
        Long residualCum = null;
        if (anyMeasured && !counted.isEmpty()) {
            long m = 0;
            for (Req r : counted) m += r.measuredTotal();
            measuredCum = m;
            residualCum = m - sumCum;
        }
        Integer residualLast = hasRows && measuredLast != null ? measuredLast - sumLast : null;
        lines.add(new Line(RESIDUAL, "Other", "Not attributed (residual)", RESIDUAL_DEFINITION, residualLast, residualCum,
                anyMeasured ? "calculated" : "not available"));

        Line rowsSum = new Line("SUM", "Total", "Sum of the rows above", "", hasRows ? sumLast : null,
                counted.isEmpty() ? null : sumCum, hasRows ? "calculated" : "not available");
        Line measured = new Line("MEASURED", "Total", "Input the provider reported", "The whole input of the request.",
                measuredLast, measuredCum, anyMeasured ? "measured" : "not available");
        Integer freeLast = windowLimit == null || measuredLast == null ? null : windowLimit - measuredLast;
        Line free = new Line("FREE", "Total", "Window left", "The window's limit minus the input of the last request.",
                freeLast, null, freeLast == null ? "not available" : "calculated");

        List<Line> output = new ArrayList<>();
        Integer outLast = last == null ? null : last.output();
        long outCum = 0, reasonCum = 0;
        boolean anyOut = false;
        for (Req r : reqs) {
            if (r.output() != null) {
                anyOut = true;
                outCum += r.output();
                reasonCum += r.reasoningEstimate();
            }
        }
        Integer reasonLast = last == null || last.output() == null ? null : last.reasoningEstimate();
        output.add(new Line("G1", "Output", OUTPUT_ROWS.get(0).label(), OUTPUT_ROWS.get(0).definition(), outLast,
                anyOut ? outCum : null, anyOut ? "measured" : "not available"));
        output.add(new Line("G2", "Output", OUTPUT_ROWS.get(1).label(), OUTPUT_ROWS.get(1).definition(), reasonLast,
                anyOut ? reasonCum : null, anyOut ? "estimated" : "not available"));
        output.add(new Line("G3", "Output", OUTPUT_ROWS.get(2).label(), OUTPUT_ROWS.get(2).definition(),
                outLast == null ? null : outLast - reasonLast, anyOut ? outCum - reasonCum : null,
                anyOut ? "estimated" : "not available"));

        long cacheReadCum = 0, cacheCreationCum = 0;
        for (Req r : reqs) {
            cacheReadCum += r.cacheRead();
            cacheCreationCum += r.cacheCreation();
        }
        List<Line> cache = new ArrayList<>();
        cache.add(new Line("CACHE_READ", "Cache", "Cache read (part of the input reported)", "Input tokens served from the provider's cache.",
                last == null ? null : last.cacheRead(), anyMeasured ? cacheReadCum : null, anyMeasured ? "measured" : "not available"));
        cache.add(new Line("CACHE_CREATION", "Cache", "Cache creation (part of the input reported)",
                "Input tokens written to the provider's cache.", last == null ? null : last.cacheCreation(),
                anyMeasured ? cacheCreationCum : null, anyMeasured ? "measured" : "not available"));

        Table partial = new Table(provider, model, windowLimit, windowLimitSource, reqs.size(), counted.size(),
                last == null ? null : last.turn(), lines, rowsSum, measured, free, output, cache, List.of(), List.copyOf(notes));
        return new Table(provider, model, windowLimit, windowLimitSource, reqs.size(), counted.size(),
                last == null ? null : last.turn(), lines, rowsSum, measured, free, output, cache, topics(partial, reqs, cacheReadCum),
                List.copyOf(notes));
    }

    // ── Special topics ───────────────────────────────────────────────────────────────────────────────────────────

    private static Integer value(Table t, String id) {
        for (Line l : t.rows()) if (l.id().equals(id)) return l.lastRequest();
        return null;
    }

    private static String percent(Integer part, Integer whole) {
        if (part == null || whole == null || whole <= 0) return "n/a";
        return String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

    /** The table of special topics, from the same figures ({@code tasks/tokens-tab-design.md}, section 4). */
    static List<Topic> topics(Table t, List<Req> reqs, long cacheReadCum) {
        List<Topic> out = new ArrayList<>();
        Integer used = t.measured().lastRequest() != null ? t.measured().lastRequest() : t.rowsSum().lastRequest();
        Integer history = sum(value(t, C1), value(t, C2));
        out.add(new Topic("History in the last request", history == null ? "n/a" : history + " tokens (" + percent(history, used) + ")",
                "Earlier turns' questions and answers; is the history bloating?"));
        // First request of each turn, to see how fast the window fills.
        List<Integer> firsts = new ArrayList<>();
        int seen = Integer.MIN_VALUE;
        for (Req r : reqs) {
            if (r.turn() != seen && r.measuredTotal() != null) {
                firsts.add(r.measuredTotal());
                seen = r.turn();
            }
        }
        Integer growth = null;
        if (firsts.size() >= 2) {
            int from = Math.max(0, firsts.size() - 6);
            growth = (firsts.get(firsts.size() - 1) - firsts.get(from)) / (firsts.size() - 1 - from);
        }
        out.add(new Topic("Growth per turn (the last 5 turns)", growth == null ? "n/a" : growth + " tokens",
                "How much the first request grows from one turn to the next."));
        Integer left = t.free().lastRequest();
        out.add(new Topic("Turns left before the window is full",
                left == null || growth == null || growth <= 0 ? "n/a" : String.valueOf(left / growth),
                "The window left divided by the growth per turn."));
        Integer results = value(t, D4);
        out.add(new Topic("Tool results in this turn", results == null ? "n/a" : results + " tokens (" + percent(results, used) + ")",
                "Do long observations fill the window?"));
        Integer head = null;
        for (String id : List.of(A1, A2, A3, A4, B1, B2)) head = sum(head, value(t, id));
        out.add(new Topic("Fixed head of every request", head == null ? "n/a" : head + " tokens (" + percent(head, t.windowLimit()) + " of the window)",
                "System prompt, skills, instructions and tool definitions: sent again with every request."));
        Long measuredCum = t.measured().cumulative();
        out.add(new Topic("Share of the input served from the cache (all requests)",
                measuredCum == null || measuredCum <= 0 ? "n/a"
                        : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * cacheReadCum / measuredCum),
                "Cache read divided by the input reported; a low share means the head keeps changing."));
        Integer residual = t.rows().get(t.rows().size() - 1).lastRequest();
        out.add(new Topic("Residual of the last request",
                residual == null ? "n/a" : residual + " tokens (" + (residual < 0 ? "estimates too high" : "not attributed") + ")",
                "How far the rows are from the measured input; how far to trust them."));
        Line g1 = t.output().get(0), g2 = t.output().get(1);
        out.add(new Topic("Reasoning share of the output (all requests)",
                g1.cumulative() == null || g1.cumulative() <= 0 ? "n/a"
                        : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * g2.cumulative() / g1.cumulative()),
                "Does reasoning eat the output?"));
        Integer images = value(t, E1);
        out.add(new Topic("Images in the last request", images == null ? "n/a" : images > 0 ? "yes" : "none",
                "One image changes the input a lot, and its tokens are in the residual."));
        out.add(new Topic("Measured from turn", reqs.stream().filter(r -> r.measuredTotal() != null).findFirst()
                .map(r -> String.valueOf(r.turn())).orElse("n/a"),
                "Usage recording began at a restart, so an older conversation is measured only from this turn on."));
        return out;
    }

    private static Integer sum(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    // ── Head-block parts → rows ──────────────────────────────────────────────────────────────────────────────────

    /** @return the row a head-block part belongs to ({@link #A2}, {@link #A4}, {@link #B1} or {@link #B2}), or {@code null} for none */
    public static String rowOfHeadPart(String partName) {
        if (partName == null) return null;
        if (partName.startsWith("CLAUDE.md files") || partName.startsWith("MEMORY.md index")) return A4;
        if (partName.startsWith("Skill list")) return A2;
        if (partName.startsWith("chat-ui's MCP server")) return B1;
        if (partName.startsWith("MCP server ")) return B2;
        return null;
    }
}
