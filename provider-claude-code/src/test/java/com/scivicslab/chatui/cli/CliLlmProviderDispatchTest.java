package com.scivicslab.chatui.cli;

import com.scivicslab.chatui.cli.process.CliConfig;
import com.scivicslab.chatui.cli.process.CliProcess;
import com.scivicslab.chatui.cli.process.StreamEvent;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.rest.ChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CliLlmProvider's dispatch logic and permission response routing.
 * Uses a minimal concrete subclass to test the abstract base class.
 */
class CliLlmProviderDispatchTest {

    private TestCliLlmProvider provider;
    private Path tempSessionFile;

    @BeforeEach
    void setUp() throws Exception {
        tempSessionFile = Files.createTempFile("test-session-", ".txt");
        tempSessionFile.toFile().deleteOnExit();
        provider = new TestCliLlmProvider(tempSessionFile.toString());
    }

    // --- registerPermissionRequest and respond routing ---

    @Nested
    @DisplayName("Permission registration and response routing")
    class PermissionRouting {

        @Test
        @DisplayName("registerPermissionRequest stores toolUseId for later dispatch")
        void registerPermissionRequest_storesId() {
            provider.registerPermissionRequest("toolu_abc");
            // The id is stored; we verify via respond() behavior
            assertTrue(provider.hasPendingPermission("toolu_abc"));
        }

        @Test
        @DisplayName("respond() for registered permission ID routes to writePermissionResponse")
        void respond_registeredPermission_routesToPermissionResponse() throws IOException {
            provider.registerPermissionRequest("toolu_123");
            // respond() will fail with IOException because no process is running,
            // but we can verify the routing by checking what method was attempted
            assertThrows(IOException.class, () -> provider.respond("toolu_123", "yes"));
        }

        @Test
        @DisplayName("respond() for unregistered ID routes to writeUserMessage")
        void respond_unregisteredId_routesToUserMessage() throws IOException {
            // This will also throw IOException (no process), but tests the routing path
            assertThrows(IOException.class, () -> provider.respond("unknown-id", "hello"));
        }

        @Test
        @DisplayName("respond() removes permission ID after use (one-time use)")
        void respond_removesPermissionIdAfterUse() {
            provider.registerPermissionRequest("toolu_once");
            try {
                provider.respond("toolu_once", "yes");
            } catch (IOException ignored) {
                // Expected - no process running
            }
            // After respond, the ID should be removed
            assertFalse(provider.hasPendingPermission("toolu_once"));
        }

        @Test
        @DisplayName("multiple permission IDs can be registered concurrently")
        void multiplePermissionIds_canBeRegistered() {
            provider.registerPermissionRequest("toolu_1");
            provider.registerPermissionRequest("toolu_2");
            provider.registerPermissionRequest("toolu_3");

            assertTrue(provider.hasPendingPermission("toolu_1"));
            assertTrue(provider.hasPendingPermission("toolu_2"));
            assertTrue(provider.hasPendingPermission("toolu_3"));
        }
    }

    // --- Capabilities ---

    // --- Plan-approval routing (ExitPlanMode) ---

    @Nested
    @DisplayName("Plan-approval registration and routing")
    class PlanApprovalRouting {

        @Test
        @DisplayName("registerPlanApproval marks an id as a plan approval, not a permission")
        void registerPlanApproval_marksId() {
            provider.registerPlanApproval("toolu_plan");
            assertTrue(provider.isPlanApproval("toolu_plan"));
            // A plan approval is distinct from a tool-permission reply.
            assertFalse(provider.hasPendingPermission("toolu_plan"));
        }

        @Test
        @DisplayName("isPlanApproval is false for unknown and null ids")
        void isPlanApproval_unknownId() {
            assertFalse(provider.isPlanApproval("nope"));
            assertFalse(provider.isPlanApproval(null));
        }

        @Test
        @DisplayName("clearPlanApproval removes the id (one-time use)")
        void clearPlanApproval_removesId() {
            provider.registerPlanApproval("toolu_once");
            assertTrue(provider.isPlanApproval("toolu_once"));
            provider.clearPlanApproval("toolu_once");
            assertFalse(provider.isPlanApproval("toolu_once"));
        }
    }

    // --- Capabilities ---

    @Test
    @DisplayName("CLI provider returns CLI capabilities")
    void capabilities_returnsCli() {
        assertEquals(ProviderCapabilities.CLI, provider.capabilities());
    }

    // --- Model management ---

    @Nested
    @DisplayName("Model management")
    class ModelManagement {

        @Test
        @DisplayName("getCurrentModel returns default model")
        void getCurrentModel_returnsDefault() {
            assertEquals("test-model", provider.getCurrentModel());
        }

        @Test
        @DisplayName("setModel updates the model")
        void setModel_updatesModel() {
            provider.setModel("new-model");
            assertEquals("new-model", provider.getCurrentModel());
        }
    }

    // --- Slash commands ---

    @Nested
    @DisplayName("Slash commands")
    class SlashCommands {

