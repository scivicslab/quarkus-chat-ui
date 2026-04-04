package com.scivicslab.chatui.codex;

import com.scivicslab.chatui.cli.CliLlmProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

/**
 * LlmProvider implementation for the Codex CLI (OpenAI).
 */
public class CodexLlmProvider extends CliLlmProvider {

    private static final List<ModelEntry> MODELS = List.of(
        new ModelEntry("gpt-5.4", "codex", null),
        new ModelEntry("o4-mini", "codex", null),
        new ModelEntry("o3", "codex", null)
    );

    /**
     * Creates a new Codex LLM provider with the given configuration.
     *
     * @param allowedTools   comma-separated list of tools the CLI is allowed to use, if any
     * @param sessionFilePath path to the file used for persisting session state
     * @param httpPort        HTTP port of the running Quarkus application
     */
    public CodexLlmProvider(
            @ConfigProperty(name = "chat-ui.allowed-tools") Optional<String> allowedTools,
            @ConfigProperty(name = "chat-ui.session-file", defaultValue = ".chat-ui-session") String sessionFilePath,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090") int httpPort) {
        super("codex", "OPENAI_API_KEY", "gpt-5.4", allowedTools, sessionFilePath, httpPort);
    }

    /** {@inheritDoc} */
    @Override public String id() { return "codex"; }

    /** {@inheritDoc} */
    @Override public String displayName() { return "Codex (OpenAI)"; }

    /** {@inheritDoc} */
    @Override public List<ModelEntry> getAvailableModels() { return MODELS; }

    /**
     * Detects the OpenAI API key from the {@code OPENAI_API_KEY} environment variable.
     *
     * @return the API key value, or {@code null} if the variable is not set
     */
    @Override
    public String detectEnvApiKey() { return System.getenv("OPENAI_API_KEY"); }
}
