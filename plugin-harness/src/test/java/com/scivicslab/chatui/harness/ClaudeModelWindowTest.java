package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.LlmProvider.ModelEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window of a Claude Code conversation is part of its model value: {@code sonnet} and {@code sonnet[1m]} leave the CLI on
 * its 1M window, {@code sonnet[200k]} starts the process with 1M context turned off and asks the CLI for {@code sonnet}. The
 * measurements behind the mapping are in {@link ClaudeModelChoice}.
 */
class ClaudeModelWindowTest {

    @Test
    void theValueIsSplitIntoTheModelTheCliGetsAndTheWindowChosen() {
        assertEquals(new ClaudeModelChoice("sonnet", "sonnet", false), ClaudeModelChoice.parse("sonnet"));
        assertEquals(new ClaudeModelChoice("sonnet[1m]", "sonnet[1m]", false), ClaudeModelChoice.parse("sonnet[1m]"));
        assertEquals(new ClaudeModelChoice("claude-sonnet-5-5", "claude-sonnet-5-5", false), ClaudeModelChoice.parse("claude-sonnet-5-5"));
        assertEquals(new ClaudeModelChoice("sonnet[200k]", "sonnet", true), ClaudeModelChoice.parse("sonnet[200k]"));
        assertEquals(new ClaudeModelChoice("claude-opus-5-5[200K]", "claude-opus-5-5", true), ClaudeModelChoice.parse("claude-opus-5-5[200K]"));
        assertEquals(new ClaudeModelChoice(null, null, false), ClaudeModelChoice.parse(null));
        assertEquals(null, ClaudeModelChoice.parse("[200k]").cliModel(), "a suffix alone names no model");
    }

    @Test
    void aChangeOfModelOrWindowNeedsTheProcessStartedAgain() {
        assertFalse(ClaudeModelChoice.needsRestart("sonnet", "sonnet"));
        assertTrue(ClaudeModelChoice.needsRestart("sonnet", "sonnet[200k]"));
        assertTrue(ClaudeModelChoice.needsRestart("sonnet[200k]", "sonnet"));
        assertTrue(ClaudeModelChoice.needsRestart("sonnet", "opus"));
        assertFalse(ClaudeModelChoice.needsRestart("sonnet[200k]", "sonnet[200K]"));
    }

    @Test
    void theModelListOffersEveryFamilyWithItsWindowInTheLabel() {
        List<ModelEntry> entries = ClaudeModelChoice.entries("claude");
        assertEquals(8, entries.size());
        assertEquals(new ModelEntry("sonnet", "claude", null, "sonnet (1M)"), entries.get(4));
        assertEquals(new ModelEntry("sonnet[200k]", "claude", null, "sonnet (200K)"), entries.get(5));
        assertEquals(List.of("fable", "fable[200k]", "opus", "opus[200k]", "sonnet", "sonnet[200k]", "haiku", "haiku[200k]"),
                entries.stream().map(ModelEntry::name).toList());
    }

    @Test
    void theCommandLineNeverCarriesTheSuffix(@TempDir Path dir) {
        List<String> cmd = new CliProcess("claude", "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet[200k]")).buildCommand();
        assertEquals("sonnet", cmd.get(cmd.indexOf("--model") + 1));
        assertFalse(String.join(" ", cmd).contains("[200k]"));
        List<String> oneM = new CliProcess("claude", "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet[1m]")).buildCommand();
        assertEquals("sonnet[1m]", oneM.get(oneM.indexOf("--model") + 1));
    }

    @Test
    void theEnvironmentTurnsOneMillionOffForTheConversationThatChoseTwoHundredK() {
        CliProcess chosen = new CliProcess("claude", "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet[200k]"));
        HarnessLaunchView a = new HarnessLaunchView(chosen);
        assertEquals("1", a.added.get(ClaudeModelChoice.DISABLE_1M_ENV));
        assertFalse(a.removed.contains(ClaudeModelChoice.DISABLE_1M_ENV));

        CliProcess standard = new CliProcess("claude", "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet"));
        HarnessLaunchView b = new HarnessLaunchView(standard);
        assertFalse(b.added.containsKey(ClaudeModelChoice.DISABLE_1M_ENV));
        assertTrue(b.removed.contains(ClaudeModelChoice.DISABLE_1M_ENV),
                "a variable this program inherited must not decide a conversation's window");
    }

    /** The environment {@link CliProcess#launch} makes for a process's own options. */
    private static final class HarnessLaunchView {
        final java.util.Map<String, String> added;
        final List<String> removed;
        HarnessLaunchView(CliProcess p) {
            var launch = p.launch(p.buildCommand());
            added = launch.addedEnv();
            removed = launch.removedEnv();
        }
    }

    /** One real process per start: the script notes the model and the switch it was started with, then answers each prompt. */
    @Test
    void aChangedWindowStartsTheProcessAgainBeforeTheNextPrompt_andNotBefore(@TempDir Path dir) throws Exception {
        Path starts = dir.resolve("starts.txt");
        Path script = dir.resolve("fake-claude");
        Files.writeString(script, "#!/bin/bash\n"
                + "echo \"args=$* disable=${CLAUDE_CODE_DISABLE_1M_CONTEXT:-unset}\" >> '" + starts + "'\n"
                + "while read line; do\n"
                + "  echo '{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\",\"session_id\":\"s1\"}'\n"
                + "done\n");
        assertTrue(script.toFile().setExecutable(true));
        CliProcess process = new CliProcess(script.toString(), "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet").withWorkingDir(dir.toString()));
        try {
            process.sendPrompt("one", new CopyOnWriteArrayList<>(), event -> { });
            process.sendPrompt("two", new CopyOnWriteArrayList<>(), event -> { });
            assertEquals(1, Files.readAllLines(starts).size(), "the same model keeps the process");

            process.updateConfig(c -> c.withModel("sonnet[200k]"));
            process.sendPrompt("three", new CopyOnWriteArrayList<>(), event -> { });
            process.sendPrompt("four", new CopyOnWriteArrayList<>(), event -> { });
            process.updateConfig(c -> c.withModel("sonnet"));
            process.sendPrompt("five", new CopyOnWriteArrayList<>(), event -> { });
            List<String> seen = Files.readAllLines(starts);
            assertEquals(3, seen.size(), seen.toString());
            assertTrue(seen.get(0).contains("--model sonnet ") && seen.get(0).contains("disable=unset"), seen.get(0));
            assertTrue(seen.get(1).contains("--model sonnet ") && !seen.get(1).contains("[200k]") && seen.get(1).contains("disable=1"),
                    "200K: the model without the suffix, 1M off: " + seen.get(1));
            assertTrue(seen.get(2).contains("disable=unset"), "back to the default window: " + seen.get(2));
        } finally {
            process.cancel();
        }
    }
}
