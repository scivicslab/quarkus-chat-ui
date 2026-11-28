package com.scivicslab.chatui.suggestprompt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The request {@link SuggestPatchPrompt} builds and the way it reads the answer. */
class SuggestPatchPromptTest {

    private static SuggestFrame frame(int digested) {
        return new SuggestFrame("project1/chat-01", 1, "standalone", "Rebuilding the connector.", digested, 2, 5L,
                List.of(new SuggestFrame.Topic(1, "Rename", "", "implement", null, "open", 2,
                        List.of(new SuggestFrame.Step(1, "Move classes", "done", "")), List.of())));
    }

    private static SuggestPatchPrompt.Message m(int turn, boolean person, String text) {
        return new SuggestPatchPrompt.Message(turn, person, text);
    }

    // ---- the request ----

    @Test
    void theRequestHoldsTheFrameJsonTheMessagesTheVocabulariesTheShapeAndTheLimits() {
        SuggestPatchPrompt.Built b = SuggestPatchPrompt.build(frame(2),
                List.of(m(3, true, "Please add the queue tools."), m(3, false, "Done, three tools added."),
                        m(4, true, "Now verify them.")), 4);
        System.out.println(b.prompt());
        System.out.println("digestedUpTo=" + b.digestedUpTo());
        assertTrue(b.needsModel());
        assertEquals(4, b.digestedUpTo());
        String p = b.prompt();
        assertTrue(p.contains(SuggestDigest.of(frame(2))), "the current frame JSON");
        assertTrue(p.contains("[turn 3]\nPERSON: Please add the queue tools.\n\nASSISTANT: Done, three tools added."));
        assertTrue(p.contains("[turn 4]\nPERSON: Now verify them."));
        assertTrue(p.indexOf("[turn 3]") < p.indexOf("[turn 4]"), "oldest first");
        for (String word : new String[]{"investigate, design, implement, verify, operate, write", "open, done, dropped",
                "not-started, in-progress, blocked, done", "\"topic_id\"", "\"work_type\"", "\"progress\"", "\"term\"",
                "overview 300 characters", "title 80", "summary 300", "note 200", "term 40", "12 open topics",
                "12 steps and 20 terms per topic", "set its status to \"dropped\"", "reply {}"}) {
            assertTrue(p.contains(word), word);
        }
        assertTrue(p.indexOf("Current work frame:") < p.indexOf("New messages:"));
    }

    @Test
    void messagesAlreadyDigestedOrNotYetFinishedOrBlankAreNotRead() {
        SuggestPatchPrompt.Built b = SuggestPatchPrompt.build(frame(2),
                List.of(m(2, true, "OLD-ONE"), m(3, true, "kept"), m(3, false, "   "), m(5, true, "RUNNING-ONE")), 4);
        System.out.println(b.prompt());
        assertTrue(b.prompt().contains("kept"));
        assertFalse(b.prompt().contains("OLD-ONE"));
        assertFalse(b.prompt().contains("RUNNING-ONE"));
        assertEquals(4, b.digestedUpTo(), "turn 4 had nothing to read but is finished, so it is passed");
    }

    @Test
    void whenNothingIsLeftToReadNoModelIsNeededAndTheTurnStillAdvances() {
        SuggestPatchPrompt.Built none = SuggestPatchPrompt.build(frame(2), List.of(), 4);
        assertFalse(none.needsModel());
        assertEquals("", none.prompt());
        assertEquals(4, none.digestedUpTo());
        SuggestPatchPrompt.Built stale = SuggestPatchPrompt.build(frame(4), List.of(m(3, true, "x")), 4);
        assertFalse(stale.needsModel());
        assertEquals(4, stale.digestedUpTo());
    }

    @Test
    void oneLongMessageIsCutToThreeThousandCharacters() {
        SuggestPatchPrompt.Built b = SuggestPatchPrompt.build(frame(0),
                List.of(m(1, true, "a".repeat(2999) + "bc" + "z".repeat(500))), 1);
        String p = b.prompt();
        int at = p.indexOf("PERSON: ");
        String text = p.substring(at + 8, p.indexOf("\n\n", at));
        System.out.println("length of the message as sent: " + text.length());
        assertEquals(3001, text.length(), "3000 characters and the mark");
        assertTrue(text.endsWith("ab…"), text.substring(2990));
    }

    @Test
    void theInputStopsAtTwelveThousandCharactersTakingWholeTurnsAndTheTurnTellsWhereItStopped() {
        List<SuggestPatchPrompt.Message> messages = new ArrayList<>();
        for (int turn = 1; turn <= 6; turn++) {
            messages.add(m(turn, true, ("t" + turn + " ").repeat(1000).substring(0, 2900)));
            messages.add(m(turn, false, ("a" + turn + " ").repeat(1000).substring(0, 2900)));
        }
        SuggestPatchPrompt.Built b = SuggestPatchPrompt.build(frame(0), messages, 6);
        String body = b.prompt().substring(b.prompt().indexOf("New messages:\n") + 14);
        System.out.println("body length " + body.length() + ", digestedUpTo " + b.digestedUpTo());
        assertTrue(body.length() <= SuggestPatchPrompt.MAX_INPUT_CHARS);
        assertEquals(2, b.digestedUpTo(), "turns 1 and 2 fit (about 5.8k each), turn 3 would not");
        assertTrue(body.contains("[turn 2]"));
        assertFalse(body.contains("[turn 3]"));
    }

