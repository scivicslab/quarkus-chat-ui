package com.scivicslab.chatui.core.mcp;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Actor that manages asynchronous MCP client requests to external servers.
 *
 * <p>Queues outgoing MCP requests and sends them asynchronously via HTTP.
 * Responses (success or error) are notified via SSE events to the user.</p>
 *
 * <p>This is a plain POJO actor — no CDI annotations, no synchronized blocks.
 * Thread safety is guaranteed by the actor's sequential message processing via ActorRef.</p>
 */
public class McpClientActor {

    private static final Logger LOG = Logger.getLogger(McpClientActor.class.getName());

    private final Queue<McpRequest> queue = new ArrayDeque<>();
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, String> sessionCache = new ConcurrentHashMap<>();
    private boolean processing = false;

    public McpClientActor() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Enqueues an MCP request for asynchronous delivery.
     *
     * @param serverUrl MCP server URL (e.g., http://localhost:28011)
     * @param toolName  Tool name to invoke (e.g., submitPrompt)
     * @param arguments JSON arguments for the tool
     * @param _caller   Caller identification
     * @param emitter   SSE emitter for notifications
     */
    public void sendMessage(String serverUrl, String toolName, String arguments,
                            String _caller, Consumer<ChatEvent> emitter) {
        queue.offer(new McpRequest(serverUrl, toolName, arguments, _caller, emitter));
        LOG.info("MCP request queued: " + toolName + " to " + serverUrl + " (queue size=" + queue.size() + ")");

        if (!processing) {
            processNext();
        }
    }

    /**
     * Processes the next request in the queue asynchronously.
     */
    private void processNext() {
        McpRequest req = queue.poll();
        if (req == null) {
            processing = false;
            return;
        }

        processing = true;
        LOG.info("Processing MCP request: " + req.toolName() + " to " + req.serverUrl());

        // Send HTTP request asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                String mcpEndpoint = ensureMcpPath(req.serverUrl());
                String sessionId = getOrCreateSession(mcpEndpoint);

                String jsonRequest = buildToolsCallRequest(req.toolName(), req.arguments(), req._caller());
                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(mcpEndpoint))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                        .build();

                HttpResponse<String> response = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    notifySuccess(req, response);
                } else {
                    notifyError(req, "HTTP " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                notifyError(req, e.getMessage());
            } finally {
                // Process next request
                processNext();
            }
        });
    }

    /**
     * Gets or creates an MCP session for the given endpoint.
     */
    private String getOrCreateSession(String mcpEndpoint) throws Exception {
        String cached = sessionCache.get(mcpEndpoint);
        if (cached != null) {
            return cached;
        }

        // Initialize session
        String initRequest = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "0.1.0",
                "clientInfo": {"name": "quarkus-chat-ui", "version": "1.0.0"}
              }
            }
            """;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(mcpEndpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initRequest))
                .build();

        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sessionId == null) {
            throw new RuntimeException("Failed to get session ID from initialize response");
        }

        sessionCache.put(mcpEndpoint, sessionId);
        LOG.info("MCP session initialized: " + sessionId + " for " + mcpEndpoint);
        return sessionId;
    }

    /**
     * Builds a tools/call JSON-RPC request.
     */
    private String buildToolsCallRequest(String toolName, String arguments, String _caller) {
        // Parse arguments JSON and inject _caller if needed
        String argsWithCaller = arguments;
        if (_caller != null && !_caller.isBlank() && !arguments.contains("_caller")) {
            // Simple injection - assumes arguments is a JSON object
            argsWithCaller = arguments.substring(0, arguments.length() - 1)
                    + ",\"_caller\":\"" + _caller + "\"}";
        }

        return String.format("""
            {
              "jsonrpc": "2.0",
              "id": 2,
              "method": "tools/call",
              "params": {
                "name": "%s",
                "arguments": %s
              }
            }
            """, toolName, argsWithCaller);
    }

    private String ensureMcpPath(String serverUrl) {
        return serverUrl.endsWith("/mcp") ? serverUrl :
                serverUrl.endsWith("/") ? serverUrl + "mcp" :
                        serverUrl + "/mcp";
    }

    private void notifySuccess(McpRequest req, HttpResponse<String> response) {
        req.emitter().accept(ChatEvent.info("[MCP to " + formatUrl(req.serverUrl()) + "] Response received"));
        LOG.info("MCP request succeeded: " + req.toolName() + " to " + req.serverUrl());
    }

    private void notifyError(McpRequest req, String errorMsg) {
        req.emitter().accept(ChatEvent.error("[MCP to " + formatUrl(req.serverUrl()) + "] Error: " + errorMsg));
        LOG.warning("MCP request failed: " + req.toolName() + " to " + req.serverUrl() + " - " + errorMsg);
    }

    private String formatUrl(String url) {
        try {
            URI uri = new URI(url);
            return uri.getAuthority();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Represents a queued MCP request.
     */
    public record McpRequest(
            String serverUrl,
            String toolName,
            String arguments,
            String _caller,
            Consumer<ChatEvent> emitter
    ) {}
}