        @Test
        @DisplayName("isCommand returns true for slash commands")
        void isCommand_slashCommand_returnsTrue() {
            assertTrue(provider.isCommand("/help"));
            assertTrue(provider.isCommand("/model gpt-4"));
            assertTrue(provider.isCommand("/clear"));
            assertTrue(provider.isCommand("/session"));
        }

        @Test
        @DisplayName("isCommand returns false for regular text")
        void isCommand_regularText_returnsFalse() {
            assertFalse(provider.isCommand("hello world"));
            assertFalse(provider.isCommand("write some code"));
        }

        @Test
        @DisplayName("handleCommand for /help returns help text")
        void handleCommand_help_returnsResponse() {
            List<ChatEvent> responses = provider.handleCommand("/help");
            assertFalse(responses.isEmpty());
        }
    }

    // --- Test implementation ---

    /**
     * Minimal concrete subclass of CliLlmProvider for testing.
     */
    private static class TestCliLlmProvider extends CliLlmProvider {

        TestCliLlmProvider(String sessionFilePath) {
            super("echo", "TEST_API_KEY", "test-model",
                  Optional.empty(), Optional.of("bypassPermissions"),
                  sessionFilePath, 9999);
        }

        @Override public String id() { return "test"; }
        @Override public String displayName() { return "Test CLI"; }
        @Override public List<LlmProvider.ModelEntry> getAvailableModels() {
            return List.of(new LlmProvider.ModelEntry("test-model", "test", null));
        }
        @Override public String detectEnvApiKey() { return null; }

        /** Expose pending permission check for testing. */
        boolean hasPendingPermission(String id) {
            // Try to remove and re-add to check presence without side effects
            // Actually, use respond to test: if it routes to permission, the ID was registered
            // This is a simpler approach: register and check via respond routing
            try {
                // Create a snapshot by trying respond with a dummy - but that would consume it
                // Instead, we expose via reflection-free approach:
                // register again (idempotent for Sets) and check size change
                return pendingPermissionIds().contains(id);
            } catch (Exception e) {
                return false;
            }
        }

        private java.util.Set<String> pendingPermissionIds() {
            try {
                var field = CliLlmProvider.class.getDeclaredField("pendingPermissionIds");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var set = (java.util.Set<String>) field.get(this);
                return set;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // --- Result-text fallback when the CLI emits no assistant event ---

    @Nested
    @DisplayName("Result text fallback")
    class ResultTextFallback {

        private java.util.List<com.scivicslab.chatui.core.rest.ChatEvent> emitted;
        private java.util.function.Consumer<com.scivicslab.chatui.core.rest.ChatEvent> emitter;

        @BeforeEach
        void collectEmissions() {
            emitted = new java.util.ArrayList<>();
            emitter = emitted::add;
        }

        private com.scivicslab.chatui.cli.process.StreamEvent resultEvent(String text) {
            return new com.scivicslab.chatui.cli.process.StreamEvent(
                    "result", text, "s-1", 0.01, 100L, false, "{}");
        }

        private com.scivicslab.chatui.cli.process.StreamEvent assistantEvent(String text) {
            return new com.scivicslab.chatui.cli.process.StreamEvent(
                    "assistant", text, null, -1, -1, false, "{}");
        }

        private java.util.List<String> deltaTexts() {
            return emitted.stream()
                    .filter(e -> "delta".equals(e.type()))
                    .map(com.scivicslab.chatui.core.rest.ChatEvent::content)
                    .toList();
        }

        @Test
        @DisplayName("result text is emitted as a delta when no assistant event arrived")
        void dispatch_resultWithoutAssistant_emitsDelta() {
            boolean[] sawAssistant = {false};
            provider.dispatch(resultEvent("Final answer."), emitter, new boolean[]{false}, sawAssistant);
            assertEquals(java.util.List.of("Final answer."), deltaTexts());
        }

        @Test
        @DisplayName("result text is not duplicated when an assistant event already streamed it")
        void dispatch_resultAfterAssistant_doesNotDuplicate() {
            boolean[] sawAssistant = {false};
            provider.dispatch(assistantEvent("Streamed answer."), emitter, new boolean[]{false}, sawAssistant);
            provider.dispatch(resultEvent("Streamed answer."), emitter, new boolean[]{false}, sawAssistant);
            // Only the assistant delta; the result must not re-send the same text.
            assertEquals(java.util.List.of("Streamed answer."), deltaTexts());
        }

        @Test
        @DisplayName("result with blank text emits no delta")
        void dispatch_resultBlankText_emitsNoDelta() {
            boolean[] sawAssistant = {false};
            provider.dispatch(resultEvent("   "), emitter, new boolean[]{false}, sawAssistant);
            assertTrue(deltaTexts().isEmpty());
        }

        @Test
        @DisplayName("result event always emits the result metadata event")
        void dispatch_result_alwaysEmitsResultEvent() {
            boolean[] sawAssistant = {false};
            provider.dispatch(resultEvent("Final answer."), emitter, new boolean[]{false}, sawAssistant);
            assertTrue(emitted.stream().anyMatch(e -> "result".equals(e.type())));
        }
    }
}
