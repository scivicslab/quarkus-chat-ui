package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliProcessTest {

    private static final String BINARY = "claude";
    private static final String API_KEY_ENV = "ANTHROPIC_API_KEY";

    private CliConfig defaultConfig;

    @BeforeEach
    void setUp() {
        defaultConfig = CliConfig.defaults("claude-sonnet-4-5");
    }

    // --- Constructor tests ---

    @Test
    @DisplayName("constructor stores binary, apiKeyEnvVar, and config")
    void constructor_storesFields() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        assertSame(defaultConfig, process.getConfig());
    }

    // --- buildCommand: base flags ---

    @Nested
    @DisplayName("buildCommand() base flags")
    class BuildCommandBase {

        @Test
        @DisplayName("generates correct base command for default config")
        void defaultConfig_generatesBaseCommand() {
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
            List<String> cmd = process.buildCommand();

            assertEquals(BINARY, cmd.get(0));
            assertTrue(cmd.contains("--output-format"));
            assertTrue(cmd.contains("stream-json"));
            assertTrue(cmd.contains("--input-format"));
            assertTrue(cmd.contains("--verbose"));
            assertTrue(cmd.contains("--model"));
            assertTrue(cmd.contains("claude-sonnet-4-5"));
        }

        @Test
        @DisplayName("default config does NOT include --permission-mode (provider sets it)")
        void defaultConfig_excludesPermissionMode() {
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
            List<String> cmd = process.buildCommand();

            assertFalse(cmd.contains("--permission-mode"),
                "CliConfig.defaults() has null permissionMode, so --permission-mode " +
                "must NOT appear. Each provider sets its own default.");
        }
    }

    // --- buildCommand: --model ---

    @Nested
    @DisplayName("buildCommand() --model flag")
    class BuildCommandModel {

        @Test
        @DisplayName("includes --model when model is set")
        void withModel_includesModelFlag() {
            CliConfig config = CliConfig.defaults("claude-opus-4");
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            int idx = cmd.indexOf("--model");
            assertTrue(idx >= 0, "--model flag should be present");
            assertEquals("claude-opus-4", cmd.get(idx + 1));
        }

        @Test
        @DisplayName("does not include --model when model is null")
        void nullModel_excludesModelFlag() {
            CliConfig config = new CliConfig(null, null, 0, null, null, false, null, null);
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            assertFalse(cmd.contains("--model"));
        }
    }

    // --- buildCommand: --permission-mode ---

    @Nested
    @DisplayName("buildCommand() --permission-mode flag")
    class BuildCommandPermissionMode {

        @ParameterizedTest
        @ValueSource(strings = {"acceptEdits", "default", "auto", "bypassPermissions", "plan"})
        @DisplayName("includes --permission-mode for each valid value")
        void validValues_includesFlag(String mode) {
            CliConfig config = CliConfig.defaults("m").withPermissionMode(mode);
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            int idx = cmd.indexOf("--permission-mode");
            assertTrue(idx >= 0);
            assertEquals(mode, cmd.get(idx + 1));
        }

        @Test
        @DisplayName("excludes --permission-mode when null")
        void nullPermissionMode_excludesFlag() {
            CliConfig config = new CliConfig("m", null, 0, null, null, false, null, null);
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            assertFalse(cmd.contains("--permission-mode"));
        }

        @Test
        @DisplayName("excludes --permission-mode when blank")
        void blankPermissionMode_excludesFlag() {
            CliConfig config = CliConfig.defaults("m").withPermissionMode("   ");
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            assertFalse(cmd.contains("--permission-mode"));
        }

        @Test
        @DisplayName("--permission-mode appears before --allowedTools in command")
        void permissionModeBeforeAllowedTools() {
            CliConfig config = CliConfig.defaults("m")
                .withPermissionMode("bypassPermissions")
                .withAllowedTools("Bash");
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            int permIdx = cmd.indexOf("--permission-mode");
            int toolsIdx = cmd.indexOf("--allowedTools");
            assertTrue(permIdx >= 0);
            assertTrue(toolsIdx >= 0);
            assertTrue(permIdx < toolsIdx, "--permission-mode should come before --allowedTools");
        }
    }

    // --- buildCommand: --resume ---

    @Nested
    @DisplayName("buildCommand() --resume flag")
    class BuildCommandResume {

        @Test
        @DisplayName("includes --resume when sessionId is set")
        void withSessionId_includesResumeFlag() {
            CliConfig config = defaultConfig.withSessionId("s-abc-123");
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            int idx = cmd.indexOf("--resume");
            assertTrue(idx >= 0, "--resume flag should be present");
            assertEquals("s-abc-123", cmd.get(idx + 1));
        }

        @Test
        @DisplayName("does not include --resume when sessionId is null")
        void nullSessionId_excludesResumeFlag() {
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
            List<String> cmd = process.buildCommand();

            assertFalse(cmd.contains("--resume"));
        }
    }

    // --- buildCommand: -c (continue) ---

    @Nested
    @DisplayName("buildCommand() -c flag")
    class BuildCommandContinue {

        @Test
        @DisplayName("includes -c when continueSession is true")
        void withContinueSession_includesCFlag() {
            CliConfig config = defaultConfig.withContinueSession();
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            assertTrue(process.buildCommand().contains("-c"));
        }

        @Test
        @DisplayName("does not include -c when continueSession is false")
        void withoutContinueSession_excludesCFlag() {
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
            assertFalse(process.buildCommand().contains("-c"));
        }
    }

    // --- buildCommand: --max-turns ---

    @Nested
    @DisplayName("buildCommand() --max-turns flag")
    class BuildCommandMaxTurns {

        @Test
        @DisplayName("includes --max-turns when maxTurns > 0")
        void withMaxTurns_includesMaxTurnsFlag() {
            CliConfig config = defaultConfig.withMaxTurns(5);
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            int idx = cmd.indexOf("--max-turns");
            assertTrue(idx >= 0);
            assertEquals("5", cmd.get(idx + 1));
        }

        @Test
        @DisplayName("does not include --max-turns when maxTurns is 0")
        void zeroMaxTurns_excludesMaxTurnsFlag() {
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
            assertFalse(process.buildCommand().contains("--max-turns"));
        }
    }

    // --- buildCommand: --allowedTools ---

    @Nested
    @DisplayName("buildCommand() --allowedTools flag")
    class BuildCommandAllowedTools {

        @Test
        @DisplayName("includes --allowedTools for each tool")
        void withAllowedTools_includesEachTool() {
            CliConfig config = defaultConfig.withAllowedTools("Bash", "Read", "Write");
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
            List<String> cmd = process.buildCommand();

            long count = cmd.stream().filter("--allowedTools"::equals).count();
            assertEquals(3, count);

            int firstIdx = cmd.indexOf("--allowedTools");
            assertEquals("Bash", cmd.get(firstIdx + 1));
        }

        @Test
        @DisplayName("does not include --allowedTools when tools is null")
        void nullAllowedTools_excludesFlag() {
            CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
            assertFalse(process.buildCommand().contains("--allowedTools"));
        }
    }

    // --- buildCommand: all options combined ---

    @Test
    @DisplayName("buildCommand() combines all options correctly")
    void buildCommand_allOptionsSet_combinesCorrectly() {
        CliConfig config = CliConfig.defaults("claude-opus-4")
            .withSessionId("s-999")
            .withContinueSession()
            .withMaxTurns(3)
            .withPermissionMode("bypassPermissions")
            .withAllowedTools("Bash", "Edit");
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        assertEquals(BINARY, cmd.get(0));
        assertTrue(cmd.contains("--output-format"));
        assertTrue(cmd.contains("--input-format"));
        assertTrue(cmd.contains("--verbose"));
        assertTrue(cmd.contains("--model"));
        assertTrue(cmd.contains("claude-opus-4"));
        assertTrue(cmd.contains("--resume"));
        assertTrue(cmd.contains("s-999"));
        assertTrue(cmd.contains("-c"));
        assertTrue(cmd.contains("--max-turns"));
        assertTrue(cmd.contains("3"));
        assertTrue(cmd.contains("--permission-mode"));
        assertTrue(cmd.contains("bypassPermissions"));
        assertEquals(2, cmd.stream().filter("--allowedTools"::equals).count());
    }

    // --- isAlive ---

    @Test
    @DisplayName("isAlive() returns false when no process has been started")
    void isAlive_noProcessStarted_returnsFalse() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        assertFalse(process.isAlive());
    }

    // --- normalisePermissionResponse ---

    @Nested
    @DisplayName("normalisePermissionResponse()")
    class NormalisePermissionResponse {

        @ParameterizedTest
        @ValueSource(strings = {"yes", "Yes", "YES", "y", "Y", "1", "ok", "OK", "allow", "Allow"})
        @DisplayName("maps affirmative inputs to 'yes'")
        void affirmative_mapsToYes(String input) {
            assertEquals("yes", CliProcess.normalisePermissionResponse(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "yes-dont-ask-again", "Yes, don't ask again",
            "yes don't ask again", "always", "Always"
        })
        @DisplayName("maps always-approve inputs to 'yes-dont-ask-again'")
        void alwaysApprove_mapsToDontAskAgain(String input) {
            assertEquals("yes-dont-ask-again", CliProcess.normalisePermissionResponse(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {"no", "No", "NO", "n", "deny", "reject", "nope", "cancel"})
        @DisplayName("maps negative or unknown inputs to 'no'")
        void negative_mapsToNo(String input) {
            assertEquals("no", CliProcess.normalisePermissionResponse(input));
        }

        @Test
        @DisplayName("null input maps to 'no'")
        void null_mapsToNo() {
            assertEquals("no", CliProcess.normalisePermissionResponse(null));
        }

        @Test
        @DisplayName("whitespace-padded input is trimmed")
        void whitespace_isTrimmed() {
            assertEquals("yes", CliProcess.normalisePermissionResponse("  yes  "));
        }
    }

    // --- escapeJsonString ---

    @Nested
    @DisplayName("escapeJsonString()")
    class EscapeJsonString {

        @Test
        @DisplayName("wraps simple text in double quotes")
        void simpleText_wrapsInQuotes() {
            assertEquals("\"hello\"", CliProcess.escapeJsonString("hello"));
        }

        @Test
        @DisplayName("escapes double quotes")
        void doubleQuotes_escaped() {
            assertEquals("\"say \\\"hello\\\"\"", CliProcess.escapeJsonString("say \"hello\""));
        }

        @Test
        @DisplayName("escapes backslashes")
        void backslashes_escaped() {
            assertEquals("\"path\\\\to\\\\file\"", CliProcess.escapeJsonString("path\\to\\file"));
        }

        @Test
        @DisplayName("escapes newlines")
        void newlines_escaped() {
            assertEquals("\"line1\\nline2\"", CliProcess.escapeJsonString("line1\nline2"));
        }

        @Test
        @DisplayName("escapes carriage returns")
        void carriageReturns_escaped() {
            assertEquals("\"line1\\rline2\"", CliProcess.escapeJsonString("line1\rline2"));
        }

        @Test
        @DisplayName("escapes tabs")
        void tabs_escaped() {
            assertEquals("\"col1\\tcol2\"", CliProcess.escapeJsonString("col1\tcol2"));
        }

        @Test
        @DisplayName("escapes control characters as unicode")
        void controlChars_escapedAsUnicode() {
            assertEquals("\"a\\u0001b\"", CliProcess.escapeJsonString("a\u0001b"));
        }

        @Test
        @DisplayName("preserves Japanese text")
        void japaneseText_preservedCorrectly() {
            assertEquals("\"日本語テスト\"", CliProcess.escapeJsonString("日本語テスト"));
        }

        @Test
        @DisplayName("handles mixed content with special chars and Japanese")
        void mixedContent_handledCorrectly() {
            assertEquals("\"Say \\\"こんにちは\\\"\\nNew line\"",
                CliProcess.escapeJsonString("Say \"こんにちは\"\nNew line"));
        }

        @Test
        @DisplayName("handles empty string")
        void emptyString_returnsEmptyQuoted() {
            assertEquals("\"\"", CliProcess.escapeJsonString(""));
        }
    }

    // --- stripAnsi ---

    @Nested
    @DisplayName("stripAnsi()")
    class StripAnsi {

        @Test
        @DisplayName("removes ANSI color codes")
        void colorCodes_removed() {
            assertEquals("ERROR: something failed",
                CliProcess.stripAnsi("\u001b[31mERROR\u001b[0m: something failed"));
        }

        @Test
        @DisplayName("removes ANSI cursor movement codes")
        void cursorCodes_removed() {
            assertEquals("Hello", CliProcess.stripAnsi("\u001b[2J\u001b[HHello"));
        }

        @Test
        @DisplayName("removes ANSI SGR with multiple parameters")
        void sgrMultiParams_removed() {
            assertEquals("SUCCESS", CliProcess.stripAnsi("\u001b[1;32mSUCCESS\u001b[0m"));
        }

        @Test
        @DisplayName("preserves normal text without ANSI sequences")
        void normalText_preserved() {
            String input = "This is plain text with no ANSI codes.";
            assertEquals(input, CliProcess.stripAnsi(input));
        }

        @Test
        @DisplayName("preserves empty string")
        void emptyString_returnsEmpty() {
            assertEquals("", CliProcess.stripAnsi(""));
        }

        @Test
        @DisplayName("preserves Japanese text")
        void japaneseText_preserved() {
            assertEquals("日本語テスト", CliProcess.stripAnsi("日本語テスト"));
        }

        @Test
        @DisplayName("removes ANSI from text with Japanese characters")
        void ansiWithJapanese_removesOnlyAnsi() {
            assertEquals("警告: テスト失敗",
                CliProcess.stripAnsi("\u001b[33m警告\u001b[0m: テスト失敗"));
        }
    }

    // --- getConfig / setConfig ---

    @Test
    @DisplayName("setConfig() replaces the configuration")
    void setConfig_replacesConfig() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        CliConfig newConfig = CliConfig.defaults("claude-opus-4");

        process.setConfig(newConfig);

        assertSame(newConfig, process.getConfig());
        assertNotSame(defaultConfig, process.getConfig());
    }

    // --- getLastSessionId ---

    @Test
    @DisplayName("getLastSessionId() returns null when no result event has been received")
    void getLastSessionId_noResult_returnsNull() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        assertNull(process.getLastSessionId());
    }
}
