package com.scivicslab.chatui.tmux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured content from tmux {@code capture-pane} snapshots of the
 * Claude Code interactive TUI.
 *
 * <p>Each {@code capture-pane} call returns the whole rendered screen (or the
 * whole scrollback), which redraws and grows over time. This class turns those
 * snapshots into a stream of {@link ExtractedEvent}s by:
 * <ol>
 *   <li>detecting permission dialogs (numbered choice boxes);</li>
 *   <li>stripping TUI chrome (banner box, input box, status and hint lines,
 *       the thinking spinner);</li>
 *   <li>diffing against the previous snapshot to keep only newly added lines;</li>
 *   <li>parsing line-head markers ({@code ●} assistant, {@code ❯} user).</li>
 * </ol>
 *
 * <p>The methods that do the parsing are static and side-effect free so they
 * can be unit-tested directly against captured fixtures. Only {@link #ingest}
 * holds state (the previous content baseline for diffing).
 *
 * <p>This class is intentionally not thread-safe: in the actor design a single
 * {@code ScreenExtractorActor} owns one instance and feeds it serially.
 */
public final class ScreenExtractor {

    private static final char[] BOX_CHARS = {'╭', '╮', '╰', '╯', '│', '├', '┤'};

    /** Matches a numbered option line such as {@code "❯ 1. Yes, I trust this folder"}. */
    private static final Pattern OPTION = Pattern.compile("^\\s*❯?\\s*(\\d+)\\.\\s+(.*\\S)\\s*$");

    /** Previous content baseline (chrome-stripped lines) used for diffing. */
    private List<String> lastContent = List.of();

    /**
     * Ingests one {@code capture-pane} snapshot and returns the events newly
     * observed since the previous snapshot.
     *
     * <p>If a permission dialog is present, it is returned as the sole event and
     * the content baseline is left untouched (the conversation has not advanced
     * until the user answers).
     *
     * @param capture the raw text returned by {@code tmux capture-pane -p}
     * @return the events newly observed in this snapshot (possibly empty)
     */
    public List<ExtractedEvent> ingest(String capture) {
        if (capture == null) {
            return List.of();
        }
        Optional<ApprovalRequested> approval = detectApproval(capture);
        if (approval.isPresent()) {
            return List.of(approval.get());
        }
        List<String> content = stripChrome(capture);
        List<String> fresh = suffixAfterCommonPrefix(lastContent, content);
        lastContent = content;
        return parseEvents(fresh);
    }

    // --- Pure helpers (static, directly unit-testable) ---

    /**
     * Removes TUI chrome and returns the content lines. Blank lines are kept as
     * separators but collapsed (no leading/trailing blanks, no runs).
     *
     * @param capture the raw {@code capture-pane} text
     * @return the chrome-stripped content lines
     */
    static List<String> stripChrome(String capture) {
        List<String> kept = new ArrayList<>();
        for (String raw : capture.lines().toList()) {
            String s = raw.strip();
            if (s.isEmpty()) {
                kept.add("");
            } else if (!isChrome(s)) {
                kept.add(s);
            }
        }
        return collapseBlanks(kept);
    }

    /**
     * Detects a permission / confirmation dialog: at least one numbered option
     * plus an {@code Enter to confirm} line.
     *
     * @param capture the raw {@code capture-pane} text
     * @return the dialog, or empty if none is present
     */
    static Optional<ApprovalRequested> detectApproval(String capture) {
        List<String> lines = capture.lines().toList();
        // Different dialogs use different footers: the trust dialog says "Enter to confirm",
        // tool-permission dialogs say "Esc to cancel · Tab to amend". Both contain "Esc to cancel".
        boolean hasConfirm = lines.stream().anyMatch(l ->
                l.contains("Enter to confirm") || l.contains("Esc to cancel"));
        List<String> options = new ArrayList<>();
        int firstOptionIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = OPTION.matcher(lines.get(i));
            if (m.matches()) {
                if (firstOptionIdx < 0) {
                    firstOptionIdx = i;
                }
                options.add(m.group(2).strip());
            }
        }
        if (!hasConfirm || options.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < firstOptionIdx; i++) {
            String l = lines.get(i).strip();
            if (l.isEmpty() || isChrome(l)) {
                continue;
            }
            if (prompt.length() > 0) {
                prompt.append(' ');
            }
            prompt.append(l);
        }
        return Optional.of(new ApprovalRequested(prompt.toString(), List.copyOf(options)));
    }

    /**
     * Tests whether the TUI is at the idle, input-ready state. The Claude Code TUI
     * shows its shortcuts hint line only once it has finished booting and is waiting
     * for input, so its presence is a reliable readiness signal.
     *
     * @param capture the raw {@code capture-pane} text
     * @return {@code true} if the input-ready hint line is present
     */
    static boolean isInputReady(String capture) {
        return capture != null && capture.contains("? for shortcuts");
    }

    /**
     * Returns the suffix of {@code current} after its longest common prefix with
     * {@code previous}. Because each snapshot is a superset (history grows by
     * appending), this yields the newly added lines.
     *
     * @param previous the previous content baseline
     * @param current  the current content lines
     * @return the lines present in {@code current} beyond the shared prefix
     */
    static List<String> suffixAfterCommonPrefix(List<String> previous, List<String> current) {
        int i = 0;
        while (i < previous.size() && i < current.size()
                && previous.get(i).equals(current.get(i))) {
            i++;
        }
        return new ArrayList<>(current.subList(i, current.size()));
    }

    /**
     * Parses content lines into events. A line starting with the {@code ●}
     * marker becomes an {@link AssistantMessage}, absorbing following
     * non-blank, non-marker lines as continuation.
     *
     * @param lines chrome-stripped content lines
     * @return the events parsed from the lines
     */
    private static final String BULLET = "● ";
    private static final String TOOL_RESULT = "⎿";

    static List<ExtractedEvent> parseEvents(List<String> lines) {
        List<ExtractedEvent> events = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(BULLET)) {
                int end = continuationEnd(lines, i);
                events.add(new AssistantMessage(joinBlock(lines, i, end, BULLET.length())));
                i = end - 1;
            } else if (line.startsWith(TOOL_RESULT)) {
                int end = continuationEnd(lines, i);
                events.add(new ToolResult(joinBlock(lines, i, end, TOOL_RESULT.length())));
                i = end - 1;
            }
        }
        return events;
    }

    /** Index of the first line after the block: the next blank line or marker. */
    private static int continuationEnd(List<String> lines, int start) {
        int j = start + 1;
        while (j < lines.size() && !lines.get(j).isEmpty() && !isMarker(lines.get(j))) {
            j++;
        }
        return j;
    }

    private static boolean isMarker(String line) {
        return line.startsWith(BULLET) || line.startsWith(TOOL_RESULT) || line.startsWith("❯");
    }

    /** Joins the marker line (after {@code skip} chars) with its continuation lines. */
    private static String joinBlock(List<String> lines, int start, int end, int skip) {
        StringBuilder body = new StringBuilder(lines.get(start).substring(skip).strip());
        for (int k = start + 1; k < end; k++) {
            body.append(' ').append(lines.get(k).strip());
        }
        return body.toString();
    }

    /**
     * Tests whether a stripped line is TUI chrome rather than content.
     *
     * @param stripped a non-blank, already-stripped line
     * @return {@code true} if the line is chrome and should be dropped
     */
    static boolean isChrome(String stripped) {
        for (char c : BOX_CHARS) {
            if (stripped.indexOf(c) >= 0) {
                return true;
            }
        }
        if (isHorizontalRule(stripped)) {
            return true;
        }
        if (stripped.contains("? for shortcuts") || stripped.contains("tmux detected")) {
            return true;
        }
        if (stripped.startsWith("✻")) {
            return true;
        }
        // Empty input box: a lone prompt caret with nothing typed.
        return stripped.startsWith("❯") && stripped.substring(1).strip().isEmpty();
    }

    private static boolean isHorizontalRule(String s) {
        long dashes = s.chars().filter(c -> c == '─').count();
        return dashes >= 10 && s.chars().allMatch(c -> c == '─' || c == ' ');
    }

    private static List<String> collapseBlanks(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean prevBlank = true; // drop leading blanks
        for (String line : lines) {
            boolean blank = line.isEmpty();
            if (blank && prevBlank) {
                continue;
            }
            out.add(line);
            prevBlank = blank;
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
