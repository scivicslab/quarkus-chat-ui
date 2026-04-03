package com.scivicslab.coderagent.core;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * On startup, registers this agent instance with the MCP Gateway
 * so that the gateway can aggregate /api/history from all agents.
 */
@ApplicationScoped
public class GatewayRegistrar {

    private static final Logger logger = Logger.getLogger(GatewayRegistrar.class.getName());

    @ConfigProperty(name = "coder-agent.gateway-url")
    Optional<String> gatewayUrl;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090")
    int httpPort;

    @ConfigProperty(name = "coder-agent.title", defaultValue = "Coder Agent")
    String title;

    void onStart(@Observes StartupEvent event) {
        if (gatewayUrl.isEmpty() || gatewayUrl.get().isBlank()) {
            logger.fine("No gateway URL configured, skipping registration");
            return;
        }

        String name = "coder-agent-" + httpPort;
        String url = "http://localhost:" + httpPort;
        String body = "{\"name\":\"" + name + "\",\"url\":\"" + url
                + "\",\"description\":\"" + title + " (port " + httpPort + ")\"}";

        Thread.startVirtualThread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3)).build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(gatewayUrl.get() + "/api/servers"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                logger.info("Registered with gateway: " + name + " -> HTTP " + response.statusCode());
            } catch (Exception e) {
                logger.warning("Failed to register with gateway: " + e.getMessage());
            }
        });
    }
}
