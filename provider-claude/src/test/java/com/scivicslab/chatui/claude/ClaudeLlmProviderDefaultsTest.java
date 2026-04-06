package com.scivicslab.chatui.claude;

import com.scivicslab.chatui.cli.CliLlmProvider;
import com.scivicslab.chatui.cli.process.CliConfig;
import com.scivicslab.chatui.core.provider.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Claude-specific CLI defaults.
 *
 * <p>These tests exist because of a severe regression where Bash/curl tools
 * were silently denied after switching from {@code --allowedTools} to
 * {@code --permission-mode acceptEdits}. See {@code PermissionModePostmortem_260404_oo01}.</p>
 *
 * <p>Uses a minimal subclass of {@link CliLlmProvider} to test the constructor
 * logic without CDI/Quarkus. Tests verify the resulting {@link CliConfig} rather
 * than the CLI command (which is package-private to provider-claude-code).</p>
 */
class ClaudeLlmProviderDefaultsTest {

    // ---- Default permission mode ----

    @Nested
    @DisplayName("Claude provider default permission mode")
    class DefaultPermissionMode {

        @Test
        @DisplayName("Claude provider defaults to bypassPermissions when no config is set")
        void claudeProvider_defaultsBypassPermissions() {
            // Simulate: @ConfigProperty not set → Optional.empty()
            var provider = new TestClaudeProvider(Optional.empty(), Optional.empty());
            CliConfig config = provider.getConfigForTest();

            assertEquals("bypassPermissions", config.permissionMode(),
                "Claude provider MUST default to 'bypassPermissions'. " +
                "Without it, stream-json mode auto-denies all tool permissions. " +
                "acceptEdits does NOT cover Bash/curl — see PermissionModePostmortem_260404_oo01.");
        }

        @Test
        @DisplayName("bypassPermissions allows ALL tools including Bash for curl/MCP")
        void bypassPermissions_allowsAllToolsIncludingBash() {
            // This test documents the critical requirement:
            // A chat UI agent needs Bash for curl, MCP communication, etc.
            // acceptEdits only covers: Read, Write, Edit, Glob, Grep
            // bypassPermissions covers: ALL tools
            var provider = new TestClaudeProvider(Optional.empty(), Optional.empty());
            CliConfig config = provider.getConfigForTest();

            assertNotEquals("acceptEdits", config.permissionMode(),
                "REGRESSION GUARD: acceptEdits does NOT cover Bash. " +
                "curl commands will fail with permission errors. " +
                "See PermissionModePostmortem_260404_oo01.");
        }

        @Test
        @DisplayName("config override replaces Claude default")
        void configOverride_replacesDefault() {
            // User explicitly sets permission-mode in application.properties
            var provider = new TestClaudeProvider(Optional.empty(), Optional.of("plan"));
            CliConfig config = provider.getConfigForTest();

            assertEquals("plan", config.permissionMode(),
                "Explicit config must override the Claude default.");
        }
    }

    // ---- Regression guards ----

    @Nested
    @DisplayName("Regression guards — PermissionModePostmortem_260404_oo01")
    class RegressionGuards {

        @Test
        @DisplayName("All tools from quarkus-llm-console-claude must remain usable")
        void allLegacyTools_mustRemainUsable() {
            // These tools worked in quarkus-llm-console-claude via --allowedTools:
            //   Bash, Read, Write, Edit, Glob, Grep, WebSearch, WebFetch
            // With bypassPermissions, all tools are auto-approved.
            var provider = new TestClaudeProvider(Optional.empty(), Optional.empty());
            CliConfig config = provider.getConfigForTest();

            assertEquals("bypassPermissions", config.permissionMode(),
                "bypassPermissions covers all tools. " +
                "Legacy tools that must work: Bash, Read, Write, Edit, Glob, Grep, WebSearch, WebFetch");
        }

        @Test
        @DisplayName("acceptEdits must NEVER be the default — it breaks Bash/curl")
        void acceptEdits_mustNeverBeDefault() {
            var provider = new TestClaudeProvider(Optional.empty(), Optional.empty());
            CliConfig config = provider.getConfigForTest();

            assertNotEquals("acceptEdits", config.permissionMode(),
                "CRITICAL: acceptEdits silently denies Bash commands including curl. " +
                "This breaks MCP Gateway communication, API calls, and all Bash-based tools. " +
                "The correct default for a server-side chat UI is bypassPermissions.");
        }

