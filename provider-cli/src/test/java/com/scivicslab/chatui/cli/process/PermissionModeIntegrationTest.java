package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level tests verifying the full permission mode pipeline:
 * CliConfig → CliProcess.buildCommand() → correct CLI flags.
 *
 * <p>These tests simulate what each provider does: start with
 * {@code CliConfig.defaults()} (permissionMode=null) and then apply
 * provider-specific settings via {@code withPermissionMode()}.</p>
 *
 * <p>See {@code PermissionModePostmortem_260404_oo01} for why these tests exist.</p>
 */
class PermissionModeIntegrationTest {

    /** Simulates what ClaudeLlmProvider does: defaults + bypassPermissions. */
    private static CliConfig claudeConfig(String model) {
        return CliConfig.defaults(model).withPermissionMode("bypassPermissions");
    }

    @Nested
    @DisplayName("CliConfig.defaults() is generic (no CLI-specific defaults)")
    class DefaultsAreGeneric {

        @Test
        @DisplayName("defaults() does NOT set permissionMode")
        void defaults_noPermissionMode() {
            CliConfig config = CliConfig.defaults("claude-sonnet-4-5");
            assertNull(config.permissionMode(),
                "CliConfig.defaults() must not set permissionMode. " +
                "Each provider sets its own default.");
        }

        @Test
        @DisplayName("defaults() buildCommand excludes --permission-mode")
        void defaults_buildCommand_excludesPermissionMode() {
            CliConfig config = CliConfig.defaults("claude-sonnet-4-5");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            assertFalse(cmd.contains("--permission-mode"),
                "Without explicit permissionMode, --permission-mode must not appear.");
        }
    }

    @Nested
    @DisplayName("Claude provider config produces correct CLI command")
    class ClaudeProviderConfig {

        @Test
        @DisplayName("Claude config includes --permission-mode bypassPermissions")
        void claudeConfig_producesBypassPermissions() {
            CliConfig config = claudeConfig("claude-sonnet-4-5");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            int permIdx = cmd.indexOf("--permission-mode");
            assertTrue(permIdx >= 0, "Claude config MUST include --permission-mode. " +
                "Without this flag, stream-json mode auto-denies all tool permissions.");
            assertEquals("bypassPermissions", cmd.get(permIdx + 1),
                "Claude provider must use 'bypassPermissions' so all tools " +
                "(including Bash/curl for MCP communication) are auto-approved. " +
                "acceptEdits does NOT cover Bash — see PermissionModePostmortem_260404_oo01.");
        }

        @Test
        @DisplayName("command contains all required base flags for stream-json mode")
        void claudeConfig_containsAllBaseFlags() {
            CliConfig config = claudeConfig("claude-sonnet-4-5");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            assertEquals("claude", cmd.get(0), "Binary name");
            assertTrue(cmd.contains("--output-format"), "Must have --output-format");
            assertTrue(cmd.contains("--input-format"), "Must have --input-format");
            assertTrue(cmd.contains("stream-json"), "Format must be stream-json");
            assertTrue(cmd.contains("--verbose"), "Must have --verbose");
            assertTrue(cmd.contains("--permission-mode"), "Must have --permission-mode");
        }
    }

    @Nested
    @DisplayName("Permission mode with session restore")
    class WithSessionRestore {

        @Test
        @DisplayName("session restore preserves permission mode")
        void sessionRestore_preservesPermissionMode() {
            CliConfig config = claudeConfig("claude-sonnet-4-5")
                .withSessionId("restored-session-id");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            assertTrue(cmd.contains("--permission-mode"));
            assertTrue(cmd.contains("bypassPermissions"));
            assertTrue(cmd.contains("--resume"));
            assertTrue(cmd.contains("restored-session-id"));
        }
    }

    @Nested
    @DisplayName("Permission mode with allowed tools")
    class WithAllowedTools {

        @Test
        @DisplayName("permission mode and allowed tools coexist in command")
        void permissionModeAndAllowedTools_bothPresent() {
            CliConfig config = claudeConfig("claude-sonnet-4-5")
                .withAllowedTools("Bash", "Read", "Write");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            assertTrue(cmd.contains("--permission-mode"));
            assertTrue(cmd.contains("bypassPermissions"));
            assertEquals(3, cmd.stream().filter("--allowedTools"::equals).count());
        }
    }

    @Nested
    @DisplayName("Override permission mode")
    class OverridePermissionMode {

        @Test
        @DisplayName("bypassPermissions mode generates correct command")
        void bypassPermissions_generatesCorrectCommand() {
            CliConfig config = CliConfig.defaults("claude-sonnet-4-5")
                .withPermissionMode("bypassPermissions");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            int idx = cmd.indexOf("--permission-mode");
            assertEquals("bypassPermissions", cmd.get(idx + 1));
        }

        @Test
        @DisplayName("plan mode generates correct command")
        void planMode_generatesCorrectCommand() {
            CliConfig config = CliConfig.defaults("claude-sonnet-4-5")
                .withPermissionMode("plan");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            int idx = cmd.indexOf("--permission-mode");
            assertEquals("plan", cmd.get(idx + 1));
        }
    }

