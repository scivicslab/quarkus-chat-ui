package com.scivicslab.chatui.suggestprompt;

import java.util.List;

/**
 * The change a local LLM proposes to a work frame: upserts of topics, steps and terms. There is no deletion; a topic
 * leaves the frame by status {@code dropped}. A field left {@code null} was not written. Validation and application are
 * in {@link SuggestFrame#apply}.
 *
 * @param overview one to two sentences, or {@code null} to keep the current one
 * @param topics   a topic with no {@code topicId} is new; one with a {@code topicId} updates only the fields written
 * @param steps    a step with a {@code seq} that exists is updated; one with no {@code seq} is added at the end
 * @param terms    terms that came up in the conversation
 */
public record SuggestPatch(String overview, List<TopicPatch> topics, List<StepPatch> steps, List<TermPatch> terms) {

    public SuggestPatch {
        topics = topics == null ? List.of() : List.copyOf(topics);
        steps = steps == null ? List.of() : List.copyOf(steps);
        terms = terms == null ? List.of() : List.copyOf(terms);
    }

    /** A patch that changes nothing. */
    public static SuggestPatch empty() {
        return new SuggestPatch(null, null, null, null);
    }

    public record TopicPatch(Integer topicId, String title, String summary, String workType, String status) {
    }

    public record StepPatch(Integer topicId, Integer seq, String title, String progress, String note) {
    }

    public record TermPatch(Integer topicId, String term) {
    }
}
