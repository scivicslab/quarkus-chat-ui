package com.scivicslab.coderagent.claude;

import com.scivicslab.coderagent.cli.CliLlmProvider;
import com.scivicslab.coderagent.core.provider.LlmProvider;
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

    public ClaudeLlmProvider(
            @ConfigProperty(name = "coder-agent.allowed-tools") Optional<String> allowedTools,
            @ConfigProperty(name = "coder-agent.session-file", defaultValue = ".coder-agent-session") String sessionFilePath,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090") int httpPort) {
        super("claude", "ANTHROPIC_API_KEY", "claude-sonnet-4-5", allowedTools, sessionFilePath, httpPort);
    }

    @Override public String id() { return "claude"; }
    @Override public String displayName() { return "Claude (Anthropic)"; }
    @Override public List<ModelEntry> getAvailableModels() { return MODELS; }

    @Override
    public String detectEnvApiKey() { return System.getenv("ANTHROPIC_API_KEY"); }
}
