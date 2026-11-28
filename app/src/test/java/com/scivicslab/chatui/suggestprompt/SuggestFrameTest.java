package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.suggestprompt.SuggestPatch.StepPatch;
import com.scivicslab.chatui.suggestprompt.SuggestPatch.TermPatch;
import com.scivicslab.chatui.suggestprompt.SuggestPatch.TopicPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link SuggestFrame} decides: applying a patch element by element, and the epoch. */
class SuggestFrameTest {

    private static final String SQUAD = "project1/chat-01";

    private static SuggestFrame empty() {
        return SuggestFrame.empty(SQUAD, 1, SuggestFrame.SUPERIOR);
    }

    private static TopicPatch newTopic(String title, String workType) {
        return new TopicPatch(null, title, null, workType, null);
    }

    private static SuggestPatch topics(TopicPatch... t) {
        return new SuggestPatch(null, List.of(t), null, null);
    }

    private static List<String> reasons(SuggestFrame.Applied a) {
        return a.rejected().stream().map(r -> r.element() + ": " + r.reason()).toList();
    }

    @Test
    void newTopicsGetSequentialIdsFromOneAndOpenStatusAndTheTurn() {
        SuggestFrame.Applied a = empty().apply(topics(newTopic("Rename", "implement"), newTopic("Design", "design")), 4, 1000L);
        assertEquals(List.of(), a.rejected());
        assertEquals(List.of(1, 2), a.frame().topics().stream().map(SuggestFrame.Topic::topicId).toList());
        SuggestFrame.Topic t = a.frame().topics().get(0);
        assertEquals("Rename", t.title());
        assertEquals("implement", t.workType());
        assertEquals("open", t.status());
        assertEquals("", t.summary());
        assertEquals(4, t.lastTurn());
        assertEquals(1, a.frame().revision());
        assertEquals(1000L, a.frame().updatedAt());
        assertEquals(0, a.frame().digestedTurn(), "apply does not move digested_turn");
    }

    @Test
    void theFrameIsNotChangedByApply() {
        SuggestFrame before = empty();
        before.apply(topics(newTopic("Rename", "implement")), 4, 1000L);
        assertTrue(before.topics().isEmpty());
        assertEquals(0, before.revision());
    }

    @Test
    void anElementThatFailsIsLeftOutAndReportedAndTheRestIsApplied() {
        SuggestPatch patch = new SuggestPatch("Rebuilding the connector.",
                List.of(newTopic("Rename", "implement"),
                        newTopic("", "design"),
                        newTopic("Bad type", "paint"),
                        new TopicPatch(9, "x", null, null, null)),
                List.of(new StepPatch(1, null, "Move classes", "in-progress", "n1"),
                        new StepPatch(1, 7, "x", null, null),
                        new StepPatch(1, null, "Bad progress", "bogus", null)),
                List.of(new TermPatch(1, "epoch"), new TermPatch(1, "epoch"), new TermPatch(3, "x")));
        SuggestFrame.Applied a = empty().apply(patch, 4, 1000L);
        assertEquals("Rebuilding the connector.", a.frame().overview());
        assertEquals(1, a.frame().topics().size());
        assertEquals(List.of("Move classes"), a.frame().topics().get(0).steps().stream().map(SuggestFrame.Step::title).toList());
        assertEquals(List.of("epoch"), a.frame().topics().get(0).terms().stream().map(SuggestFrame.Term::term).toList());
        assertEquals(List.of(
                "topics[1]: a new topic needs a title: empty",
                "topics[2]: a new topic needs a work_type of the closed vocabulary, got \"paint\"",
                "topics[3]: no topic with topic_id 9",
                "steps[1]: topic 1 has no step with seq 7",
                "steps[2]: progress is not in the vocabulary: \"bogus\"",
                "terms[1]: the term is already recorded for the topic",
                "terms[2]: no topic with topic_id 3"), reasons(a));
    }