    @Nested
    @DisplayName("Regression guards — PermissionModePostmortem_260404_oo01")
    class RegressionGuards {

        @Test
        @DisplayName("CliConfig.defaults() must NOT set permissionMode (provider responsibility)")
        void defaults_mustNotSetPermissionMode() {
            CliConfig config = CliConfig.defaults("any-model");
            assertNull(config.permissionMode(),
                "CliConfig.defaults() must not hardcode a permission mode. " +
                "Permission mode is CLI-specific (Claude vs Codex) and belongs in each provider. " +
                "Hardcoding here caused the acceptEdits regression — see PermissionModePostmortem_260404_oo01.");
        }

        @Test
        @DisplayName("null permissionMode produces no --permission-mode flag")
        void nullPermissionMode_noFlag() {
            CliConfig config = CliConfig.defaults("m");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            assertFalse(cmd.contains("--permission-mode"),
                "null permissionMode → no flag. Provider must set it explicitly.");
        }

        @Test
        @DisplayName("acceptEdits in command does NOT guarantee Bash works")
        void acceptEdits_doesNotGuaranteeBash() {
            // DOCUMENTATION TEST: acceptEdits covers ONLY file operations.
            // Bash, WebSearch, WebFetch are NOT covered.
            // Using acceptEdits as a default caused curl/MCP to break.
            CliConfig config = CliConfig.defaults("m").withPermissionMode("acceptEdits");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            int idx = cmd.indexOf("--permission-mode");
            assertEquals("acceptEdits", cmd.get(idx + 1));
            // WARNING: This command will silently deny Bash commands.
            // If you see this mode in production, it is likely a bug.
        }

        @Test
        @DisplayName("session restore + permissionMode: both flags coexist")
        void sessionRestore_permissionModeCoexist() {
            CliConfig config = CliConfig.defaults("m")
                .withPermissionMode("bypassPermissions")
                .withSessionId("old-session");
            CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
            List<String> cmd = process.buildCommand();

            assertTrue(cmd.contains("--permission-mode"));
            assertTrue(cmd.contains("bypassPermissions"));
            assertTrue(cmd.contains("--resume"));
            assertTrue(cmd.contains("old-session"));

            // Note: CLI may ignore --permission-mode for resumed sessions
            // and use the mode from session creation. If this happens,
            // the session file must be deleted and a new session started.
            // There is no way to test this without a real CLI process.
        }
    }

    @Nested
    @DisplayName("Permission response normalization")
    class PermissionResponseNormalization {

        @Test
        @DisplayName("UI button labels are correctly normalized for CLI")
        void uiButtonLabels_normalizedForCli() {
            assertEquals("yes", CliProcess.normalisePermissionResponse("Yes"));
            assertEquals("yes-dont-ask-again", CliProcess.normalisePermissionResponse("Yes, don't ask again"));
            assertEquals("no", CliProcess.normalisePermissionResponse("No"));
        }

        @Test
        @DisplayName("alternative affirmative responses are normalized")
        void alternativeAffirmative_normalized() {
            assertEquals("yes", CliProcess.normalisePermissionResponse("allow"));
            assertEquals("yes", CliProcess.normalisePermissionResponse("ok"));
            assertEquals("yes", CliProcess.normalisePermissionResponse("y"));
        }

        @Test
        @DisplayName("always-approve responses are normalized")
        void alwaysApprove_normalized() {
            assertEquals("yes-dont-ask-again", CliProcess.normalisePermissionResponse("always"));
            assertEquals("yes-dont-ask-again", CliProcess.normalisePermissionResponse("yes-dont-ask-again"));
        }
    }

    @Nested
    @DisplayName("StreamEventParser handles permission-related events")
    class ParserPermissionEvents {

        private final StreamEventParser parser = new StreamEventParser();

        @Test
        @DisplayName("parser correctly identifies permission_request subtype")
        void parser_permissionRequest_identifiedCorrectly() {
            String json = """
                {"type":"system","subtype":"permission_request",
                 "tool_use_id":"toolu_test","tool_name":"Write",
                 "tool_input":{"file_path":"/home/user/test.txt"}}
                """.trim();
            StreamEvent e = parser.parse(json);

            assertEquals("prompt", e.type());
            assertEquals("permission", e.promptType());
            assertEquals("toolu_test", e.promptId());
            assertTrue(e.isPrompt());
        }

        @Test
        @DisplayName("parser handles permission denial as tool_result with rawJson")
        void parser_permissionDenial_parsedAsToolResult() {
            String json = """
                {"type":"user",
                 "tool_use_result":"Error: Claude requested permissions to write to /tmp/test.txt, but you haven't granted it yet.",
                 "message":{"role":"user","content":[
                   {"type":"tool_result","content":"Claude requested permissions to write to /tmp/test.txt, but you haven't granted it yet.","is_error":true,"tool_use_id":"toolu_denied"}
                 ]}}
                """.trim();
            StreamEvent e = parser.parse(json);

            assertEquals("tool_result", e.type());
            assertNotNull(e.rawJson());
            assertTrue(e.rawJson().contains("permission"));
        }
    }
}
