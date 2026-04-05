package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CliConfigTest {

    // --- defaults() ---

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
    @DisplayName("defaults() sets permissionMode to null (provider sets its own default)")
    void defaults_setsPermissionModeToNull() {
        CliConfig config = CliConfig.defaults("claude-sonnet-4-5");
        assertNull(config.permissionMode(),
            "CliConfig.defaults() must NOT set permissionMode. " +
            "Each provider (ClaudeLlmProvider, CodexLlmProvider) sets its own CLI-specific default.");
    }

    // --- withModel() ---

    @Test
    @DisplayName("withModel() returns new instance with updated model")
    void withModel_returnsNewInstanceWithUpdatedModel() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withModel("claude-opus-4");

        assertEquals("claude-opus-4", updated.model());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withModel() preserves permissionMode")
    void withModel_preservesPermissionMode() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5")
            .withPermissionMode("bypassPermissions");
        CliConfig updated = original.withModel("claude-opus-4");

        assertEquals("bypassPermissions", updated.permissionMode());
    }

    // --- withSessionId() ---

    @Test
    @DisplayName("withSessionId() returns new instance with updated sessionId")
    void withSessionId_returnsNewInstanceWithUpdatedSessionId() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withSessionId("session-abc-123");

        assertEquals("session-abc-123", updated.sessionId());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withSessionId() preserves permissionMode")
    void withSessionId_preservesPermissionMode() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5")
            .withPermissionMode("auto");
        CliConfig updated = original.withSessionId("s-1");

        assertEquals("auto", updated.permissionMode());
    }

    // --- withContinueSession() ---

    @Test
    @DisplayName("withContinueSession() sets continueSession flag to true")
    void withContinueSession_setsFlagToTrue() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withContinueSession();

        assertTrue(updated.continueSession());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withContinueSession() preserves permissionMode")
    void withContinueSession_preservesPermissionMode() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5")
            .withPermissionMode("bypassPermissions");
        CliConfig updated = original.withContinueSession();

        assertEquals("bypassPermissions", updated.permissionMode());
    }

    // --- withMaxTurns() ---

    @Test
    @DisplayName("withMaxTurns() returns new instance with updated maxTurns")
    void withMaxTurns_returnsNewInstanceWithUpdatedMaxTurns() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
        CliConfig updated = original.withMaxTurns(10);

        assertEquals(10, updated.maxTurns());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withMaxTurns() preserves permissionMode")
    void withMaxTurns_preservesPermissionMode() {
        CliConfig config = CliConfig.defaults("m").withPermissionMode("plan");
        assertEquals("plan", config.withMaxTurns(5).permissionMode());
    }

    // --- withAllowedTools() ---

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
    @DisplayName("withAllowedTools() preserves permissionMode")
    void withAllowedTools_preservesPermissionMode() {
        CliConfig config = CliConfig.defaults("m").withPermissionMode("auto");
        assertEquals("auto", config.withAllowedTools("Bash").permissionMode());
    }

    // --- withPermissionMode() ---

    @Nested
    @DisplayName("withPermissionMode()")
    class WithPermissionMode {

        @Test
        @DisplayName("returns new instance with updated permissionMode")
        void returnsNewInstance() {
            CliConfig original = CliConfig.defaults("claude-sonnet-4-5");
            CliConfig updated = original.withPermissionMode("bypassPermissions");

            assertEquals("bypassPermissions", updated.permissionMode());
            assertNotSame(original, updated);
        }

        @Test
        @DisplayName("preserves all other fields")
        void preservesOtherFields() {
            CliConfig original = CliConfig.defaults("claude-sonnet-4-5")
                .withSessionId("s-1")
                .withMaxTurns(5)
                .withAllowedTools("Bash");
            CliConfig updated = original.withPermissionMode("plan");

            assertEquals("claude-sonnet-4-5", updated.model());
            assertEquals("s-1", updated.sessionId());
            assertEquals(5, updated.maxTurns());
            assertArrayEquals(new String[]{"Bash"}, updated.allowedTools());
            assertEquals("plan", updated.permissionMode());
        }

        @Test
        @DisplayName("accepts null value")
        void acceptsNull() {
            CliConfig config = CliConfig.defaults("m").withPermissionMode(null);
            assertNull(config.permissionMode());
        }

        @Test
        @DisplayName("accepts all valid permission mode values")
        void acceptsAllValidValues() {
            for (String mode : new String[]{"default", "acceptEdits", "auto", "bypassPermissions", "plan"}) {
                CliConfig config = CliConfig.defaults("m").withPermissionMode(mode);
                assertEquals(mode, config.permissionMode());
            }
        }
    }

    // --- Chaining ---

    @Test
    @DisplayName("chained builder calls produce correct final configuration")
    void chainedBuilderCalls_produceCorrectConfig() {
        CliConfig config = CliConfig.defaults("claude-sonnet-4-5")
            .withModel("claude-opus-4")
            .withSessionId("s-999")
            .withContinueSession()
            .withMaxTurns(5)
            .withAllowedTools("Bash", "Edit")
            .withPermissionMode("bypassPermissions");

        assertEquals("claude-opus-4", config.model());
        assertEquals("s-999", config.sessionId());
        assertTrue(config.continueSession());
        assertEquals(5, config.maxTurns());
        assertArrayEquals(new String[]{"Bash", "Edit"}, config.allowedTools());
        assertEquals("bypassPermissions", config.permissionMode());
    }

    // --- Immutability ---

    @Test
    @DisplayName("original config is NOT mutated by with* methods (immutability)")
    void withMethods_doNotMutateOriginal() {
        CliConfig original = CliConfig.defaults("claude-sonnet-4-5");

        original.withModel("claude-opus-4");
        original.withSessionId("s-999");
        original.withContinueSession();
        original.withMaxTurns(10);
        original.withAllowedTools("Bash");
        original.withPermissionMode("bypassPermissions");

        // Original must remain unchanged
        assertEquals("claude-sonnet-4-5", original.model());
        assertNull(original.sessionId());
        assertFalse(original.continueSession());
        assertEquals(0, original.maxTurns());
        assertNull(original.allowedTools());
        assertNull(original.permissionMode());
    }

    // --- Record equality ---

    @Test
    @DisplayName("two configs with same values are equal")
    void equalConfigs_areEqual() {
        CliConfig a = CliConfig.defaults("m").withPermissionMode("auto");
        CliConfig b = CliConfig.defaults("m").withPermissionMode("auto");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("configs with different permissionMode are not equal")
    void differentPermissionMode_notEqual() {
        CliConfig a = CliConfig.defaults("m").withPermissionMode("auto");
        CliConfig b = CliConfig.defaults("m").withPermissionMode("plan");
        assertNotEquals(a, b);
    }
}