    @Test
    void anUpdateChangesOnlyTheFieldsWrittenAndATouchedTopicTakesTheTurn() {
        SuggestFrame f = empty().apply(new SuggestPatch(null,
                List.of(new TopicPatch(null, "Rename", "first summary", "implement", null), newTopic("Design", "design")),
                List.of(new StepPatch(1, null, "Move classes", "in-progress", "n1")), null), 4, 1000L).frame();
        SuggestFrame g = f.apply(new SuggestPatch(null,
                List.of(new TopicPatch(1, null, null, null, "done")),
                List.of(new StepPatch(1, 1, null, "done", null)), null), 6, 2000L).frame();
        SuggestFrame.Topic t = g.topics().get(0);
        assertEquals("Rename", t.title());
        assertEquals("first summary", t.summary());
        assertEquals("implement", t.workType());
        assertEquals("done", t.status());
        assertEquals("Move classes", t.steps().get(0).title());
        assertEquals("done", t.steps().get(0).progress());
        assertEquals("n1", t.steps().get(0).note(), "a note that was not written stays");
        assertEquals(6, t.lastTurn());
        assertEquals(4, g.topics().get(1).lastTurn(), "a topic the patch did not touch keeps its turn");
        assertEquals(2, g.revision());
        assertEquals(2000L, g.updatedAt());
    }

    @Test
    void aStepWithNoSeqIsAddedAtTheEndAndSeqCountsFromOne() {
        SuggestFrame f = empty().apply(topics(newTopic("Rename", "implement")), 1, 1L).frame();
        SuggestFrame g = f.apply(new SuggestPatch(null, null,
                List.of(new StepPatch(1, null, "A", null, null), new StepPatch(1, null, "B", "blocked", null)), null), 2, 2L).frame();
        List<SuggestFrame.Step> steps = g.topics().get(0).steps();
        assertEquals(List.of(1, 2), steps.stream().map(SuggestFrame.Step::seq).toList());
        assertEquals("not-started", steps.get(0).progress());
        assertEquals("blocked", steps.get(1).progress());
    }

    @Test
    void aTermRecordsTheTurnItFirstCameUpIn() {
        SuggestFrame f = empty().apply(topics(newTopic("Rename", "implement")), 1, 1L).frame();
        SuggestFrame g = f.apply(new SuggestPatch(null, null, null, List.of(new TermPatch(1, "epoch"))), 5, 2L).frame();
        assertEquals(new SuggestFrame.Term("epoch", 5), g.topics().get(0).terms().get(0));
    }

    @Test
    void aTopicLeavesOnlyByStatusDroppedAndKeepsItsRowsAndItsId() {
        SuggestFrame f = empty().apply(topics(newTopic("Rename", "implement"), newTopic("Design", "design")), 1, 1L).frame();
        SuggestFrame g = f.apply(topics(new TopicPatch(2, null, null, null, "dropped")), 2, 2L).frame();
        assertEquals(2, g.topics().size());
        assertEquals("dropped", g.topics().get(1).status());
        SuggestFrame h = g.apply(topics(newTopic("Third", "write")), 3, 3L).frame();
        assertEquals(3, h.topics().get(2).topicId(), "an id is never used again");
    }

    @Test
    void textOverTheLimitIsDiscardedNotTruncated() {
        String t81 = "t".repeat(81);
        String t80 = "t".repeat(80);
        SuggestPatch patch = new SuggestPatch("o".repeat(301),
                List.of(newTopic(t81, "design"),
                        new TopicPatch(null, t80, "s".repeat(301), "design", null),
                        newTopic(t80, "design")),
                null, null);
        SuggestFrame.Applied a = empty().apply(patch, 1, 1L);
        assertEquals("", a.frame().overview());
        assertEquals(1, a.frame().topics().size());
        assertEquals(t80, a.frame().topics().get(0).title(), "exactly at the limit is kept whole");
        assertEquals(3, a.rejected().size());
        assertEquals("overview", a.rejected().get(0).element());
        assertEquals("longer than 300 characters (301)", a.rejected().get(0).reason());
        assertEquals("a new topic needs a title: longer than 80 characters (81)", a.rejected().get(1).reason());
        assertEquals("summary: longer than 300 characters (301)", a.rejected().get(2).reason());
    }

    @Test
    void noteAndTermLimits() {
        SuggestFrame f = empty().apply(topics(newTopic("Rename", "implement")), 1, 1L).frame();
        SuggestFrame.Applied a = f.apply(new SuggestPatch(null, null,
                List.of(new StepPatch(1, null, "A", null, "n".repeat(201)), new StepPatch(1, null, "B", null, "n".repeat(200))),
                List.of(new TermPatch(1, "x".repeat(41)), new TermPatch(1, "x".repeat(40)))), 2, 2L);
        assertEquals(List.of("B"), a.frame().topics().get(0).steps().stream().map(SuggestFrame.Step::title).toList());
        assertEquals(1, a.frame().topics().get(0).terms().size());
        assertEquals(List.of("steps[0]: note: longer than 200 characters (201)",
                "terms[0]: term: longer than 40 characters (41)"), reasons(a));
    }

