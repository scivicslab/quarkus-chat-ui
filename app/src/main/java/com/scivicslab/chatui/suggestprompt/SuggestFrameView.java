package com.scivicslab.chatui.suggestprompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the screen reads of a work frame ({@code GET .../suggest-frame}): the topic, step and term lists, and when the
 * frame was last brought up to date. Plain steps; no DB and no LLM.
 */
public final class SuggestFrameView {

    private SuggestFrameView() {
    }

    /**
     * @param frame        the conversation's current work frame
     * @param finishedTurn the number of the last finished turn of the conversation
     * @return a JSON-ready map. {@code available} is whether the big-picture entry has anything to show (the frame has
     *         an open topic); {@code newTurns} is how many turns have finished since {@code digestedTurn}.
     */
    public static Map<String, Object> of(SuggestFrame frame, int finishedTurn) {
        StagedSuggestions.TopicStage stage = StagedSuggestions.topicStage(frame);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("available", !stage.choices().isEmpty());
        root.put("role", frame.role());
        root.put("epoch", frame.epoch());
        root.put("overview", frame.overview());
        root.put("revision", frame.revision());
        root.put("updatedAt", frame.updatedAt());
        root.put("digestedTurn", frame.digestedTurn());
        root.put("finishedTurn", finishedTurn);
        root.put("newTurns", Math.max(0, finishedTurn - frame.digestedTurn()));
        List<Map<String, Object>> topics = new ArrayList<>();
        for (StagedSuggestions.TopicChoice t : stage.choices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topicId", t.topicId());
            m.put("title", t.title());
            m.put("workType", t.workType());
            m.put("delegateSquad", t.delegateSquad());
            StagedSuggestions.StepStage steps = StagedSuggestions.stepStage(frame, t.topicId());
            List<Map<String, Object>> stepList = new ArrayList<>();
            for (StagedSuggestions.StepChoice s : steps.choices()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("seq", s.seq());
                sm.put("title", s.title());
                sm.put("progress", s.progress());
                stepList.add(sm);
            }
            m.put("skipSteps", steps.skip());
            m.put("steps", stepList);
            m.put("terms", frame.topics().stream().filter(x -> x.topicId() == t.topicId()).findFirst()
                    .map(x -> x.terms().stream().map(SuggestFrame.Term::term).toList()).orElse(List.of()));
            topics.add(m);
        }
        root.put("skipTopics", stage.skip());
        root.put("topics", topics);
        return root;
    }
}