    @Test
    void aSingleTurnBiggerThanTheLimitIsCutButStillMovesForward() {
        List<SuggestPatchPrompt.Message> messages = new ArrayList<>();
        for (int k = 0; k < 6; k++) messages.add(m(7, k % 2 == 0, "x".repeat(2900)));
        messages.add(m(8, true, "next"));
        SuggestPatchPrompt.Built b = SuggestPatchPrompt.build(frame(6), messages, 8);
        String body = b.prompt().substring(b.prompt().indexOf("New messages:\n") + 14);
        System.out.println("body length " + body.length() + ", digestedUpTo " + b.digestedUpTo());
        assertEquals(SuggestPatchPrompt.MAX_INPUT_CHARS, body.length());
        assertEquals(7, b.digestedUpTo());
        assertFalse(body.contains("next"));
    }

    // ---- the answer ----

    private static final String PATCH = "{\"overview\":\"We rebuild it.\",\"topics\":[{\"topic_id\":1,\"status\":\"done\"},"
            + "{\"title\":\"New topic\",\"work_type\":\"verify\"}],\"steps\":[{\"topic_id\":1,\"seq\":1,"
            + "\"progress\":\"done\",\"note\":\"ok\"}],\"terms\":[{\"topic_id\":1,\"term\":\"epoch\"}]}";

    @Test
    void aPlainObjectIsReadIntoAPatch() {
        SuggestPatchPrompt.Parsed r = SuggestPatchPrompt.parse(PATCH);
        System.out.println(r);
        SuggestPatch p = r.patch().orElseThrow();
        assertEquals("We rebuild it.", p.overview());
        assertEquals(2, p.topics().size());
        assertEquals(new SuggestPatch.TopicPatch(1, null, null, null, "done"), p.topics().get(0));
        assertEquals(new SuggestPatch.TopicPatch(null, "New topic", null, "verify", null), p.topics().get(1));
        assertEquals(new SuggestPatch.StepPatch(1, 1, null, "done", "ok"), p.steps().get(0));
        assertEquals(new SuggestPatch.TermPatch(1, "epoch"), p.terms().get(0));
        assertEquals("", r.reason());
        assertTrue(r.dropped().isEmpty());
    }

    @Test
    void aCodeFenceAndSentencesAroundTheObjectAreTolerated() {
        for (String reply : new String[]{"```json\n" + PATCH + "\n```", "Here is the patch:\n" + PATCH + "\nHope it helps.",
                "Note {not json} first. " + PATCH + " Done {again}."}) {
            SuggestPatchPrompt.Parsed r = SuggestPatchPrompt.parse(reply);
            System.out.println(reply.substring(0, 20) + " -> " + r.patch().isPresent() + " " + r.reason());
            assertEquals("We rebuild it.", r.patch().orElseThrow().overview(), reply);
        }
    }

    @Test
    void anEmptyObjectIsAPatchThatChangesNothing() {
        SuggestPatch p = SuggestPatchPrompt.parse("{}").patch().orElseThrow();
        assertEquals(SuggestPatch.empty(), p);
    }

    @Test
    void whatCannotBeReadGivesNoPatchAndTheReason() {
        for (String reply : new String[]{null, "", "  ", "no json at all", "{\"topics\": [", "[1,2,3]", "{broken: 1}"}) {
            SuggestPatchPrompt.Parsed r = SuggestPatchPrompt.parse(reply);
            System.out.println("[" + reply + "] -> " + r.reason());
            assertTrue(r.patch().isEmpty(), String.valueOf(reply));
            assertFalse(r.reason().isEmpty(), String.valueOf(reply));
        }
    }

    @Test
    void anElementOfTheWrongTypeIsDroppedAndReportedWhileTheRestIsKept() {
        SuggestPatchPrompt.Parsed r = SuggestPatchPrompt.parse("{\"overview\":5,\"topics\":[{\"topic_id\":\"1\",\"status\":\"done\"},"
                + "\"text\",{\"title\":\"ok\",\"work_type\":\"write\"}],\"steps\":{\"seq\":1},"
                + "\"terms\":[{\"topic_id\":1,\"term\":7},{\"topic_id\":1,\"term\":\"fine\"}]}");
        System.out.println(r);
        SuggestPatch p = r.patch().orElseThrow();
        assertEquals(null, p.overview());
        assertEquals(List.of(new SuggestPatch.TopicPatch(null, "ok", null, "write", null)), p.topics());
        assertEquals(List.of(new SuggestPatch.TermPatch(1, "fine")), p.terms());
        assertTrue(p.steps().isEmpty());
        List<String> names = r.dropped().stream().map(SuggestFrame.Rejected::element).toList();
        assertEquals(List.of("overview", "topics[0]", "topics[1]", "steps", "terms[0]"), names);
        assertEquals("topic_id is not an integer", r.dropped().get(1).reason());
    }

    @Test
    void aVocabularyViolationIsNotCheckedHereButByApply() {
        SuggestPatch p = SuggestPatchPrompt.parse("{\"topics\":[{\"title\":\"x\",\"work_type\":\"dance\"}]}")
                .patch().orElseThrow();
        SuggestFrame.Applied a = frame(0).apply(p, 1, 9L);
        System.out.println(a.rejected());
        assertEquals(1, a.rejected().size());
        assertEquals(1, a.frame().topics().size(), "the existing topic only");
    }
}
