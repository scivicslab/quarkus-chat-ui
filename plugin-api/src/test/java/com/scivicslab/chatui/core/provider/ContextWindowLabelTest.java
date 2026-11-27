package com.scivicslab.chatui.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Every place that shows a conversation's model also shows its context window, and the label comes from this one helper:
 * a {@code [200k]} suffix is 200K, a plain Claude model is the CLI's default 1M, and another provider says what its server
 * reported, or {@code ?} when it reported nothing.
 */
class ContextWindowLabelTest {

    @Test
    void theTwoHundredKSuffixIsTwoHundredK_whateverTheCase() {
        assertEquals("200K", ContextWindowLabel.of("claude", "sonnet[200k]", () -> -1));
        assertEquals("200K", ContextWindowLabel.of("claude", "claude-opus-5-5[200K]", () -> -1));
    }

    @Test
    void aPlainClaudeModelIsTheCliDefaultOneMillion() {
        assertEquals("1M", ContextWindowLabel.of("claude", "sonnet", () -> -1));
        assertEquals("1M", ContextWindowLabel.of("claude", "sonnet[1m]", () -> -1));
        assertEquals("1M", ContextWindowLabel.of("claude", "claude-sonnet-5-5", () -> -1));
    }

    @Test
    void anotherProviderShowsWhatItsServerReported() {
        assertEquals("131K", ContextWindowLabel.of("openai-compat", "gemma", () -> 131072));
        assertEquals("262K", ContextWindowLabel.of("openai-compat", "glm", () -> 262144));
        assertEquals("1M", ContextWindowLabel.of("openai-compat", "big", () -> 1_000_000));
        assertEquals("1.5M", ContextWindowLabel.of("openai-compat", "bigger", () -> 1_500_000));
        assertEquals("8K", ContextWindowLabel.of("openai-compat", "small", () -> 8192));
    }

    @Test
    void anUnknownWindowIsAQuestionMark() {
        assertEquals("?", ContextWindowLabel.of("openai-compat", "gemma", () -> -1));
        assertEquals("?", ContextWindowLabel.of("codex", "gpt", () -> 0));
        assertEquals("1M", ContextWindowLabel.of("claude", null, () -> -1), "no model chosen on Claude Code: the CLI default");
    }

    @Test
    void theServerIsAskedOnlyWhenTheModelStringDoesNotSayTheWindow() {
        AtomicBoolean asked = new AtomicBoolean();
        ContextWindowLabel.of("claude", "sonnet[200k]", () -> { asked.set(true); return 5; });
        ContextWindowLabel.of("claude", "sonnet", () -> { asked.set(true); return 5; });
        assertFalse(asked.get());
    }

    @Test
    void theLabelIsWrittenNextToTheModel() {
        assertEquals("sonnet · 1M", ContextWindowLabel.withModel("sonnet", "1M"));
        assertEquals("sonnet[200k] · 200K", ContextWindowLabel.withModel("sonnet[200k]", "200K"));
        assertEquals("(no model)", ContextWindowLabel.withModel(null, "1M"));
    }
}
