package com.scivicslab.coderagent.codex;

import com.scivicslab.coderagent.cli.CliLlmProvider;
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

    public CodexLlmProvider(
            @ConfigProperty(name = "coder-agent.allowed-tools") Optional<String> allowedTools,
            @ConfigProperty(name = "coder-agent.session-file", defaultValue = ".coder-agent-session") String sessionFilePath,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090") int httpPort) {
        super("codex", "OPENAI_API_KEY", "gpt-5.4", allowedTools, sessionFilePath, httpPort);
    }

    @Override public String id() { return "codex"; }
    @Override public String displayName() { return "Codex (OpenAI)"; }
    @Override public List<ModelEntry> getAvailableModels() { return MODELS; }

    @Override
    public String detectEnvApiKey() { return System.getenv("OPENAI_API_KEY"); }
}
