package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.suggestprompt.PromptSuggestion.Choice;
import com.scivicslab.chatui.suggestprompt.PromptSuggestion.Message;
import com.scivicslab.chatui.suggestprompt.PromptSuggestion.Question;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PromptSuggestionTest {

    private static String longPrompt(int n) {
        return "Go through option number " + n + " step by step, explain the reason for each change, and show the result as a table.";
    }

    // ---- parseQuestion -------------------------------------------------------------------

    @Test
    void questionWithChoices() {
        String reply = "{\"question\":\"Which design should I use?\",\"choices\":["
                + "{\"id\":\"A\",\"text\":\"Keep one actor\"},{\"id\":\"B\",\"text\":\"Split into two actors\"}]}";
        Question q = PromptSuggestion.parseQuestion(reply).orElseThrow();
        assertEquals("Which design should I use?", q.question());
        assertEquals(List.of(new Choice("A", "Keep one actor"), new Choice("B", "Split into two actors")), q.choices());
    }

    @Test
    void questionWithoutChoices() {
        Question q = PromptSuggestion.parseQuestion("{\"question\":\"Shall I continue?\",\"choices\":[]}").orElseThrow();
        assertEquals("Shall I continue?", q.question());
        assertTrue(q.choices().isEmpty());
    }

    @Test
    void missingChoicesFieldIsNoChoices() {
        assertTrue(PromptSuggestion.parseQuestion("{\"question\":\"Why?\"}").orElseThrow().choices().isEmpty());
    }

    @Test
    void aSingleChoiceIsNotAChoice() {
        String reply = "{\"question\":\"Proceed?\",\"choices\":[{\"id\":\"A\",\"text\":\"Proceed\"}]}";
        assertTrue(PromptSuggestion.parseQuestion(reply).orElseThrow().choices().isEmpty());
    }

    @Test
    void questionInsideFenceAndSentence() {
        String reply = "Here you go:\n```json\n{\"question\":\"Which one?\",\"choices\":[\"First\",\"Second\",\"Third\"]}\n```\nDone.";
        Question q = PromptSuggestion.parseQuestion(reply).orElseThrow();
        assertEquals(List.of(new Choice("A", "First"), new Choice("B", "Second"), new Choice("C", "Third")), q.choices());
    }

    @Test
    void duplicateAndEmptyChoicesAreDropped() {
        String reply = "{\"question\":\"Which?\",\"choices\":[{\"id\":\"A\",\"text\":\"x\"},{\"id\":\"A\",\"text\":\"y\"},"
                + "{\"id\":\"B\",\"text\":\"\"},{\"id\":\"C\",\"text\":\"z\"}]}";
        assertEquals(List.of(new Choice("A", "x"), new Choice("C", "z")),
                PromptSuggestion.parseQuestion(reply).orElseThrow().choices());
    }

    @Test
    void choicesAreCapped() {
        StringBuilder sb = new StringBuilder("{\"question\":\"Which?\",\"choices\":[");
        for (int i = 0; i < 12; i++) sb.append(i == 0 ? "" : ",").append("{\"id\":\"C").append(i).append("\",\"text\":\"t").append(i).append("\"}");
        sb.append("]}");
        assertEquals(PromptSuggestion.MAX_CHOICES, PromptSuggestion.parseQuestion(sb.toString()).orElseThrow().choices().size());
    }

    @Test
    void unreadableQuestions() {
        assertEquals(Optional.empty(), PromptSuggestion.parseQuestion(null));
        assertEquals(Optional.empty(), PromptSuggestion.parseQuestion("I cannot tell."));
        assertEquals(Optional.empty(), PromptSuggestion.parseQuestion("{\"question\": "));
        assertEquals(Optional.empty(), PromptSuggestion.parseQuestion("{\"question\":\"  \",\"choices\":[]}"));
        assertEquals(Optional.empty(), PromptSuggestion.parseQuestion("{\"choices\":[]}"));
        assertEquals(Optional.empty(), PromptSuggestion.parseQuestion("[\"a\"]"));
    }

    // ---- parseFollowups ------------------------------------------------------------------

    @Test
    void fourToSixFollowupsAreRead() {
        for (int n = 4; n <= 6; n++) {
            List<String> in = new ArrayList<>();
            StringBuilder json = new StringBuilder("[");
            for (int i = 1; i <= n; i++) {
                in.add(longPrompt(i));
                json.append(i == 1 ? "" : ",").append('"').append(longPrompt(i)).append('"');
            }
            assertEquals(in, PromptSuggestion.parseFollowups(json.append("]").toString()));
        }
    }

    @Test
    void moreThanSixAreCappedAtSix() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 9; i++) json.append(i == 1 ? "" : ",").append('"').append(longPrompt(i)).append('"');
        List<String> out = PromptSuggestion.parseFollowups(json.append("]").toString());
        assertEquals(PromptSuggestion.MAX_COUNT, out.size());
        assertEquals(longPrompt(1), out.get(0));
    }

    @Test
    void followupsInsideFenceAndSentence() {
        String reply = "Sure!\n```json\n[\"" + longPrompt(1) + "\", \"" + longPrompt(2) + "\"]\n```";
        assertEquals(List.of(longPrompt(1), longPrompt(2)), PromptSuggestion.parseFollowups(reply));
    }

    @Test
    void yesNoOnlyAndShortPromptsAreDropped() {
        String reply = "[\"Yes.\", \"No\", \"はい\", \"お願いします。\", \"OK, go ahead!\", \"Do it\", \"" + longPrompt(1) + "\"]";
        assertEquals(List.of(longPrompt(1)), PromptSuggestion.parseFollowups(reply));
    }

    @Test
    void aLongPromptThatStartsWithYesIsKept() {
        String p = "Yes, and also add a unit test for the empty case, because the parser used to fail on it.";
        assertEquals(List.of(p), PromptSuggestion.parseFollowups("[\"" + p + "\"]"));
    }

    @Test
    void tooLongNonTextAndDuplicatePromptsAreDropped() {
        String tooLong = "x".repeat(PromptSuggestion.MAX_PROMPT_CHARS + 1);
        String reply = "[\"" + tooLong + "\", 42, null, {\"a\":1}, \"" + longPrompt(1) + "\", \"  " + longPrompt(1) + "  \"]";
        assertEquals(List.of(longPrompt(1)), PromptSuggestion.parseFollowups(reply));
    }

    @Test
    void unreadableFollowups() {
        assertEquals(List.of(), PromptSuggestion.parseFollowups(null));
        assertEquals(List.of(), PromptSuggestion.parseFollowups(""));
        assertEquals(List.of(), PromptSuggestion.parseFollowups("Sorry, I cannot do that."));
        assertEquals(List.of(), PromptSuggestion.parseFollowups("[\"unterminated"));
        assertEquals(List.of(), PromptSuggestion.parseFollowups("{\"prompts\": 1}"));
    }

    // ---- yes/no, head ----------------------------------------------------------------------

    @Test
    void yesNoDetection() {
        for (String s : List.of("yes", "Yes!", " No. ", "OK", "Please do", "go ahead", "はい", "いいえ。", "お願いします", "了解です"))
            assertTrue(PromptSuggestion.isYesNoOnly(s), s);
        for (String s : List.of("yes, and add tests", "No, use B instead", "はい、テストも追加してください"))
            assertFalse(PromptSuggestion.isYesNoOnly(s), s);
        assertTrue(PromptSuggestion.isYesNoOnly(null));
    }

    @Test
    void headNamesTheChosenOption() {
        assertEquals("Go with B. ", PromptSuggestion.headFor(new Choice("B", "Split")));
        assertEquals("", PromptSuggestion.headFor(null));
    }

    // ---- prompts and history -----------------------------------------------------------------

    @Test
    void lastAssistantTextIsTheNewestAssistantMessage() {
        List<Message> h = List.of(new Message(true, "q1"), new Message(false, "a1"), new Message(true, "q2"),
                new Message(false, "a2"), new Message(true, "q3"));
        assertEquals("a2", PromptSuggestion.lastAssistantText(h));
        assertNull(PromptSuggestion.lastAssistantText(List.of(new Message(true, "q"))));
        assertNull(PromptSuggestion.lastAssistantText(List.of()));
    }

    @Test
    void questionPromptCarriesTheMessageAndTheFormat() {
        String p = PromptSuggestion.questionPromptFor("Option A or option B?");
        assertTrue(p.contains("Option A or option B?"));
        assertTrue(p.contains("\"choices\""));
        assertTrue(p.startsWith("Below is the last message an AI assistant wrote to a person."));
    }

    @Test
    void questionPromptKeepsTheEndOfALongMessage() {
        String p = PromptSuggestion.questionPromptFor("start-" + "x".repeat(PromptSuggestion.MAX_MESSAGE_CHARS + 500) + "-the-end");
        assertTrue(p.contains("-the-end"));
        assertFalse(p.contains("start-"));
    }

    @Test
    void followupPromptStatesTheDecisionAndShowsOnlyRecentMessages() {
        List<Message> h = new ArrayList<>();
        for (int i = 1; i <= 10; i++) h.add(new Message(i % 2 == 1, "message-" + i));
        Question q = new Question("Which design?", List.of(new Choice("A", "One actor"), new Choice("B", "Two actors")));
        String p = PromptSuggestion.followupPromptFor(h, q, new Choice("B", "Two actors"));
        assertTrue(p.contains("Which design?"));
        assertTrue(p.contains("The person has decided: B — Two actors"));
        assertTrue(p.contains("different instructions the person is likely to give next"));
        assertTrue(p.contains("message-10"));
        assertTrue(p.contains("message-5"));
        assertFalse(p.contains("message-4\n"));
        assertTrue(p.contains("PERSON: message-9"));
        assertTrue(p.contains("ASSISTANT: message-10"));
    }

    @Test
    void followupPromptWithoutAChoiceStatesNoDecision() {
        String p = PromptSuggestion.followupPromptFor(List.of(new Message(false, "Shall I go on?")),
                new Question("Shall I go on?", List.of()), null);
        assertFalse(p.contains("has decided"));
        assertTrue(p.contains("Shall I go on?"));
    }
}
