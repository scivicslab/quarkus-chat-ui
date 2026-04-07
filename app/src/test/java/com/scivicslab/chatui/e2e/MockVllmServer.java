package com.scivicslab.chatui.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal in-process mock vLLM server for E2E smoke tests.
 *
 * <p>Uses the JDK built-in {@link HttpServer} — no additional dependencies.
 * Responds to the two OpenAI-compatible endpoints that quarkus-chat-ui requires:
 * <ul>
 *   <li>GET  /v1/models             — returns a single test model</li>
 *   <li>POST /v1/chat/completions   — returns a minimal non-streaming completion</li>
 * </ul>
 *
 * <p>Typical usage in an IT test:
 * <pre>
 *   private static MockVllmServer mockVllm;
 *
 *   {@literal @}BeforeAll
 *   static void startMock() throws IOException {
 *       mockVllm = new MockVllmServer(19080);
 *       mockVllm.start();
 *   }
 *
 *   {@literal @}AfterAll
 *   static void stopMock() { mockVllm.stop(); }
 * </pre>
 */
class MockVllmServer {

    private static final byte[] MODELS_RESPONSE =
            "{\"object\":\"list\",\"data\":[{\"id\":\"test-model\",\"object\":\"model\",\"created\":0,\"owned_by\":\"mock\"}]}"
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] COMPLETION_RESPONSE =
            ("{\"id\":\"mock-completion\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"test-model\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Mock response.\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;

    MockVllmServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/models", exchange -> {
            sendJson(exchange, MODELS_RESPONSE);
        });
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, COMPLETION_RESPONSE);
        });
        server.setExecutor(null);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    private static void sendJson(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