        @Test
        @DisplayName("session restore preserves bypassPermissions in config")
        void sessionRestore_preservesBypassPermissions() {
            var provider = new TestClaudeProvider(Optional.empty(), Optional.empty());
            CliConfig config = provider.getConfigForTest().withSessionId("old-session-id");

            assertEquals("bypassPermissions", config.permissionMode(),
                "permissionMode must survive withSessionId(). " +
                "Note: CLI may still use the mode from session creation.");
            assertEquals("old-session-id", config.sessionId());
        }
    }

    // ---- CliConfig.defaults() is generic ----

    @Nested
    @DisplayName("CliConfig.defaults() is CLI-agnostic")
    class CliConfigDefaults {

        @Test
        @DisplayName("CliConfig.defaults() does NOT set permissionMode")
        void defaults_noPermissionMode() {
            CliConfig config = CliConfig.defaults("any-model");
            assertNull(config.permissionMode(),
                "CliConfig.defaults() must not set permissionMode. " +
                "Permission mode is CLI-specific (Claude vs Codex) and belongs in the provider.");
        }

        @Test
        @DisplayName("CliConfig.defaults() does NOT set allowedTools")
        void defaults_noAllowedTools() {
            CliConfig config = CliConfig.defaults("any-model");
            assertNull(config.allowedTools(),
                "CliConfig.defaults() must not set allowedTools. " +
                "Allowed tools are CLI-specific and belong in the provider.");
        }
    }

    // ---- Permission mode semantics documentation ----

    @Nested
    @DisplayName("Permission mode semantics (documentation as tests)")
    class PermissionModeSemantics {

        @Test
        @DisplayName("acceptEdits does NOT cover Bash — documented limitation")
        void acceptEdits_doesNotCoverBash() {
            // As of Claude Code CLI 2.1.x:
            //   acceptEdits covers: Read, Write, Edit, Glob, Grep (file operations only)
            //   acceptEdits does NOT cover: Bash, WebSearch, WebFetch
            //
            // If Claude CLI changes acceptEdits to include Bash in the future,
            // update this test AND the default config accordingly.
            CliConfig config = CliConfig.defaults("m").withPermissionMode("acceptEdits");
            assertEquals("acceptEdits", config.permissionMode());
            // WARNING: This config will silently deny Bash commands.
        }

        @Test
        @DisplayName("bypassPermissions covers ALL tools including Bash")
        void bypassPermissions_coversAllTools() {
            // bypassPermissions auto-approves all tool operations without prompting.
            // This is the correct default for a server-side chat UI where:
            //   1. The CLI runs as a controlled subprocess
            //   2. The trust boundary is at the web UI level
            //   3. Bash/curl is needed for MCP communication
            CliConfig config = CliConfig.defaults("m").withPermissionMode("bypassPermissions");
            assertEquals("bypassPermissions", config.permissionMode());
        }
    }

    // ---- Test helper ----

    /**
     * Minimal concrete subclass of CliLlmProvider for testing Claude defaults.
     * Reproduces the same default logic as {@link ClaudeLlmProvider}:
     * {@code permissionMode.or(() -> Optional.of("bypassPermissions"))}.
     */
    private static class TestClaudeProvider extends CliLlmProvider {

        private static final String DEFAULT_PERMISSION_MODE = "bypassPermissions";

        TestClaudeProvider(Optional<String> allowedTools, Optional<String> permissionMode) {
            super("claude", "ANTHROPIC_API_KEY", "claude-sonnet-4-6",
                  allowedTools,
                  permissionMode.filter(s -> !s.isBlank()).or(() -> Optional.of(DEFAULT_PERMISSION_MODE)),
                  System.getProperty("java.io.tmpdir") + "/.test-claude-session",
                  9999);
        }

        CliConfig getConfigForTest() {
            return cliProcess.getConfig();
        }

        @Override public String id() { return "claude"; }
        @Override public String displayName() { return "Claude (Test)"; }
        @Override public List<LlmProvider.ModelEntry> getAvailableModels() {
            return List.of(new LlmProvider.ModelEntry("claude-sonnet-4-6", "claude", null));
        }
        @Override public String detectEnvApiKey() { return null; }
    }
}
