package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.openaicompat.ToolDefinition;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.OpenAiCompatClient;
import com.scivicslab.pojoactor.core.ActorRef;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Actor that owns the tool-calling loop for the openai-compat provider.
 *
 * <p>State machine:</p>
 * <pre>
 * start()
 *   └─ supplyAsync(fetchAllTools, ioPool) ──► tell(onToolsFetched)
 *        └─ sendNextRequest()
 *             └─ supplyAsync(callLlm, ioPool) ──► tell(onLlmResponse)
 *                  ├─ no tool_calls → emit answer, done.complete()
 *                  └─ tool_calls → allOf(supplyAsync(callMcp, ioPool) × N)
 *                                      └─ tell(onToolsDone)
 *                                           └─ sendNextRequest() (next iteration)
 * </pre>
 *
 * <p>All state mutations happen on the actor thread (via tell/ask).
 * All blocking I/O runs off the actor thread via ask(fn, self.system().getManagedThreadPool()).
 * cancel() uses tellNow() to bypass the queue and set cancelled=true immediately.</p>
 */
public class AgentLoopActor {

    private static final Logger LOG = Logger.getLogger(AgentLoopActor.class.getName());

    // Shared infrastructure — injected at construction, thread-safe
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, String> sessionCache;
    private final Duration mcpCallTimeout;

    // Per-loop state — mutated only on the actor thread
    private String model;
    private LinkedList<ChatMessage> history;
    private Consumer<ChatEvent> emitter;
    private ProviderContext ctx;
    private CompletableFuture<Void> done;
    private List<OpenAiCompatClient> clients;
    private List<String> mcpUrls;
    private int maxIterations;
    private int iteration;
    private long startTime;
    private List<ToolDefinition> tools;
    private Map<String, String> toolToServerUrl;  // populated in onToolsFetched

    // volatile: read by tellNow() from outside the actor thread
    private volatile boolean cancelled;

    AgentLoopActor(HttpClient httpClient,
                   ConcurrentHashMap<String, String> sessionCache,
                   Duration mcpCallTimeout) {
        this.httpClient = httpClient;
        this.sessionCache = sessionCache;
        this.mcpCallTimeout = mcpCallTimeout;
    }

    // ── cancel (called via tellNow — bypasses queue) ─────────────────────────

