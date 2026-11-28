package com.scivicslab.chatui.suggestprompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** What {@link SuggestDigest} puts in the JSON the local LLM is given. */
class SuggestDigestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SuggestFrame frame(String role, String delegate) {
        return new SuggestFrame("project1/chat-01", 1, role, "Rebuilding the connector.", 4, 2, 5L, List.of(
                new SuggestFrame.Topic(2, "Design", "", "design", delegate, "open", 4, List.of(), List.of()),
                new SuggestFrame.Topic(1, "Rename", "rename step", "implement", delegate, "open", 4,
                        List.of(new SuggestFrame.Step(1, "Move classes", "done", "n1"),
                                new SuggestFrame.Step(2, "Fix tests", "not-started", "")),
                        List.of(new SuggestFrame.Term("epoch", 3))),
                new SuggestFrame.Topic(3, "Old", "", "write", delegate, "dropped", 1, List.of(), List.of())));
    }

    @Test
    void theJsonHasTheKeysAndOrderOfTheDesignAndTopicsInIdOrderWithoutTheDroppedOne() {
        String json = SuggestDigest.of(frame("superior", "project1/chat-33"));
        assertEquals("{\"role\":\"superior\",\"overview\":\"Rebuilding the connector.\",\"topics\":["
                + "{\"topic_id\":1,\"title\":\"Rename\",\"summary\":\"rename step\",\"work_type\":\"implement\","
                + "\"status\":\"open\",\"delegate_squad\":\"project1/chat-33\","
                + "\"steps\":[{\"seq\":1,\"title\":\"Move classes\",\"progress\":\"done\",\"note\":\"n1\"},"
                + "{\"seq\":2,\"title\":\"Fix tests\",\"progress\":\"not-started\",\"note\":\"\"}],\"terms\":[\"epoch\"]},"
                + "{\"topic_id\":2,\"title\":\"Design\",\"summary\":\"\",\"work_type\":\"design\",\"status\":\"open\","
                + "\"delegate_squad\":\"project1/chat-33\",\"steps\":[],\"terms\":[]}]}", json);
    }

    @Test
    void delegateSquadIsWrittenOnlyForASuperiorThatHasOne() throws Exception {
        for (String role : new String[]{"subordinate", "standalone"}) {
            JsonNode root = JSON.readTree(SuggestDigest.of(frame(role, "project1/chat-33")));
            assertEquals(role, root.get("role").asText());
            assertFalse(root.get("topics").get(0).has("delegate_squad"), role);
        }
        JsonNode none = JSON.readTree(SuggestDigest.of(frame("superior", null)));
        assertFalse(none.get("topics").get(0).has("delegate_squad"), "a superior with no delegate set");
    }

    @Test
    void anEmptyFrameGivesNoTopicsAndQuotesAreEscaped() throws Exception {
        assertEquals("{\"role\":\"standalone\",\"overview\":\"\",\"topics\":[]}",
                SuggestDigest.of(SuggestFrame.empty("project1/chat-01", 1, "standalone")));
        List<SuggestFrame.Topic> topics = new ArrayList<>();
        topics.add(new SuggestFrame.Topic(1, "say \"hi\"\nnow", "", "write", null, "open", 1, List.of(), List.of()));
        JsonNode root = JSON.readTree(SuggestDigest.of(new SuggestFrame("s", 1, "standalone", "", 0, 1, 1L, topics)));
        assertEquals("say \"hi\"\nnow", root.get("topics").get(0).get("title").asText());
    }
}
