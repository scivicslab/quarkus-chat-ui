package com.scivicslab.chatui.suggestprompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link StagedSuggestions} decides: the three stages of the "back to the big picture" entry. */
class StagedSuggestionsTest {

    private static SuggestFrame.Topic topic(int id, String title, String workType, String status,
                                            List<SuggestFrame.Step> steps, List<SuggestFrame.Term> terms) {
        return new SuggestFrame.Topic(id, title, "", workType, null, status, 1, steps, terms);
    }

    private static SuggestFrame frame(SuggestFrame.Topic... topics) {
        return new SuggestFrame("project1/chat-01", 1, "standalone", "", 0, 1, 1L, List.of(topics));
    }

    private static final List<SuggestFrame.Step> TWO_STEPS = List.of(
            new SuggestFrame.Step(2, "Fix tests", "not-started", ""),
            new SuggestFrame.Step(1, "Move classes", "done", ""));

    @Test
    void theTopicStageListsOpenTopicsAndIsSkippedForExactlyOne() {
        SuggestFrame two = frame(topic(1, "Rename", "implement", "open", List.of(), List.of()),
                topic(2, "Design", "design", "open", List.of(), List.of()),
                topic(3, "Old", "write", "dropped", List.of(), List.of()),
                topic(4, "Done", "verify", "done", List.of(), List.of()));
        StagedSuggestions.TopicStage stage = StagedSuggestions.topicStage(two);
        assertEquals(List.of(new StagedSuggestions.TopicChoice(1, "Rename", "implement", null),
                new StagedSuggestions.TopicChoice(2, "Design", "design", null)), stage.choices());
        assertFalse(stage.skip());

        StagedSuggestions.TopicStage one = StagedSuggestions.topicStage(
                frame(topic(1, "Rename", "implement", "open", List.of(), List.of()), topic(3, "Old", "write", "dropped", List.of(), List.of())));
        assertEquals(1, one.choices().size());
        assertTrue(one.skip());
    }

    @Test
    void theStepStageIsInSeqOrderAndSkippedWhenThereAreNoSteps() {
        SuggestFrame f = frame(topic(1, "Rename", "implement", "open", TWO_STEPS, List.of()),
                topic(2, "Design", "design", "open", List.of(), List.of()));
        StagedSuggestions.StepStage stage = StagedSuggestions.stepStage(f, 1);
        assertEquals(List.of(new StagedSuggestions.StepChoice(1, "Move classes", "done"),
                new StagedSuggestions.StepChoice(2, "Fix tests", "not-started")), stage.choices());
        assertFalse(stage.skip());
        assertTrue(StagedSuggestions.stepStage(f, 2).skip());
        assertTrue(StagedSuggestions.stepStage(f, 2).choices().isEmpty());
        assertTrue(StagedSuggestions.stepStage(f, 99).skip());
    }

    private static List<String> ids(List<StagedSuggestions.Question> qs) {
        return qs.stream().map(StagedSuggestions.Question::questionId).toList();
    }

    @Test
    void questionsAreCommonPlusTheTopicsTypeAndFilledIn() {
        SuggestFrame f = frame(topic(1, "Rename", "implement", "open", TWO_STEPS,
                List.of(new SuggestFrame.Term("epoch", 1), new SuggestFrame.Term("squad", 2))));
        List<StagedSuggestions.Question> qs = StagedSuggestions.questionStage(f, 1, 2, SuggestFixtures.questions());
        assertEquals(List.of("common-1", "common-1", "common-2", "common-3", "common-4", "implement-1", "implement-2"), ids(qs));
        assertEquals("What does \"epoch\" mean?", qs.get(0).text());
        assertEquals("What does \"squad\" mean?", qs.get(1).text());
        assertEquals("Your earlier explanation was not enough. Explain \"Rename\" again, starting from the background.", qs.get(2).text());
        assertEquals("How far has \"Fix tests\" progressed?", qs.get(3).text());
        assertEquals("What is still undecided before \"Fix tests\" can be finished?", qs.get(4).text());
        assertEquals("Which files will \"Fix tests\" change, and what is the smallest change that works?", qs.get(5).text());
    }

    @Test
    void withNoStepChosenTheQuestionsWithAStepAreLeftOutAndWithNoTermsTheTermQuestionIs() {
        SuggestFrame f = frame(topic(1, "Rename", "implement", "open", TWO_STEPS, List.of()));
        assertEquals(List.of("common-2"), ids(StagedSuggestions.questionStage(f, 1, null, SuggestFixtures.questions())));
        SuggestFrame g = frame(topic(1, "Check", "verify", "open", TWO_STEPS, List.of()));
        assertEquals(List.of("common-2", "common-3", "common-4", "verify-1", "verify-2"),
                ids(StagedSuggestions.questionStage(g, 1, 1, SuggestFixtures.questions())));
        assertEquals(List.of("common-2", "verify-2"),
                ids(StagedSuggestions.questionStage(g, 1, null, SuggestFixtures.questions())));
    }

    @Test
    void aStepSeqThatDoesNotExistCountsAsNoStepAndAnUnknownTopicGivesNothing() {
        SuggestFrame f = frame(topic(1, "Rename", "implement", "open", TWO_STEPS, List.of()));
        assertEquals(List.of("common-2"), ids(StagedSuggestions.questionStage(f, 1, 9, SuggestFixtures.questions())));
        assertEquals(List.of(), StagedSuggestions.questionStage(f, 5, 1, SuggestFixtures.questions()));
    }

    @Test
    void aTitleThatLooksLikeAPlaceholderIsNotFilledInAgain() {
        SuggestFrame f = frame(topic(1, "Handle {step} and $1", "write", "open",
                List.of(new SuggestFrame.Step(1, "Draft", "not-started", "")), List.of()));
        List<StagedSuggestions.Question> qs = StagedSuggestions.questionStage(f, 1, 1, SuggestFixtures.questions());
        assertEquals("Your earlier explanation was not enough. Explain \"Handle {step} and $1\" again, starting from the background.",
                qs.get(0).text());
        assertEquals("How far has \"Draft\" progressed?", qs.get(1).text());
    }

    @Test
    void theSixteenQuestionsHaveTheIdsOfTheDesign() {
        List<String> ids = SuggestFixtures.questions().stream().map(SuggestQuestion::questionId).toList();
        assertEquals(List.of("common-1", "common-2", "common-3", "common-4", "investigate-1", "investigate-2",
                "design-1", "design-2", "implement-1", "implement-2", "verify-1", "verify-2", "operate-1", "operate-2",
                "write-1", "write-2"), ids);
    }
}
