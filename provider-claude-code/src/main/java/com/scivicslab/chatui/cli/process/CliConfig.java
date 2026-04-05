package com.scivicslab.chatui.cli.process;

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
    String[] allowedTools,
    String permissionMode
) {
    /**
     * Creates a default configuration with the given model and all other fields unset.
     * Permission mode and allowed tools are {@code null} (unset) by default.
     *
     * <p>Each CLI provider subclass (e.g. {@code ClaudeLlmProvider}, {@code CodexLlmProvider})
     * is responsible for setting its own CLI-specific defaults.
     * See {@code PermissionModePostmortem_260404_oo01} for why generic defaults are dangerous.</p>
     *
     * @param defaultModel the model identifier to use
     * @return a new {@code CliConfig} with sensible defaults
     */
    public static CliConfig defaults(String defaultModel) {
        return new CliConfig(defaultModel, null, 0, null, null, false, null, null);
    }

    /**
     * Returns a copy of this configuration with a different model.
     *
     * @param newModel the new model identifier
     * @return a new {@code CliConfig} with the updated model
     */
    public CliConfig withModel(String newModel) {
        return new CliConfig(newModel, systemPrompt, maxTurns, workingDir, sessionId, continueSession, allowedTools, permissionMode);
    }

    /**
     * Returns a copy of this configuration with a different session ID.
     *
     * @param newSessionId the new session ID, or {@code null} to clear
     * @return a new {@code CliConfig} with the updated session ID
     */
    public CliConfig withSessionId(String newSessionId) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, newSessionId, continueSession, allowedTools, permissionMode);
    }

    /**
     * Returns a copy of this configuration with the continue-session flag enabled.
     *
     * @return a new {@code CliConfig} with {@code continueSession} set to {@code true}
     */
    public CliConfig withContinueSession() {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, true, allowedTools, permissionMode);
    }

    /**
     * Returns a copy of this configuration with a different max-turns limit.
     *
     * @param newMaxTurns the maximum number of agentic turns (0 for unlimited)
     * @return a new {@code CliConfig} with the updated max turns
     */
    public CliConfig withMaxTurns(int newMaxTurns) {
        return new CliConfig(model, systemPrompt, newMaxTurns, workingDir, sessionId, continueSession, allowedTools, permissionMode);
    }

    /**
     * Returns a copy of this configuration with the specified allowed tools.
     *
     * @param newAllowedTools the tool names the CLI process is permitted to use
     * @return a new {@code CliConfig} with the updated allowed tools
     */
    public CliConfig withAllowedTools(String... newAllowedTools) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession, newAllowedTools, permissionMode);
    }

    /**
     * Returns a copy of this configuration with the specified permission mode.
     *
     * @param newPermissionMode the CLI permission mode (acceptEdits, default, auto, bypassPermissions, plan)
     * @return a new {@code CliConfig} with the updated permission mode
     */
    public CliConfig withPermissionMode(String newPermissionMode) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession, allowedTools, newPermissionMode);
    }
}
