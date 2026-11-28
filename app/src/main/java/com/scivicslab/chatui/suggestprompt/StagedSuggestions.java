package com.scivicslab.chatui.suggestprompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three choices of the "back to the big picture" entry of Suggest prompt: topic, step, fixed question. Reads a
 * {@link SuggestFrame} and the fixed questions; touches no DB and no LLM.
 */
public final class StagedSuggestions {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(term|topic|step)}");

    private StagedSuggestions() {
    }

    public record TopicChoice(int topicId, String title, String workType, String delegateSquad) {
    }

    public record StepChoice(int seq, String title, String progress) {
    }

    /** A fixed question with its placeholders filled in. */
    public record Question(String questionId, String text) {
    }

    /** @param skip set when the stage need not be shown: one choice only, or none */
    public record TopicStage(List<TopicChoice> choices, boolean skip) {
    }

    /** @param skip set when the topic has no step */
    public record StepStage(List<StepChoice> choices, boolean skip) {
    }

    /** The open topics. Skipped when there is exactly one. */
    public static TopicStage topicStage(SuggestFrame frame) {
        List<TopicChoice> choices = new ArrayList<>();
        for (SuggestFrame.Topic t : frame.topics()) {
            if (t.status().equals("open")) choices.add(new TopicChoice(t.topicId(), t.title(), t.workType(), t.delegateSquad()));
        }
        return new TopicStage(List.copyOf(choices), choices.size() == 1);
    }

    /** The steps of the chosen topic in {@code seq} order. Skipped when there are none (or the topic is unknown). */
    public static StepStage stepStage(SuggestFrame frame, int topicId) {
        SuggestFrame.Topic topic = find(frame, topicId);
        if (topic == null) return new StepStage(List.of(), true);
        List<StepChoice> choices = topic.steps().stream()
                .sorted(java.util.Comparator.comparingInt(SuggestFrame.Step::seq))
                .map(s -> new StepChoice(s.seq(), s.title(), s.progress()))
                .toList();
        return new StepStage(choices, choices.isEmpty());
    }

    /**
     * The fixed questions of the chosen topic: the common ones and those of its work type, in the order of
     * {@code questions}. {@code {topic}} becomes the topic's title and {@code {step}} the chosen step's title; a
     * question with {@code {step}} is left out when no step is chosen. A question with {@code {term}} is given once for
     * each term of the topic, and not at all when the topic has none.
     *
     * @param stepSeq the chosen step, or {@code null} when none is chosen
     */
    public static List<Question> questionStage(SuggestFrame frame, int topicId, Integer stepSeq,
                                               List<SuggestQuestion> questions) {
        SuggestFrame.Topic topic = find(frame, topicId);
        if (topic == null) return List.of();
        String stepTitle = null;
        if (stepSeq != null) {
            for (SuggestFrame.Step s : topic.steps()) if (s.seq() == stepSeq) stepTitle = s.title();
        }
        List<Question> out = new ArrayList<>();
        for (SuggestQuestion q : questions) {
            if (!q.workType().equals(SuggestFrame.COMMON) && !q.workType().equals(topic.workType())) continue;
            boolean hasStep = q.template().contains("{step}");
            boolean hasTerm = q.template().contains("{term}");
            if (hasStep && stepTitle == null) continue;
            if (hasTerm) {
                for (SuggestFrame.Term term : topic.terms()) {
                    out.add(new Question(q.questionId(), fill(q.template(), topic.title(), stepTitle, term.term())));
                }
            } else {
                out.add(new Question(q.questionId(), fill(q.template(), topic.title(), stepTitle, null)));
            }
        }
        return List.copyOf(out);
    }

    /** One pass, so a title that itself contains {@code {step}} is not expanded again. */
    private static String fill(String template, String topic, String step, String term) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case "topic" -> topic;
                case "step" -> step;
                default -> term;
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static SuggestFrame.Topic find(SuggestFrame frame, int topicId) {
        for (SuggestFrame.Topic t : frame.topics()) if (t.topicId() == topicId) return t;
        return null;
    }
}
