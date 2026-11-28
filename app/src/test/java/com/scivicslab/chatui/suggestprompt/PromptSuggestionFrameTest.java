package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.suggestprompt.PromptSuggestion.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The last suggestion of the big-picture entry: the chosen topic, step and fixed question must be in the prompt. */
class PromptSuggestionFrameTest {

    private static final SuggestFrame.Topic TOPIC = new SuggestFrame.Topic(1, "Rebuild the connector", "", "implement",
            null, "open", 1, List.of(), List.of());
    private static final SuggestFrame.Step STEP = new SuggestFrame.Step(2, "Wire the actor", "in-progress", "");
    private static final List<Message> RECENT = List.of(new Message(true, "Please go on."), new Message(false, "Done so far."));

    @Test
    void theTopicStepAndQuestionAreInThePrompt() {
        String p = PromptSuggestion.followupPromptForFrame(RECENT, TOPIC, STEP, "How far has \"Wire the actor\" progressed?");

        assertTrue(p.contains("Topic: Rebuild the connector (implement)"));
        assertTrue(p.contains("Step: 2 Wire the actor [in-progress]"));
        assertTrue(p.contains("How far has \"Wire the actor\" progressed?"));
        assertTrue(p.contains("different instructions the person is likely to give next"));
        assertTrue(p.contains("PERSON: Please go on."));
        assertTrue(p.contains("ASSISTANT: Done so far."));
    }

    @Test
    void noStepMeansNoStepLine() {
        String p = PromptSuggestion.followupPromptForFrame(RECENT, TOPIC, null, "What is still undecided?");

        assertFalse(p.contains("Step:"));
        assertTrue(p.contains("What is still undecided?"));
    }
}
