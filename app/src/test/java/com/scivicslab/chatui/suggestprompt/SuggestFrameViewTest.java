package com.scivicslab.chatui.suggestprompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the screen reads of a work frame: whether the big-picture entry has anything to show, and the lists. */
class SuggestFrameViewTest {

    private static SuggestFrame.Topic topic(int id, String title, String status, List<SuggestFrame.Step> steps) {
        return new SuggestFrame.Topic(id, title, "", "implement", null, status, 1, steps, List.of(new SuggestFrame.Term("pojo", 1)));
    }

    private static SuggestFrame frame(int revision, SuggestFrame.Topic... topics) {
        return new SuggestFrame("project1/chat-01", 1, "superior", "An overview.", 3, revision, 1000L, List.of(topics));
    }

    @Test
    void anEmptyFrameHasNothingToShow() {
        Map<String, Object> view = SuggestFrameView.of(SuggestFrame.empty("project1/chat-01", 1, "standalone"), 0);

        assertEquals(false, view.get("available"));
        assertEquals(0, view.get("revision"));
        assertEquals(List.of(), view.get("topics"));
    }

    @Test
    void aFrameWithOnlyClosedTopicsHasNothingToShow() {
        Map<String, Object> view = SuggestFrameView.of(frame(2, topic(1, "Old", "done", List.of()),
                topic(2, "Gone", "dropped", List.of())), 5);

        assertEquals(false, view.get("available"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void openTopicsAreListedWithTheirStepsAndTerms() {
        SuggestFrame f = frame(4, topic(1, "Rename", "open", List.of(new SuggestFrame.Step(1, "Move", "done", ""))),
                topic(2, "Design", "open", List.of()), topic(3, "Old", "done", List.of()));

        Map<String, Object> view = SuggestFrameView.of(f, 7);

        assertEquals(true, view.get("available"));
        assertEquals(false, view.get("skipTopics"));
        List<Map<String, Object>> topics = (List<Map<String, Object>>) view.get("topics");
        assertEquals(List.of("Rename", "Design"), topics.stream().map(t -> t.get("title")).toList());
        assertEquals(false, topics.get(0).get("skipSteps"));
        assertEquals(true, topics.get(1).get("skipSteps"));
        assertEquals(List.of("pojo"), topics.get(0).get("terms"));
        List<Map<String, Object>> steps = (List<Map<String, Object>>) topics.get(0).get("steps");
        assertEquals(1, steps.size());
        assertEquals("done", steps.get(0).get("progress"));
    }

    @Test
    void newTurnsCountsTheTurnsFinishedAfterDigestedTurn() {
        SuggestFrame f = frame(1, topic(1, "Rename", "open", List.of()));   // digestedTurn is 3

        assertEquals(4, SuggestFrameView.of(f, 7).get("newTurns"));
        assertEquals(0, SuggestFrameView.of(f, 3).get("newTurns"));
        assertEquals(0, SuggestFrameView.of(f, 1).get("newTurns"), "never negative");
        assertTrue((Boolean) SuggestFrameView.of(f, 7).get("skipTopics"), "one open topic skips the topic stage");
        assertFalse(SuggestFrameView.of(f, 7).isEmpty());
    }
}