    @Test
    void thirteenthOpenTopicStepAndTermAreDiscarded() {
        SuggestFrame f = empty();
        List<TopicPatch> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) twelve.add(newTopic("T" + i, "design"));
        f = f.apply(new SuggestPatch(null, twelve, null, null), 1, 1L).frame();
        SuggestFrame.Applied over = f.apply(topics(newTopic("T13", "design")), 2, 2L);
        assertEquals(12, over.frame().topics().size());
        assertEquals(List.of("topics[0]: there are already 12 open topics"), reasons(over));

        SuggestFrame done = f.apply(topics(new TopicPatch(1, null, null, null, "done")), 3, 3L).frame();
        assertEquals(13, done.apply(topics(newTopic("T13", "design")), 4, 4L).frame().topics().size(),
                "once one is done a new one fits");
        SuggestFrame.Applied reopen = done.apply(topics(newTopic("T13", "design"), new TopicPatch(1, null, null, null, "open")), 5, 5L);
        assertEquals(List.of("topics[1]: there are already 12 open topics"), reasons(reopen));

        List<StepPatch> steps = new ArrayList<>();
        List<TermPatch> terms = new ArrayList<>();
        for (int i = 1; i <= 13; i++) steps.add(new StepPatch(2, null, "S" + i, null, null));
        for (int i = 1; i <= 21; i++) terms.add(new TermPatch(2, "w" + i));
        SuggestFrame.Applied many = f.apply(new SuggestPatch(null, null, steps, terms), 6, 6L);
        assertEquals(12, many.frame().topics().get(1).steps().size());
        assertEquals(20, many.frame().topics().get(1).terms().size());
        assertEquals(List.of("steps[12]: the topic already has 12 steps", "terms[20]: the topic already has 20 terms"),
                reasons(many));
    }

    @Test
    void anEmptyPatchStillCountsAsARevision() {
        SuggestFrame.Applied a = empty().apply(SuggestPatch.empty(), 3, 9L);
        assertEquals(1, a.frame().revision());
        assertEquals(9L, a.frame().updatedAt());
        assertEquals(List.of(), a.rejected());
        assertEquals(1, empty().apply(null, 3, 9L).frame().revision());
    }

    @Test
    void roleAndDelegateAreNotInAPatchAndStay() {
        SuggestFrame withDelegate = new SuggestFrame(SQUAD, 1, SuggestFrame.SUPERIOR, "", 7, 3, 5L,
                List.of(new SuggestFrame.Topic(1, "Rename", "", "implement", "project1/chat-33", "open", 2, List.of(), List.of())));
        SuggestFrame g = withDelegate.apply(topics(new TopicPatch(1, "Rename 2", null, null, null)), 8, 6L).frame();
        assertEquals("project1/chat-33", g.topics().get(0).delegateSquad());
        assertEquals(SuggestFrame.SUPERIOR, g.role());
        assertEquals(7, g.digestedTurn());
        assertEquals(4, g.revision());
    }

    @Test
    void nextEpochIsEmptyWithRevisionAndDigestedTurnZeroAndTheOldFrameIsUntouched() {
        SuggestFrame f = new SuggestFrame(SQUAD, 3, SuggestFrame.SUPERIOR, "o", 9, 5, 77L,
                List.of(new SuggestFrame.Topic(1, "Rename", "", "implement", null, "open", 2, List.of(), List.of())));
        SuggestFrame n = f.nextEpoch(SuggestFrame.STANDALONE);
        assertEquals(new SuggestFrame(SQUAD, 4, SuggestFrame.STANDALONE, "", 0, 0, 0L, List.of()), n);
        assertTrue(n.isEmpty());
        assertEquals(3, f.epoch());
        assertEquals(1, f.topics().size());
        assertEquals(1, n.apply(topics(newTopic("A", "write")), 1, 1L).frame().topics().get(0).topicId(),
                "a new generation numbers its topics from 1 again");
    }
}
