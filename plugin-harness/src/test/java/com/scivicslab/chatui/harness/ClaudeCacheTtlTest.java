package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Claude Code writes its prompt cache for 5 minutes once the account is using extra usage, and only the first request of a
 * process for 1 hour. A conversation that waits longer than 5 minutes between turns then re-creates its whole context.
 * The process is therefore started with the CLI's own switch for the main conversation's cache lifetime.
 */
class ClaudeCacheTtlTest {

    @Test
    void theProcessIsStartedWithAOneHourPromptCache() {
        CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet"));
        var launch = process.launch(process.buildCommand());
        assertEquals("1h", launch.addedEnv().get("CLAUDE_CODE_PROMPT_CACHE_TTL"));
    }

    @Test
    void theOneHourCacheDoesNotDependOnTheWindowChoice() {
        CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", CliConfig.defaults("sonnet[200k]"));
        var launch = process.launch(process.buildCommand());
        assertEquals("1h", launch.addedEnv().get("CLAUDE_CODE_PROMPT_CACHE_TTL"));
    }
}
