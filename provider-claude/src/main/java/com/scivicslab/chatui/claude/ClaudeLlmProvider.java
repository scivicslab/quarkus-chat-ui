package com.scivicslab.chatui.claude;

import com.scivicslab.chatui.cli.CliLlmProvider;
import com.scivicslab.chatui.core.provider.LlmProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

/**
 * LlmProvider implementation for the Claude Code CLI (Anthropic).
 *
 * <p>Claude Code CLI in {@code stream-json} mode requires {@code --permission-mode}
 * to auto-approve tool operations. Without it, all permissions are silently denied.
 * This provider defaults to {@code bypassPermissions} so all tools (including
 * Bash/curl for MCP communication) are auto-approved.</p>
 *
 * <p><b>Warning:</b> {@code acceptEdits} only covers file operations
 * (Read, Write, Edit, Glob, Grep) and silently denies Bash commands.
 * See {@code PermissionModePostmortem_260404_oo01} for details.</p>
 */
public class ClaudeLlmProvider extends CliLlmProvider {

    /** Default permission mode for Claude Code CLI in stream-json mode.
     *  bypassPermissions auto-approves ALL tools including Bash/curl.
     *  acceptEdits does NOT cover Bash — do not use as default. */
    private static final String DEFAULT_PERMISSION_MODE = "bypassPermissions";

    private static final List<ModelEntry> MODELS = List.of(
        new ModelEntry("claude-opus-4-6", "claude", null),
        new ModelEntry("claude-sonnet-4-6", "claude", null),
        new ModelEntry("claude-haiku-4-5-20251001", "claude", null)
    );

    /**
     * Creates a new Claude LLM provider with the given configuration.
     *
     * <p>If {@code permissionMode} is not configured, defaults to
     * {@value #DEFAULT_PERMISSION_MODE} to ensure all tools work.</p>
     *
     * @param allowedTools    comma-separated list of tools the CLI is allowed to use, if any
     * @param permissionMode  CLI permission mode override; defaults to bypassPermissions
     * @param sessionFilePath path to the file used for persisting session state
     * @param httpPort        HTTP port of the running Quarkus application
     */
    public ClaudeLlmProvider(
            @ConfigProperty(name = "chat-ui.allowed-tools") Optional<String> allowedTools,
            @ConfigProperty(name = "chat-ui.permission-mode") Optional<String> permissionMode,
            @ConfigProperty(name = "chat-ui.session-file", defaultValue = ".chat-ui-session") String sessionFilePath,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090") int httpPort) {
        super("claude", "ANTHROPIC_API_KEY", "claude-sonnet-4-6", allowedTools,
              permissionMode.or(() -> Optional.of(DEFAULT_PERMISSION_MODE)),
              sessionFilePath, httpPort);
    }

    /** {@inheritDoc} */
    @Override public String id() { return "claude"; }

    /** {@inheritDoc} */
    @Override public String displayName() { return "Claude (Anthropic)"; }

    /** {@inheritDoc} */
    @Override public List<ModelEntry> getAvailableModels() { return MODELS; }

    /**
     * Detects the Anthropic API key from the {@code ANTHROPIC_API_KEY} environment variable.
     *
     * @return the API key value, or {@code null} if the variable is not set
     */
    @Override
    public String detectEnvApiKey() { return System.getenv("ANTHROPIC_API_KEY"); }
}
