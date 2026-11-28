package com.scivicslab.chatui.suggestprompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The preamble put at the head of a suggested instruction, built by code from the work frame; the LLM does not write
 * it. Plain steps that touch neither a model nor a database.
 *
 * <pre>
 * So far we have been working on: &lt;topic A&gt; (&lt;work type&gt;, &lt;status&gt;); &lt;topic B&gt; (...).
 * For "&lt;chosen topic&gt;", the plan is: 1 &lt;step&gt; [done]; 2 &lt;step&gt; [in-progress].
 * </pre>
 */
public final class SuggestPreamble {

    /** The whole preamble, newline included, is at most this many characters. */
    public static final int MAX_CHARS = 1200;

    /** What is kept free for the first line while the second is fitted: the lead and one topic always fit. */
    private static final int FIRST_LINE_RESERVE = 200;

    private static final String LEAD = "So far we have been working on: ";

    private SuggestPreamble() {
    }

    /** @return the preamble of the default entry: the first line only; empty when the frame has no open topic */
    public static String forDefaultEntry(SuggestFrame frame) {
        return of(frame, null);
    }

    /**
     * @param frame         the work frame
     * @param chosenTopicId the topic chosen at the big-picture entry, or {@code null} for the default entry
     * @return the lines, each ending with a newline; empty when the frame is empty or has no open topic. The second
     *         line is left out when the chosen topic does not exist, is dropped or has no step. Open topics are listed
     *         newest first ({@code lastTurn}, then {@code topicId}, descending); those that do not fit are replaced by
     *         "and N more", never cut in the middle.
     */
    public static String of(SuggestFrame frame, Integer chosenTopicId) {
        if (frame.isEmpty()) return "";
        List<SuggestFrame.Topic> open = new ArrayList<>(
                frame.topics().stream().filter(t -> t.status().equals("open")).toList());
        if (open.isEmpty()) return "";
        open.sort(Comparator.comparingInt(SuggestFrame.Topic::lastTurn).thenComparingInt(SuggestFrame.Topic::topicId)
                .reversed());

        String second = chosenTopicId == null ? "" : planLine(frame, chosenTopicId).orElse("");
        int firstBudget = MAX_CHARS - (second.isEmpty() ? 0 : second.length() + 1) - 1;
        String first = firstLine(open, firstBudget);
        return first + "\n" + (second.isEmpty() ? "" : second + "\n");
    }

    private static String firstLine(List<SuggestFrame.Topic> open, int budget) {
        List<String> items = open.stream()
                .map(t -> one(t.title()) + " (" + t.workType() + ", " + t.status() + ")").toList();
        for (int n = items.size(); n >= 1; n--) {
            String line = LEAD + String.join("; ", items.subList(0, n))
                    + (n < items.size() ? "; and " + (items.size() - n) + " more" : "") + ".";
            if (line.length() <= budget) return line;
        }
        return LEAD + items.get(0) + ".";
    }

    private static Optional<String> planLine(SuggestFrame frame, int topicId) {
        SuggestFrame.Topic topic = frame.topics().stream()
                .filter(t -> t.topicId() == topicId && !t.status().equals("dropped")).findFirst().orElse(null);
        if (topic == null || topic.steps().isEmpty()) return Optional.empty();
        String head = "For \"" + one(topic.title()) + "\", the plan is: ";
        List<String> items = topic.steps().stream()
                .map(s -> s.seq() + " " + one(s.title()) + " [" + s.progress() + "]").toList();
        int budget = MAX_CHARS - FIRST_LINE_RESERVE - 1;
        for (int n = items.size(); n >= 1; n--) {
            String line = head + String.join("; ", items.subList(0, n))
                    + (n < items.size() ? "; and " + (items.size() - n) + " more steps" : "") + ".";
            if (line.length() <= budget) return Optional.of(line);
        }
        return Optional.empty();
    }

    /** A title is the conversation's words on one line. */
    private static String one(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
