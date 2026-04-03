package com.scivicslab.coderagent.cli.process;

/**
 * Configuration record for CLI-based LLM subprocess invocation (claude, codex, etc.).
 */
public record CliConfig(
    String model,
    String systemPrompt,
    int maxTurns,
    String workingDir,
    String sessionId,
    boolean continueSession,
    String[] allowedTools
) {
    public static CliConfig defaults(String defaultModel) {
        return new CliConfig(defaultModel, null, 0, null, null, false, null);
    }

    public CliConfig withModel(String newModel) {
        return new CliConfig(newModel, systemPrompt, maxTurns, workingDir, sessionId, continueSession, allowedTools);
    }

    public CliConfig withSessionId(String newSessionId) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, newSessionId, continueSession, allowedTools);
    }

    public CliConfig withContinueSession() {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, true, allowedTools);
    }

    public CliConfig withMaxTurns(int newMaxTurns) {
        return new CliConfig(model, systemPrompt, newMaxTurns, workingDir, sessionId, continueSession, allowedTools);
    }

    public CliConfig withAllowedTools(String... newAllowedTools) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession, newAllowedTools);
    }
}
