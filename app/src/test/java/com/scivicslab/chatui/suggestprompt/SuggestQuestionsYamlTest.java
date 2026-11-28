package com.scivicslab.chatui.suggestprompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The fixed questions loaded from the resource YAML ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}). */
class SuggestQuestionsYamlTest {

    @Test
    void theResourceHoldsTheSixteenFixedQuestionsWithTheirIdsInTableOrder() throws Exception {
        assertEquals(SuggestFixtures.questions(), SuggestQuestionsYaml.load());
    }

    @Test
    void anIdIsTheWorkTypeAndThePlaceInItsList() {
        var q = SuggestQuestionsYaml.parse("common:\n  - one\n  - two\ndesign:\n  - three\n");

        assertEquals(java.util.List.of("common-1", "common-2", "design-1"), q.stream().map(SuggestQuestion::questionId).toList());
        assertEquals("three", q.get(2).template());
    }

    @Test
    void aFileThatIsNotAMapOfListsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SuggestQuestionsYaml.parse("- a\n- b\n"));
        assertThrows(IllegalArgumentException.class, () -> SuggestQuestionsYaml.parse("common: text\n"));
        assertThrows(IllegalArgumentException.class, () -> SuggestQuestionsYaml.parse("common:\n  - ''\n"));
    }
}
