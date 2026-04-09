package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.openaicompat.AgentLoopExtension;
import com.scivicslab.chatui.openaicompat.ToolDefinition;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.OpenAiCompatClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Optional;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Agent-loop plugin for the openai-compat provider.
 *
 * <p>When active ({@code chat-ui.agent-loop.enabled=true}), this bean intercepts
 * {@code OpenAiCompatProvider.sendPrompt} and runs a tool-calling loop:</p>
 * <ol>
 *   <li>Fetch tool definitions from configured MCP servers</li>
 *   <li>Send a non-streaming request with tools to the LLM</li>
 *   <li>If the model returns {@code tool_calls}, execute each via MCP and loop</li>
 *   <li>When {@code finish_reason=stop}, emit the final answer and complete</li>
 * </ol>
 *
 * <p>Required config:
 * <pre>
 *   chat-ui.agent-loop.enabled=true
 *   chat-ui.agent-loop.mcp-urls=http://localhost:9000
 * </pre>
 * Optional:
 * <pre>
 *   chat-ui.agent-loop.max-iterations=10
 * </pre>
 * </p>
 */
@ApplicationScoped
public class AgentLoopExtensionImpl implements AgentLoopExtension {

    private static final Logger LOG = Logger.getLogger(AgentLoopExtensionImpl.class.getName());

