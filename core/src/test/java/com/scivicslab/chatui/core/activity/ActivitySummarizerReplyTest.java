package com.scivicslab.chatui.core.activity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit test for reading the broker's two answers.
 *
 * <p>Exercises the load-bearing path: an unreadable or empty answer must come back as "no answer"
 * rather than as a summary, because the caller draws whatever it is given on a dashboard.</p>
 */
class ActivitySummarizerReplyTest {

    @Test
    void firstChoiceContent_readsTheAssistantText() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"検索フォームの脆弱性診断。"}}]}""";

        assertEquals("検索フォームの脆弱性診断。", ActivitySummarizer.firstChoiceContent(body));
    }

    @Test
    void firstChoiceContent_ofAReasoningOnlyReply_isEmpty() {
        // A reasoning model that spends the whole token limit thinking answers with an empty
        // content and a full reasoning_content. That is not a summary.
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"We need to..."}}]}""";

        assertEquals("", ActivitySummarizer.firstChoiceContent(body));
    }

    @Test
    void firstChoiceContent_ofSomethingElse_isNull() {
        assertNull(ActivitySummarizer.firstChoiceContent("{\"error\":\"model not found\"}"));
        assertNull(ActivitySummarizer.firstChoiceContent("<html>502 Bad Gateway</html>"));
    }

    @Test
    void firstModelId_readsTheFirstEntry() {
        String body = """
                {"object":"list","data":[{"id":"Qwen/Qwen3.8-27B"},{"id":"google/gemma-4-26B-A4B-it"}]}""";

        assertEquals("Qwen/Qwen3.8-27B", ActivitySummarizer.firstModelId(body));
    }

    @Test
    void firstModelId_ofAnEmptyList_isNull() {
        assertNull(ActivitySummarizer.firstModelId("{\"object\":\"list\",\"data\":[]}"));
    }
}
