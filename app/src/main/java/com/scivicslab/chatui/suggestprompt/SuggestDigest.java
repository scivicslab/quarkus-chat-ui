package com.scivicslab.chatui.suggestprompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The current work frame as the JSON the local LLM is given when it is asked for a patch. The keys are the ones a
 * patch uses. It does not write the preamble sentence ({@code SuggestPreamble} does).
 */
public final class SuggestDigest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SuggestDigest() {
    }

    /**
     * @return {@code {"role", "overview", "topics": [...]}}; topics in {@code topic_id} order, none with status
     *         {@code dropped}; {@code delegate_squad} only for a superior that has one set
     */
    public static String of(SuggestFrame frame) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("role", frame.role());
        root.put("overview", frame.overview());
        List<Object> topics = new ArrayList<>();
        frame.topics().stream()
                .filter(t -> !t.status().equals("dropped"))
                .sorted(Comparator.comparingInt(SuggestFrame.Topic::topicId))
                .forEach(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("topic_id", t.topicId());
                    m.put("title", t.title());
                    m.put("summary", t.summary());
                    m.put("work_type", t.workType());
                    m.put("status", t.status());
                    if (SuggestFrame.SUPERIOR.equals(frame.role()) && t.delegateSquad() != null) {
                        m.put("delegate_squad", t.delegateSquad());
                    }
                    List<Object> steps = new ArrayList<>();
                    for (SuggestFrame.Step s : t.steps()) {
                        Map<String, Object> sm = new LinkedHashMap<>();
                        sm.put("seq", s.seq());
                        sm.put("title", s.title());
                        sm.put("progress", s.progress());
                        sm.put("note", s.note());
                        steps.add(sm);
                    }
                    m.put("steps", steps);
                    m.put("terms", t.terms().stream().map(SuggestFrame.Term::term).toList());
                    topics.add(m);
                });
        root.put("topics", topics);
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
