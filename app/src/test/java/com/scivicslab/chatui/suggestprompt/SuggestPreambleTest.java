package com.scivicslab.chatui.suggestprompt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The preamble {@link SuggestPreamble} puts at the head of a suggested instruction. */
class SuggestPreambleTest {

    private static SuggestFrame.Topic topic(int id, String title, String type, String status, int last,
                                            List<SuggestFrame.Step> steps) {
        return new SuggestFrame.Topic(id, title, "", type, null, status, last, steps, List.of());
    }

    private static SuggestFrame frame(List<SuggestFrame.Topic> topics) {
        return new SuggestFrame("s", 1, "standalone", "overview", 5, 3, 1L, topics);
    }

    private static final List<SuggestFrame.Step> STEPS = List.of(
            new SuggestFrame.Step(1, "Write the POJO", "done", ""),
            new SuggestFrame.Step(2, "Write the tests", "in-progress", ""),
            new SuggestFrame.Step(3, "Run the build", "not-started", ""));

    private static final SuggestFrame THREE = frame(List.of(
            topic(1, "Rename classes", "implement", "open", 2, List.of()),
            topic(2, "Design the frame", "design", "open", 5, STEPS),
            topic(3, "Old idea", "write", "dropped", 9, List.of()),
            topic(4, "Finished work", "verify", "done", 8, List.of()),
            topic(5, "Check the build", "verify", "open", 5, List.of())));

    @Test
    void theBigPictureEntryGivesTheFirstLineOfOpenTopicsNewestFirstAndThePlanOfTheChosenTopic() {
        String text = SuggestPreamble.of(THREE, 2);
        System.out.print(text);
        assertEquals("So far we have been working on: Check the build (verify, open); Design the frame (design, open); "
                + "Rename classes (implement, open).\n"
                + "For \"Design the frame\", the plan is: 1 Write the POJO [done]; 2 Write the tests [in-progress]; "
                + "3 Run the build [not-started].\n", text);
    }

    @Test
    void theDefaultEntryGivesTheFirstLineOnly() {
        String text = SuggestPreamble.forDefaultEntry(THREE);
        System.out.print(text);
        assertEquals("So far we have been working on: Check the build (verify, open); Design the frame (design, open); "
                + "Rename classes (implement, open).\n", text);
        assertEquals(text, SuggestPreamble.of(THREE, null));
    }

    @Test
    void anEmptyFrameOrOneWithNoOpenTopicGivesNothing() {
        assertEquals("", SuggestPreamble.of(SuggestFrame.empty("s", 1, "standalone"), null));
        assertEquals("", SuggestPreamble.of(SuggestFrame.empty("s", 1, "standalone"), 1));
        assertEquals("", SuggestPreamble.of(frame(List.of(topic(1, "Done one", "write", "done", 1, STEPS))), 1));
        assertEquals("", SuggestPreamble.of(new SuggestFrame("s", 1, "standalone", "only an overview", 1, 1, 1L,
                List.of()), null));
    }

    @Test
    void theSecondLineIsLeftOutForAMissingDroppedOrStepLessTopic() {
        String first = SuggestPreamble.forDefaultEntry(THREE);
        assertEquals(first, SuggestPreamble.of(THREE, 99), "no such topic");
        assertEquals(first, SuggestPreamble.of(THREE, 3), "dropped topic");
        assertEquals(first, SuggestPreamble.of(THREE, 1), "no steps");
    }

    @Test
    void aTitleWithNewlinesStaysOnItsLine() {
        String text = SuggestPreamble.of(frame(List.of(topic(1, "two\nlines\t here", "write", "open", 1, List.of()))), null);
        System.out.print(text);
        assertEquals("So far we have been working on: two lines here (write, open).\n", text);
    }

    @Test
    void manyTopicsAreCutAtWholeTopicsKeepingTheNewestAndSayHowManyAreLeftOut() {
        List<SuggestFrame.Topic> topics = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            topics.add(topic(i, "Topic number " + i + " " + "w".repeat(70), "investigate", "open", i, List.of()));
        }
        String text = SuggestPreamble.forDefaultEntry(frame(topics));
        System.out.println(text.length() + " chars:\n" + text);
        assertTrue(text.length() <= SuggestPreamble.MAX_CHARS);
        assertTrue(text.startsWith("So far we have been working on: Topic number 12 "), "newest first");
        assertTrue(text.contains("Topic number 11 "));
        assertFalse(text.contains("Topic number 1 "), "the oldest was left out");
        assertTrue(text.matches("(?s).*; and \\d+ more\\.\n"), text);
        assertEquals(1, text.chars().filter(c -> c == '\n').count());
    }

    @Test
    void aLongPlanIsCutAtWholeStepsAndStillLeavesRoomForTheFirstLine() {
        List<SuggestFrame.Step> steps = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            steps.add(new SuggestFrame.Step(i, "Step " + i + " " + "s".repeat(70), "in-progress", ""));
        }
        List<SuggestFrame.Topic> topics = new ArrayList<>();
        topics.add(topic(1, "Big " + "b".repeat(70), "implement", "open", 9, steps));
        for (int i = 2; i <= 12; i++) topics.add(topic(i, "Other " + i + " " + "o".repeat(70), "write", "open", 1, List.of()));
        String text = SuggestPreamble.of(frame(topics), 1);
        System.out.println(text.length() + " chars:\n" + text);
        assertTrue(text.length() <= SuggestPreamble.MAX_CHARS);
        String[] lines = text.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("So far we have been working on: Big "));
        assertTrue(lines[1].startsWith("For \"Big "));
        assertTrue(lines[1].contains("1 Step 1 "));
        assertTrue(lines[1].matches(".*; and \\d+ more steps\\.$"), lines[1]);
    }
}
