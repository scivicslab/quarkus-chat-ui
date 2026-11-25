package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The window of a model whose server reports none comes from a fixed table kept in the settings. */
class WindowLimitsTest {

    @Test
    void theDefaultTableKnowsTheClaudeFamiliesAndGlm() {
        WindowLimits limits = WindowLimits.of(null);
        assertEquals(1_000_000, limits.windowOf("sonnet"), "what CLI 2.1.331 reported for the alias on 2026-11-25");
        assertEquals(1_000_000, limits.windowOf("claude-opus-4"));
        assertEquals(1_000_000, limits.windowOf("haiku"));
        assertEquals(262_144, limits.windowOf("GLM-5.3-Flash-Uncensored"));
        assertEquals(-1, limits.windowOf("gpt-5"), "a model of the table's names only; none is guessed");
        assertEquals(-1, limits.windowOf(null));
    }

    @Test
    void theSettingAddsToTheTableAndReplacesAnEntryOfTheSameName() {
        WindowLimits limits = WindowLimits.of("gpt-5=400000, sonnet=200000, bad=x, =5");
        assertEquals(400_000, limits.windowOf("gpt-5-codex"));
        assertEquals(200_000, limits.windowOf("sonnet"));
        assertEquals(1_000_000, limits.windowOf("haiku"));
    }

    @Test
    void theLongestMatchingNameWins() {
        assertEquals(500_000, WindowLimits.of("glm-5.3-flash=500000").windowOf("glm-5.3-flash-x"));
        assertEquals(262_144, WindowLimits.of("glm-5.3-flash=500000").windowOf("glm-5.3-pro"));
    }
}
