package com.scivicslab.chatui.app;

import com.scivicslab.chatui.claude.ClaudeLlmProvider;
import com.scivicslab.chatui.codex.CodexLlmProvider;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * CDI producer that selects and instantiates the active {@link LlmProvider}
 * based on the {@code chat-ui.provider} configuration property.
 *
 * <p>Valid values: {@code claude} (default), {@code codex}, {@code openai-compat}</p>
 */
@ApplicationScoped
public class LlmProviderProducer {

    private static final Logger LOG = Logger.getLogger(LlmProviderProducer.class.getName());

    @ConfigProperty(name = "chat-ui.provider", defaultValue = "claude")
    String providerName;

    @ConfigProperty(name = "chat-ui.allowed-tools")
    Optional<String> allowedTools;

    @ConfigProperty(name = "chat-ui.permission-mode")
    Optional<String> permissionMode;

    @ConfigProperty(name = "chat-ui.session-file", defaultValue = ".chat-ui-session")
    String sessionFilePath;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090")
    int httpPort;

    @ConfigProperty(name = "chat-ui.servers", defaultValue = "http://localhost:8000")
    String servers;

    @ConfigProperty(name = "chat-ui.default-model")
    Optional<String> defaultModel;

    /**
     * Produces the active {@link LlmProvider} bean based on the {@code chat-ui.provider}
     * configuration property. Supported values are {@code claude}, {@code codex},
     * and {@code openai-compat}.
     *
     * @return the configured LLM provider instance
     * @throws IllegalStateException if the configured provider name is not recognized
     */
    @Produces
    @ApplicationScoped
    public LlmProvider produce() {
        LOG.info("Initializing LLM provider: " + providerName);
        return switch (providerName.toLowerCase().trim()) {
            case "claude" -> new ClaudeLlmProvider(allowedTools, permissionMode, sessionFilePath, httpPort);
            case "codex" -> new CodexLlmProvider(allowedTools, permissionMode, sessionFilePath, httpPort);
            case "openai-compat" -> {
                List<String> urls = Arrays.stream(servers.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
                String model = defaultModel.filter(s -> !s.isBlank()).orElse("default");
                yield new OpenAiCompatProvider(urls, model);
            }
            default -> throw new IllegalStateException(
                "Unknown provider: '" + providerName + "'. Valid values: claude, codex, openai-compat");
        };
    }
}