    @ConfigProperty(name = "chat-ui.agent-loop.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "chat-ui.agent-loop.mcp-urls")
    Optional<String> mcpUrlsRaw;

    @ConfigProperty(name = "chat-ui.agent-loop.max-iterations", defaultValue = "10")
    int maxIterations;

    private List<OpenAiCompatClient> clients = List.of();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ConcurrentHashMap<String, String> sessionCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> toolToServerUrl = new ConcurrentHashMap<>();

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void initialize(List<OpenAiCompatClient> clients) {
        this.clients = List.copyOf(clients);
        LOG.info("AgentLoop initialized with " + clients.size() + " client(s), "
                + "mcp-urls=" + mcpUrlsRaw.orElse("") + ", max-iterations=" + maxIterations);
    }

    @Override
    public void runAgentLoop(String model, LinkedList<ChatMessage> history,
                             Consumer<ChatEvent> emitter, ProviderContext ctx) {
        List<ToolDefinition> tools = fetchAllTools();
        OpenAiCompatClient client = selectClient(model);
        if (client == null) {
            emitter.accept(ChatEvent.error("No server available for model: " + model));
            return;
        }

        long startTime = System.currentTimeMillis();

        if (tools.isEmpty()) {
            LOG.info("No MCP tools available — running single-shot request");
            runSingleShot(client, model, history, emitter, ctx, startTime);
            return;
        }

        LOG.info("Starting agent loop: model=" + model + ", tools=" + tools.size()
                + ", maxIter=" + maxIterations);

        for (int iter = 0; iter < maxIterations; iter++) {
            OpenAiCompatClient.NonStreamingResponse resp =
                    client.sendNonStreaming(model, List.copyOf(history), ctx.noThink(), 0, tools);

            if (resp.finishReason().startsWith("error")) {
                String detail = resp.content() != null ? resp.content() : resp.finishReason();
                LOG.warning("Agent loop error: " + detail + " (model=" + model + ")");
                emitter.accept(ChatEvent.error("Agent loop error: " + detail));
                return;
            }

            if (resp.toolCalls().isEmpty()) {
                // Final answer — stream as single delta
                String content = resp.content() != null ? resp.content() : "";
                history.addLast(new ChatMessage.Assistant(content));
                emitter.accept(ChatEvent.delta(content));
                emitter.accept(ChatEvent.result(null, 0.0, System.currentTimeMillis() - startTime, model, false));
                LOG.info("Agent loop complete after " + (iter + 1) + " iteration(s)");
                return;
            }

            // Add tool_calls message to history
            history.addLast(new ChatMessage.ToolCallRequest(resp.toolCalls()));

            // Execute each tool call sequentially
            for (ChatMessage.ToolCallRequest.ToolCall tc : resp.toolCalls()) {
                String shortArgs = tc.arguments().length() > 120
                        ? tc.arguments().substring(0, 120) + "…"
                        : tc.arguments();
                emitter.accept(ChatEvent.thinking("[Tool] " + tc.name() + "(" + shortArgs + ")"));
                if (ctx.onActivity() != null) ctx.onActivity().run();

                String result = callMcpTool(tc.name(), tc.arguments());
                String shortResult = result.length() > 300 ? result.substring(0, 300) + "…" : result;
                emitter.accept(ChatEvent.thinking("[Result] " + shortResult));

                // Truncate large results to avoid overflowing the model's context window.
                // Binary files (e.g. PDFs) returned by the filesystem tool would corrupt
                // the JSON request body and cause vLLM to return an error.
                String historyResult = result.length() > 10_000
                        ? result.substring(0, 10_000) + "\n[truncated — content too large]"
                        : result;
                history.addLast(new ChatMessage.ToolResult(tc.id(), tc.name(), historyResult));
            }
        }

        // Max iterations reached without a final answer
        LOG.warning("Agent loop hit max-iterations (" + maxIterations + ") for model=" + model);
        emitter.accept(ChatEvent.thinking("Tool call limit (" + maxIterations + " iterations) reached."));
        String limitMsg = "Maximum tool call iterations reached without a final answer.";
        history.addLast(new ChatMessage.Assistant(limitMsg));
        emitter.accept(ChatEvent.delta(limitMsg));
        emitter.accept(ChatEvent.result(null, 0.0, System.currentTimeMillis() - startTime, model, false));
    }

    // ------------------------------------------------------------------ //
    // Single-shot fallback (no tools)                                     //
    // ------------------------------------------------------------------ //

    private void runSingleShot(OpenAiCompatClient client, String model,
                               LinkedList<ChatMessage> history, Consumer<ChatEvent> emitter,
                               ProviderContext ctx, long startTime) {
        OpenAiCompatClient.NonStreamingResponse resp =
                client.sendNonStreaming(model, List.copyOf(history), ctx.noThink(), 0, List.of());
        String content = resp.content() != null ? resp.content() : "";
        history.addLast(new ChatMessage.Assistant(content));
        emitter.accept(ChatEvent.delta(content));
        emitter.accept(ChatEvent.result(null, 0.0, System.currentTimeMillis() - startTime, model, false));
    }

    // ------------------------------------------------------------------ //
    // Tool fetching                                                        //
    // ------------------------------------------------------------------ //

    private List<ToolDefinition> fetchAllTools() {
        String mcpUrlsConfig = mcpUrlsRaw.orElse("");
        if (mcpUrlsConfig.isBlank()) return List.of();
        toolToServerUrl.clear();
        List<ToolDefinition> all = new ArrayList<>();
        for (String raw : mcpUrlsConfig.split(",")) {
            String url = raw.trim();
            if (url.isEmpty()) continue;
            try {
                List<ToolDefinition> tools = fetchToolsFromServer(url);
                tools.forEach(t -> toolToServerUrl.put(t.name(), url));
                all.addAll(tools);
            } catch (Exception e) {
                LOG.warning("Could not fetch tools from " + url + ": " + e.getMessage());
            }
        }
        return all;
    }

    private List<ToolDefinition> fetchToolsFromServer(String serverUrl) throws Exception {
        String mcpUrl = ensureMcpPath(serverUrl);
        String sessionId = getOrCreateSession(mcpUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            LOG.warning("tools/list HTTP " + response.statusCode() + " from " + serverUrl);
            return List.of();
        }
        return parseToolsList(response.body());
    }

    private List<ToolDefinition> parseToolsList(String json) {
        try {
            JSONObject response = new JSONObject(json);
            JSONArray tools = response.getJSONObject("result").getJSONArray("tools");
            List<ToolDefinition> result = new ArrayList<>();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                String name = tool.getString("name");
                String desc = tool.optString("description", "");
                JSONObject schema = tool.optJSONObject("inputSchema");
                String schemaJson = schema != null
                        ? schema.toString()
                        : "{\"type\":\"object\",\"properties\":{}}";
                result.add(new ToolDefinition(name, desc, schemaJson));
            }
            LOG.info("Fetched " + result.size() + " tool(s) from MCP");
            return result;
        } catch (Exception e) {
            LOG.warning("Failed to parse tools/list response: " + e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ //
    // Tool execution                                                       //
    // ------------------------------------------------------------------ //

    private String callMcpTool(String toolName, String arguments) {
        String mcpUrlsConfig = mcpUrlsRaw.orElse("");
        if (mcpUrlsConfig.isBlank()) return "No MCP server configured";
        String serverUrl = toolToServerUrl.getOrDefault(
                toolName, mcpUrlsConfig.split(",")[0].trim());
        try {
            return callToolOnServer(serverUrl, toolName, arguments);
        } catch (Exception e) {
            LOG.warning("Tool call failed (" + toolName + " on " + serverUrl + "): " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private String callToolOnServer(String serverUrl, String toolName, String arguments) throws Exception {
        String mcpUrl = ensureMcpPath(serverUrl);
        String sessionId = getOrCreateSession(mcpUrl);

        String callBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + escapeJsonString(toolName) + "\","
                + "\"arguments\":" + arguments + "}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(callBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return "HTTP " + response.statusCode() + ": " + response.body();
        }
        return parseToolCallResult(response.body());
    }

    private String parseToolCallResult(String json) {
        try {
            JSONObject response = new JSONObject(json);
            if (response.has("error")) {
                JSONObject err = response.getJSONObject("error");
                return "Error: " + err.optString("message", "unknown error");
            }
            JSONObject result = response.optJSONObject("result");
            if (result == null) return "Empty result";
            JSONArray content = result.optJSONArray("content");
            if (content == null) return result.toString();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = content.getJSONObject(i);
                if ("text".equals(item.optString("type"))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(item.optString("text", ""));
                }
            }
            return sb.length() > 0 ? sb.toString() : result.toString();
        } catch (Exception e) {
            return json;
        }
    }

    // ------------------------------------------------------------------ //
    // MCP session management                                              //
    // ------------------------------------------------------------------ //

    private String getOrCreateSession(String mcpEndpoint) throws Exception {
        String cached = sessionCache.get(mcpEndpoint);
        if (cached != null) return cached;

        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"0.1.0\","
                + "\"clientInfo\":{\"name\":\"quarkus-chat-ui-agent\",\"version\":\"1.0.0\"}}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpEndpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sessionId == null) {
            throw new RuntimeException("No Mcp-Session-Id header in initialize response from " + mcpEndpoint);
        }
        sessionCache.put(mcpEndpoint, sessionId);
        LOG.info("MCP session established: " + sessionId + " at " + mcpEndpoint);
        return sessionId;
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                             //
    // ------------------------------------------------------------------ //

    private OpenAiCompatClient selectClient(String model) {
        for (OpenAiCompatClient c : clients) {
            if (c.servesModel(model)) return c;
        }
        return clients.isEmpty() ? null : clients.get(0);
    }

    private static String ensureMcpPath(String url) {
        if (url.endsWith("/mcp")) return url;
        if (url.contains("/mcp/")) return url;  // already a sub-path like /mcp/filesystem
        if (url.endsWith("/")) return url + "mcp";
        return url + "/mcp";
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
