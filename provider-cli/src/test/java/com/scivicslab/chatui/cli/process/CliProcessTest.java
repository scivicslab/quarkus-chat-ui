package com.scivicslab.chatui.cli.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    // --- buildCommand tests ---

    @Test
    @DisplayName("buildCommand() generates correct base command for default config")
    void buildCommand_defaultConfig_generatesBaseCommand() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        List<String> cmd = process.buildCommand();

        assertEquals(BINARY, cmd.get(0));
        assertTrue(cmd.contains("--output-format"));
        assertTrue(cmd.contains("stream-json"));
        assertTrue(cmd.contains("--input-format"));
        assertTrue(cmd.contains("--verbose"));
        // Default config has model set
        assertTrue(cmd.contains("--model"));
        assertTrue(cmd.contains("claude-sonnet-4-5"));
    }

    @Test
    @DisplayName("buildCommand() includes --model when model is set")
    void buildCommand_withModel_includesModelFlag() {
        CliConfig config = CliConfig.defaults("claude-opus-4");
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        int idx = cmd.indexOf("--model");
        assertTrue(idx >= 0, "--model flag should be present");
        assertEquals("claude-opus-4", cmd.get(idx + 1));
    }

    @Test
    @DisplayName("buildCommand() does not include --model when model is null")
    void buildCommand_nullModel_excludesModelFlag() {
        CliConfig config = new CliConfig(null, null, 0, null, null, false, null);
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        assertFalse(cmd.contains("--model"));
    }

    @Test
    @DisplayName("buildCommand() includes --resume when sessionId is set")
    void buildCommand_withSessionId_includesResumeFlag() {
        CliConfig config = defaultConfig.withSessionId("s-abc-123");
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        int idx = cmd.indexOf("--resume");
        assertTrue(idx >= 0, "--resume flag should be present");
        assertEquals("s-abc-123", cmd.get(idx + 1));
    }

    @Test
    @DisplayName("buildCommand() does not include --resume when sessionId is null")
    void buildCommand_nullSessionId_excludesResumeFlag() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        List<String> cmd = process.buildCommand();

        assertFalse(cmd.contains("--resume"));
    }

    @Test
    @DisplayName("buildCommand() includes -c when continueSession is true")
    void buildCommand_withContinueSession_includesCFlag() {
        CliConfig config = defaultConfig.withContinueSession();
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        assertTrue(cmd.contains("-c"));
    }

    @Test
    @DisplayName("buildCommand() does not include -c when continueSession is false")
    void buildCommand_withoutContinueSession_excludesCFlag() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        List<String> cmd = process.buildCommand();

        assertFalse(cmd.contains("-c"));
    }

    @Test
    @DisplayName("buildCommand() includes --max-turns when maxTurns > 0")
    void buildCommand_withMaxTurns_includesMaxTurnsFlag() {
        CliConfig config = defaultConfig.withMaxTurns(5);
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        int idx = cmd.indexOf("--max-turns");
        assertTrue(idx >= 0, "--max-turns flag should be present");
        assertEquals("5", cmd.get(idx + 1));
    }

    @Test
    @DisplayName("buildCommand() does not include --max-turns when maxTurns is 0")
    void buildCommand_zeroMaxTurns_excludesMaxTurnsFlag() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        List<String> cmd = process.buildCommand();

        assertFalse(cmd.contains("--max-turns"));
    }

    @Test
    @DisplayName("buildCommand() includes --allowedTools for each tool")
    void buildCommand_withAllowedTools_includesEachTool() {
        CliConfig config = defaultConfig.withAllowedTools("Bash", "Read", "Write");
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        // Each tool should be preceded by --allowedTools
        int firstIdx = cmd.indexOf("--allowedTools");
        assertTrue(firstIdx >= 0, "--allowedTools flag should be present");

        // Count occurrences of --allowedTools
        long count = cmd.stream().filter("--allowedTools"::equals).count();
        assertEquals(3, count, "should have one --allowedTools per tool");

        // Verify the tools follow their flags in order
        assertEquals("Bash", cmd.get(firstIdx + 1));
        int secondIdx = cmd.subList(firstIdx + 1, cmd.size()).indexOf("--allowedTools") + firstIdx + 1;
        assertEquals("Read", cmd.get(secondIdx + 1));
        int thirdIdx = cmd.subList(secondIdx + 1, cmd.size()).indexOf("--allowedTools") + secondIdx + 1;
        assertEquals("Write", cmd.get(thirdIdx + 1));
    }

    @Test
    @DisplayName("buildCommand() does not include --allowedTools when tools is null")
    void buildCommand_nullAllowedTools_excludesFlag() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);
        List<String> cmd = process.buildCommand();

        assertFalse(cmd.contains("--allowedTools"));
    }

    @Test
    @DisplayName("buildCommand() combines all options correctly")
    void buildCommand_allOptionsSet_combinesCorrectly() {
        CliConfig config = CliConfig.defaults("claude-opus-4")
            .withSessionId("s-999")
            .withContinueSession()
            .withMaxTurns(3)
            .withAllowedTools("Bash", "Edit");
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, config);
        List<String> cmd = process.buildCommand();

        // Base flags
        assertEquals(BINARY, cmd.get(0));
        assertTrue(cmd.contains("--output-format"));
        assertTrue(cmd.contains("--input-format"));
        assertTrue(cmd.contains("--verbose"));

        // All options present
        assertTrue(cmd.contains("--model"));
        assertTrue(cmd.contains("claude-opus-4"));
        assertTrue(cmd.contains("--resume"));
        assertTrue(cmd.contains("s-999"));
        assertTrue(cmd.contains("-c"));
        assertTrue(cmd.contains("--max-turns"));
        assertTrue(cmd.contains("3"));
        assertEquals(2, cmd.stream().filter("--allowedTools"::equals).count());
    }

    // --- isAlive tests ---

    @Test
    @DisplayName("isAlive() returns false when no process has been started")
    void isAlive_noProcessStarted_returnsFalse() {
        CliProcess process = new CliProcess(BINARY, API_KEY_ENV, defaultConfig);

        assertFalse(process.isAlive());
    }

    // --- escapeJsonString tests ---

    @Test
    @DisplayName("escapeJsonString() wraps simple text in double quotes")
    void escapeJsonString_simpleText_wrapsInQuotes() {
        String result = CliProcess.escapeJsonString("hello");

        assertEquals("\"hello\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() escapes double quotes")
    void escapeJsonString_doubleQuotes_escaped() {
        String result = CliProcess.escapeJsonString("say \"hello\"");

        assertEquals("\"say \\\"hello\\\"\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() escapes backslashes")
    void escapeJsonString_backslashes_escaped() {
        String result = CliProcess.escapeJsonString("path\\to\\file");

        assertEquals("\"path\\\\to\\\\file\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() escapes newlines")
    void escapeJsonString_newlines_escaped() {
        String result = CliProcess.escapeJsonString("line1\nline2");

        assertEquals("\"line1\\nline2\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() escapes carriage returns")
    void escapeJsonString_carriageReturns_escaped() {
        String result = CliProcess.escapeJsonString("line1\rline2");

        assertEquals("\"line1\\rline2\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() escapes tabs")
    void escapeJsonString_tabs_escaped() {
        String result = CliProcess.escapeJsonString("col1\tcol2");

        assertEquals("\"col1\\tcol2\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() escapes control characters as unicode")
    void escapeJsonString_controlChars_escapedAsUnicode() {
        // 0x01 (SOH) should be escaped as \u0001
        String result = CliProcess.escapeJsonString("a\u0001b");

        assertEquals("\"a\\u0001b\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() handles Japanese text correctly")
    void escapeJsonString_japaneseText_preservedCorrectly() {
        String result = CliProcess.escapeJsonString("Hello world");

        assertEquals("\"Hello world\"", result);

        // Multi-byte Japanese characters should pass through unchanged
        String japanese = CliProcess.escapeJsonString("日本語テスト");
        assertEquals("\"日本語テスト\"", japanese);
    }

    @Test
    @DisplayName("escapeJsonString() handles mixed content with special chars and Japanese")
    void escapeJsonString_mixedContent_handledCorrectly() {
        String result = CliProcess.escapeJsonString("Say \"こんにちは\"\nNew line");

        assertEquals("\"Say \\\"こんにちは\\\"\\nNew line\"", result);
    }

    @Test
    @DisplayName("escapeJsonString() handles empty string")
    void escapeJsonString_emptyString_returnsEmptyQuoted() {
        String result = CliProcess.escapeJsonString("");

        assertEquals("\"\"", result);
    }

    // --- stripAnsi tests ---

    @Test
    @DisplayName("stripAnsi() removes ANSI color codes")
    void stripAnsi_colorCodes_removed() {
        // ESC[31m = red, ESC[0m = reset
        String input = "\u001b[31mERROR\u001b[0m: something failed";
        String result = CliProcess.stripAnsi(input);

        assertEquals("ERROR: something failed", result);
    }

    @Test
    @DisplayName("stripAnsi() removes ANSI cursor movement codes")
    void stripAnsi_cursorCodes_removed() {
        // ESC[2J = clear screen, ESC[H = cursor home
        String input = "\u001b[2J\u001b[HHello";
        String result = CliProcess.stripAnsi(input);

        assertEquals("Hello", result);
    }

    @Test
    @DisplayName("stripAnsi() removes ANSI SGR with multiple parameters")
    void stripAnsi_sgrMultiParams_removed() {
        // ESC[1;32m = bold green
        String input = "\u001b[1;32mSUCCESS\u001b[0m";
        String result = CliProcess.stripAnsi(input);

        assertEquals("SUCCESS", result);
    }

    @Test
    @DisplayName("stripAnsi() preserves normal text without ANSI sequences")
    void stripAnsi_normalText_preserved() {
        String input = "This is plain text with no ANSI codes.";
        String result = CliProcess.stripAnsi(input);

        assertEquals(input, result);
    }

    @Test
    @DisplayName("stripAnsi() preserves empty string")
    void stripAnsi_emptyString_returnsEmpty() {
        assertEquals("", CliProcess.stripAnsi(""));
    }

    @Test
    @DisplayName("stripAnsi() preserves Japanese text")
    void stripAnsi_japaneseText_preserved() {
        String input = "日本語テスト";
        String result = CliProcess.stripAnsi(input);

        assertEquals(input, result);
    }

    @Test
    @DisplayName("stripAnsi() removes ANSI from text with Japanese characters")
    void stripAnsi_ansiWithJapanese_removesOnlyAnsi() {
        String input = "\u001b[33m警告\u001b[0m: テスト失敗";
        String result = CliProcess.stripAnsi(input);

        assertEquals("警告: テスト失敗", result);
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
