package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CliConfigTest {

    @Test
    @DisplayName("defaults() creates config with given model and null/false/zero for other fields")
    void defaults_createsConfigWithCorrectModelAndNulls() {
        CliConfig config = CliConfig.defaults("claude-sonnet-4-5");

        assertEquals("claude-sonnet-4-5", config.model());
        assertNull(config.systemPrompt());
        assertEquals(0, config.maxTurns());
        assertNull(config.workingDir());
        assertNull(config.sessionId());
        assertFalse(config.continueSession());
        assertNull(config.allowedTools());
    }

    @Test
    @DisplayName("withModel() returns new instance with updated model")
    void withModel_returnsNewInstanceWithUpdatedModel() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withModel("claude-opus-4");

        assertEquals("claude-opus-4", updated.model());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withSessionId() returns new instance with updated sessionId")
    void withSessionId_returnsNewInstanceWithUpdatedSessionId() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withSessionId("session-abc-123");

        assertEquals("session-abc-123", updated.sessionId());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withContinueSession() sets continueSession flag to true")
    void withContinueSession_setsFlagToTrue() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withContinueSession();

        assertTrue(updated.continueSession());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withMaxTurns() returns new instance with updated maxTurns")
    void withMaxTurns_returnsNewInstanceWithUpdatedMaxTurns() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withMaxTurns(10);

        assertEquals(10, updated.maxTurns());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withAllowedTools() returns new instance with specified tools")
    void withAllowedTools_returnsNewInstanceWithTools() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withAllowedTools("Bash", "Read", "Write");

        assertNotNull(updated.allowedTools());
        assertArrayEquals(new String[]{"Bash", "Read", "Write"}, updated.allowedTools());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("chained builder calls produce correct final configuration")
    void chainedBuilderCalls_produceCorrectConfig() {
        CliConfig config = CliConfig.defaults("claude-sonnet-4-5")
            .withModel("claude-opus-4")
            .withSessionId("s-999")
            .withContinueSession()
            .withMaxTurns(5)
            .withAllowedTools("Bash", "Edit");

        assertEquals("claude-opus-4", config.model());
        assertEquals("s-999", config.sessionId());
        assertTrue(config.continueSession());
        assertEquals(5, config.maxTurns());
        assertArrayEquals(new String[]{"Bash", "Edit"}, config.allowedTools());
    }

    @Test
    @DisplayName("original config is NOT mutated by with* methods (immutability)")
    void withMethods_doNotMutateOriginal() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");

        original.withModel("claude-opus-4");
        original.withSessionId("s-999");
        original.withContinueSession();
        original.withMaxTurns(10);
        original.withAllowedTools("Bash");

        // Original must remain unchanged
        assertEquals("claude-sonnet-4-5", original.model());
        assertNull(original.sessionId());
        assertFalse(original.continueSession());
        assertEquals(0, original.maxTurns());
        assertNull(original.allowedTools());
    }
}
