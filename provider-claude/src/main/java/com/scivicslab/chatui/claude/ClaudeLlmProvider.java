package com.scivicslab.chatui.claude;

import com.scivicslab.chatui.cli.CliLlmProvider;
import com.scivicslab.chatui.core.provider.LlmProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

/**
 * LlmProvider implementation for the Claude CLI (Anthropic).
 */
public class ClaudeLlmProvider extends CliLlmProvider {

    private static final List<ModelEntry> MODELS = List.of(
        new ModelEntry("claude-opus-4-5", "claude", null),
        new ModelEntry("claude-sonnet-4-5", "claude", null),
        new ModelEntry("claude-haiku-4-5", "claude", null)
    );

    /**
     * Creates a new Claude LLM provider with the given configuration.
     *
     * @param allowedTools   comma-separated list of tools the CLI is allowed to use, if any
     * @param sessionFilePath path to the file used for persisting session state
     * @param httpPort        HTTP port of the running Quarkus application
     */
    public ClaudeLlmProvider(
            @ConfigProperty(name = "chat-ui.allowed-tools") Optional<String> allowedTools,
            @ConfigProperty(name = "chat-ui.session-file", defaultValue = ".chat-ui-session") String sessionFilePath,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090") int httpPort) {
        super("claude", "ANTHROPIC_API_KEY", "claude-sonnet-4-5", allowedTools, sessionFilePath, httpPort);
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
