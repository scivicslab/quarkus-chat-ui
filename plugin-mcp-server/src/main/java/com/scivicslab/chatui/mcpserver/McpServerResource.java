package com.scivicslab.chatui.mcpserver;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.rest.ChatEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * MCP server endpoint for quarkus-chat-ui (Streamable HTTP Transport).
 *
 * <p>Exposes each configured LLM model as one MCP tool. {@code tools/call} submits
 * the prompt to {@link com.scivicslab.chatui.core.actor.QueueActor} with
 * {@code source="agent:xxx"} and a {@code resultKey} UUID, then blocks on a
 * {@link CompletableFuture} until {@link ChatActor} stores the completed result.</p>
 *
 * <p>Enabled with {@code chat-ui.mcp-server.enabled=true} (default: false).
 * The CDI {@code @ApplicationScoped} bean is always instantiated; the endpoint
 * returns HTTP 503 when disabled so that the JAX-RS path is always registered.</p>
 *
 * <p>Supported JSON-RPC methods:</p>
 * <ul>
 *   <li>{@code initialize} — issues a session ID in {@code Mcp-Session-Id} response header</li>
 *   <li>{@code tools/list} — returns one tool per configured model</li>
 *   <li>{@code tools/call} — enqueues prompt, blocks until result is ready</li>
 * </ul>
 */
@ApplicationScoped
@Path("/mcp")
public class McpServerResource {

    private static final Logger LOG = Logger.getLogger(McpServerResource.class.getName());

    @Inject
    ChatUiActorSystem actorSystem;

    @ConfigProperty(name = "chat-ui.mcp-server.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "chat-ui.agent-loop.mcp-timeout", defaultValue = "120")
    int mcpTimeoutSeconds;

    // session ID → true (lightweight session registry)
    private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handle(@Context HttpHeaders headers, String body) {
        if (!enabled) {
            return Response.status(503).entity("{\"error\":\"MCP server is disabled\"}").build();
        }

        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            return jsonRpcError(null, -32700, "Parse error");
        }

        Object id = req.opt("id");
        String method = req.optString("method", "");

        return switch (method) {
            case "initialize"  -> handleInitialize(id);
            case "tools/list"  -> handleToolsList(id, headers);
            case "tools/call"  -> handleToolsCall(id, headers, req);
            default            -> jsonRpcError(id, -32601, "Method not found: " + method);
        };
    }

    // ── initialize ───────────────────────────────────────────────────────────

    private Response handleInitialize(Object id) {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, true);

        JSONObject result = new JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                .put("serverInfo", new JSONObject()
                        .put("name", "quarkus-chat-ui")
                        .put("version", "1.0"));

        return Response.ok(jsonRpcResult(id, result))
                .header("Mcp-Session-Id", sessionId)
                .build();
    }

    // ── tools/list ───────────────────────────────────────────────────────────

    private Response handleToolsList(Object id, HttpHeaders headers) {
        if (!isValidSession(headers)) {
            return jsonRpcError(id, -32000, "Invalid or missing Mcp-Session-Id");
        }

        var chatRef = actorSystem.getChatActor();
        var models  = chatRef.ask(ChatActor::getAvailableModels).join();

        JSONArray tools = new JSONArray();
        for (var model : models) {
            tools.put(new JSONObject()
                    .put("name", model.name())
                    .put("description", "Ask " + model.name())
                    .put("inputSchema", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("prompt", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("prompt"))));
        }

        return Response.ok(jsonRpcResult(id, new JSONObject().put("tools", tools))).build();
    }

    // ── tools/call ───────────────────────────────────────────────────────────

    private Response handleToolsCall(Object id, HttpHeaders headers, JSONObject req) {
        if (!isValidSession(headers)) {
            return jsonRpcError(id, -32000, "Invalid or missing Mcp-Session-Id");
        }

        JSONObject params = req.optJSONObject("params");
        if (params == null) {
            return jsonRpcError(id, -32602, "Missing params");
        }
        String toolName = params.optString("name", "");
        JSONObject arguments = params.optJSONObject("arguments");
        String prompt = arguments != null ? arguments.optString("prompt", "") : "";
        if (prompt.isBlank()) {
            return jsonRpcError(id, -32602, "Missing arguments.prompt");
        }

        // Derive caller label from Mcp-Session-Id header (best available identifier)
        String sessionId   = headers.getHeaderString("Mcp-Session-Id");
        String callerLabel = sessionId != null ? "session:" + sessionId.substring(0, 8) : "unknown";
        String source      = "agent:" + callerLabel;
        String resultKey   = UUID.randomUUID().toString();

        var chatRef  = actorSystem.getChatActor();
        var queueRef = actorSystem.getQueueActor();

        // Register the key before enqueuing so getResultStatus() returns "processing" immediately
        chatRef.tell(a -> a.registerPendingResultKey(resultKey));

        // Create the done future externally so this thread can block on it
        CompletableFuture<Void> done = new CompletableFuture<>();

        queueRef.tell(q -> q.enqueue(
                prompt,
                toolName,
                "queue",
                event -> {},          // discard SSE events; result is captured via resultKey
                chatRef,
                source,
                resultKey,
                done));

        LOG.info("MCP tools/call: tool=" + toolName + " resultKey=" + resultKey
                + " source=" + source);

        // Block the virtual thread until ChatActor stores the completed result
        try {
            done.get(mcpTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warning("MCP tools/call timed out after " + mcpTimeoutSeconds + "s: resultKey=" + resultKey);
            return jsonRpcError(id, -32000,
                    "LLM did not respond within " + mcpTimeoutSeconds + " seconds");
        } catch (Exception e) {
            LOG.warning("MCP tools/call interrupted: " + e.getMessage());
            return jsonRpcError(id, -32000, "Agent loop error: " + e.getMessage());
        }

        String resultText = chatRef.ask(a -> a.getCompletedResult(resultKey)).join();
        if (resultText == null) {
            return jsonRpcError(id, -32000, "Result not found for key: " + resultKey);
        }

        JSONObject content = new JSONObject()
                .put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", resultText)));

        return Response.ok(jsonRpcResult(id, content)).build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isValidSession(HttpHeaders headers) {
        String sid = headers.getHeaderString("Mcp-Session-Id");
        return sid != null && sessions.containsKey(sid);
    }

    private static String jsonRpcResult(Object id, JSONObject result) {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString();
    }

    private static Response jsonRpcError(Object id, int code, String message) {
        String body = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message))
                .toString();
        return Response.ok(body).build(); // JSON-RPC errors still return HTTP 200
    }
}
