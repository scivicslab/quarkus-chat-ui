package com.scivicslab.coderagent.app;

import com.scivicslab.coderagent.claude.ClaudeLlmProvider;
import com.scivicslab.coderagent.codex.CodexLlmProvider;
import com.scivicslab.coderagent.core.provider.LlmProvider;
import com.scivicslab.coderagent.openaicompat.OpenAiCompatProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * CDI producer that selects and instantiates the active {@link LlmProvider}
 * based on the {@code coder-agent.provider} configuration property.
 *
 * <p>Valid values: {@code claude} (default), {@code codex}, {@code openai-compat}</p>
 */
@ApplicationScoped
public class LlmProviderProducer {

    private static final Logger LOG = Logger.getLogger(LlmProviderProducer.class.getName());

    @ConfigProperty(name = "coder-agent.provider", defaultValue = "claude")
    String providerName;

    @ConfigProperty(name = "coder-agent.allowed-tools")
    Optional<String> allowedTools;

    @ConfigProperty(name = "coder-agent.session-file", defaultValue = ".coder-agent-session")
    String sessionFilePath;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090")
    int httpPort;

    @ConfigProperty(name = "coder-agent.servers", defaultValue = "http://localhost:8000")
    String servers;

    @ConfigProperty(name = "coder-agent.default-model", defaultValue = "")
    String defaultModel;

    @Produces
    @ApplicationScoped
    public LlmProvider produce() {
        LOG.info("Initializing LLM provider: " + providerName);
        return switch (providerName.toLowerCase().trim()) {
            case "claude" -> new ClaudeLlmProvider(allowedTools, sessionFilePath, httpPort);
            case "codex" -> new CodexLlmProvider(allowedTools, sessionFilePath, httpPort);
            case "openai-compat" -> {
                List<String> urls = Arrays.stream(servers.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
                String model = defaultModel.isBlank() ? "default" : defaultModel;
                yield new OpenAiCompatProvider(urls, model);
            }
            default -> throw new IllegalStateException(
                "Unknown provider: '" + providerName + "'. Valid values: claude, codex, openai-compat");
        };
    }
}