    /** Sets the cancelled flag immediately. The loop stops at the next actor message. */
    public void cancel() {
        cancelled = true;
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Starts a new agent loop. Fetches MCP tool definitions off-thread,
     * then delivers the result back via tell(onToolsFetched).
     */
    public void start(String model, LinkedList<ChatMessage> history,
                      Consumer<ChatEvent> emitter, ProviderContext ctx,
                      CompletableFuture<Void> done,
                      List<OpenAiCompatClient> clients, List<String> mcpUrls, int maxIterations,
                      ActorRef<AgentLoopActor> self) {
        this.model = model;
        this.history = history;
        this.emitter = emitter;
        this.ctx = ctx;
        this.done = done;
        this.clients = clients;
        this.mcpUrls = mcpUrls;
        this.maxIterations = maxIterations;
        this.iteration = 0;
        this.startTime = System.currentTimeMillis();
        this.cancelled = false;
        this.tools = List.of();
        this.toolToServerUrl = new ConcurrentHashMap<>();

        // Fetch tools off the actor thread; deliver result back via tell()
        self.ask(a -> fetchAllTools(), self.system().getManagedThreadPool())
            .thenAccept(result -> self.tell(a -> a.onToolsFetched(result, self)));
    }

    // ── Step 1: tools fetched ─────────────────────────────────────────────────

    public void onToolsFetched(ToolFetchResult result, ActorRef<AgentLoopActor> self) {
        if (cancelled) { done.complete(null); return; }
        this.tools = result.tools();
        this.toolToServerUrl = result.toolToServerUrl();
        LOG.info("Agent loop starting: model=" + model
                + ", tools=" + tools.size() + ", maxIter=" + maxIterations);
        sendNextRequest(self);
    }

    // ── Step 2: LLM request ───────────────────────────────────────────────────

    private void sendNextRequest(ActorRef<AgentLoopActor> self) {
        if (cancelled) { done.complete(null); return; }

        iteration++;
        if (iteration > maxIterations) {
            LOG.warning("Agent loop hit max-iterations (" + maxIterations + ") for model=" + model);
            emitter.accept(ChatEvent.thinking("Tool call limit (" + maxIterations + " iterations) reached."));
            String limitMsg = "Maximum tool call iterations reached without a final answer.";
            history.addLast(new ChatMessage.Assistant(limitMsg));
            emitter.accept(ChatEvent.delta(limitMsg));
            emitter.accept(ChatEvent.result(null, 0.0, elapsed(), model, false));
            done.complete(null);
            return;
        }

        OpenAiCompatClient client = selectClient();
        if (client == null) {
            emitter.accept(ChatEvent.error("No server available for model: " + model));
            done.complete(null);
            return;
        }

        // Capture snapshot before going off-thread (history may grow on actor thread later)
        List<ChatMessage> snapshot = List.copyOf(history);
        List<ToolDefinition> currentTools = List.copyOf(tools);

        self.ask(a -> client.sendNonStreaming(model, snapshot, ctx.noThink(), 0, currentTools),
                 self.system().getManagedThreadPool())
            .thenAccept(resp -> self.tell(a -> a.onLlmResponse(resp, self)));
    }

    // ── Step 3: LLM responded ─────────────────────────────────────────────────

    public void onLlmResponse(OpenAiCompatClient.NonStreamingResponse resp,
                              ActorRef<AgentLoopActor> self) {
        if (cancelled) { done.complete(null); return; }

        if (resp.finishReason().startsWith("error")) {
            String detail = resp.content() != null ? resp.content() : resp.finishReason();
            LOG.warning("Agent loop error at iter=" + iteration + ": " + detail);
            emitter.accept(ChatEvent.error("Agent loop error: " + detail));
            done.complete(null);
            return;
        }

        if (resp.toolCalls().isEmpty()) {
            // Final answer
            String content = resp.content() != null ? resp.content() : "";
            history.addLast(new ChatMessage.Assistant(content));
            emitter.accept(ChatEvent.delta(content));
            emitter.accept(ChatEvent.result(null, 0.0, elapsed(), model, false));
            LOG.info("Agent loop complete after " + iteration + " iteration(s)");
            done.complete(null);
            return;
        }

        // Record tool call request in history
        history.addLast(new ChatMessage.ToolCallRequest(resp.toolCalls()));

        // Emit thinking events for each tool (on actor thread — safe)
        for (ChatMessage.ToolCallRequest.ToolCall tc : resp.toolCalls()) {
            String shortArgs = tc.arguments().length() > 120
                    ? tc.arguments().substring(0, 120) + "…" : tc.arguments();
            emitter.accept(ChatEvent.thinking("[Tool] " + tc.name() + "(" + shortArgs + ")"));
            if (ctx.onActivity() != null) ctx.onActivity().run();
        }

        // Execute all tool calls in parallel off the actor thread.
        // Capture server URLs here (actor thread) before going off-thread.
        List<CompletableFuture<ToolCallResult>> futures = resp.toolCalls().stream()
            .map(tc -> {
                String serverUrl = toolToServerUrl.getOrDefault(
                        tc.name(), mcpUrls.isEmpty() ? null : mcpUrls.get(0));
                return self.ask(a -> {
                    try {
                        String result = a.callToolOnServer(serverUrl, tc.name(), tc.arguments());
                        return new ToolCallResult(tc.id(), tc.name(), result);
                    } catch (Exception e) {
                        LOG.warning("Tool call failed (" + tc.name() + "): " + e.getMessage());
                        return new ToolCallResult(tc.id(), tc.name(), "Error: " + e.getMessage());
                    }
                }, self.system().getManagedThreadPool());
            })
            .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()))
            .thenAccept(results -> self.tell(a -> a.onToolsDone(results, self)));
    }

    // ── Step 4: all tool results collected ───────────────────────────────────

    public void onToolsDone(List<ToolCallResult> results, ActorRef<AgentLoopActor> self) {
        if (cancelled) { done.complete(null); return; }

        for (ToolCallResult r : results) {
            String shortResult = r.result().length() > 300
                    ? r.result().substring(0, 300) + "…" : r.result();
            emitter.accept(ChatEvent.thinking("[Result] " + shortResult));

            String histResult = r.result().length() > 10_000
                    ? r.result().substring(0, 10_000) + "\n[truncated — content too large]"
                    : r.result();
            history.addLast(new ChatMessage.ToolResult(r.id(), r.name(), histResult));
        }

        sendNextRequest(self);  // next iteration
    }

    // ── Tool fetching (runs on ioPool) ────────────────────────────────────────

    private ToolFetchResult fetchAllTools() {
        Map<String, String> mapping = new ConcurrentHashMap<>();
        List<ToolDefinition> all = new ArrayList<>();
        for (String url : mcpUrls) {
            try {
                List<ToolDefinition> fetched = fetchToolsFromServer(url);
                fetched.forEach(t -> mapping.put(t.name(), url));
                all.addAll(fetched);
            } catch (Exception e) {
                LOG.warning("Could not fetch tools from " + url + ": " + e.getMessage());
            }
        }
        return new ToolFetchResult(all, mapping);
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
            JSONObject obj = new JSONObject(json);
            JSONArray tools = obj.getJSONObject("result").getJSONArray("tools");
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
            return result;
        } catch (Exception e) {
            LOG.warning("Failed to parse tools/list response: " + e.getMessage());
            return List.of();
        }
    }

    // ── Tool execution (runs on ioPool) ───────────────────────────────────────

    private String callToolOnServer(String serverUrl, String toolName, String arguments)
            throws Exception {
        if (serverUrl == null) return "No MCP server configured";
        String mcpUrl = ensureMcpPath(serverUrl);
        String sessionId = getOrCreateSession(mcpUrl);
        String callBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + escapeJsonString(toolName) + "\","
                + "\"arguments\":" + arguments + "}}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(mcpCallTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(callBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return "HTTP " + response.statusCode() + ": " + response.body();
        return parseToolCallResult(response.body());
    }

    private String parseToolCallResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("error")) {
                return "Error: " + obj.getJSONObject("error").optString("message", "unknown error");
            }
            JSONObject result = obj.optJSONObject("result");
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

    // ── MCP session management (runs on ioPool, sessionCache is ConcurrentHashMap) ──

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
            throw new RuntimeException("No Mcp-Session-Id header from " + mcpEndpoint);
        }
        sessionCache.put(mcpEndpoint, sessionId);
        LOG.info("MCP session established: " + sessionId + " at " + mcpEndpoint);
        return sessionId;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OpenAiCompatClient selectClient() {
        for (OpenAiCompatClient c : clients) {
            if (c.servesModel(model)) return c;
        }
        return clients.isEmpty() ? null : clients.get(0);
    }

    private long elapsed() {
        return System.currentTimeMillis() - startTime;
    }

    private static String ensureMcpPath(String url) {
        if (url.endsWith("/mcp")) return url;
        if (url.contains("/mcp/")) return url;
        if (url.endsWith("/")) return url + "mcp";
        return url + "/mcp";
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── Value types ───────────────────────────────────────────────────────────

    record ToolFetchResult(List<ToolDefinition> tools, Map<String, String> toolToServerUrl) {}

    record ToolCallResult(String id, String name, String result) {}
}
