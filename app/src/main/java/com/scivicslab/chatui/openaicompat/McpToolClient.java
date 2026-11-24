package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.core.provider.CancelScope;
import com.scivicslab.chatui.kernel.StateOwner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The openai-compat provider's MCP client for one MCP server ({@code OpenAiCompatStandardToolCalling_261120_oo01}).
 *
 * <p>It sends stateless MCP requests (protocol {@value #PROTOCOL_VERSION}): {@code tools/list} to learn the tools
 * the model may call, and {@code tools/call} to run one. To chat-ui's own MCP server it names its conversation in
 * {@value #CONVERSATION_HEADER} and carries that conversation's key, so the server lists and runs that conversation's
 * tools.</p>
 */
public final class McpToolClient {

    private static final Logger logger = Logger.getLogger(McpToolClient.class.getName());

    /** The MCP protocol version of the requests; the first one whose requests stand on their own. */
    public static final String PROTOCOL_VERSION = "2026-07-28";
    /** The header chat-ui's MCP server reads the conversation from. */
    public static final String CONVERSATION_HEADER = com.scivicslab.chatui.plugin.ConversationMcp.SCOPE_HEADER;

    /** How long a tool may run before its call is given up: as long as a turn's longest tool runs. */
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(30);

    /**
     * What a {@code tools/call} returned.
     *
     * @param text    the text of the result's content, joined
     * @param isError whether the server marked it an error, or the call did not reach the tool
     */
    public record Result(String text, boolean isError) {}

    private final String endpoint;
    private final String conversation;
    private final String key;
    // The JDK HttpClient runs its own short callbacks (TLS tasks, completions) on an executor; without one it
    // makes a pool of real threads of its own. They run on the real threads of one ManagedThreadPool that all
    // clients share (a call itself blocks the caller, not these). Threads are made on first use.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(com.scivicslab.chatui.openaicompat.client.HttpCallbacks.POOL)
            .connectTimeout(Duration.ofSeconds(5)).build();
    /** The numbers of the requests, one sequence for all clients; its one owner, an actor, hands them out. */
    private static final StateOwner<int[]> IDS = new StateOwner<>("mcp-request-ids", new int[] {1});

    /**
     * @param endpoint     the server's MCP endpoint, e.g. {@code http://127.0.0.1:28030/mcp}
     * @param conversation the conversation to name in {@value #CONVERSATION_HEADER}, e.g.
     *                     {@code project1/chat-01}; {@code null} names none
     */
    public McpToolClient(String endpoint, String conversation) {
        this(endpoint, conversation, null);
    }

    /**
     * @param endpoint     the server's MCP endpoint, e.g. {@code http://127.0.0.1:28030/mcp}
     * @param conversation the conversation to name in {@value #CONVERSATION_HEADER}; {@code null} names none
     * @param key          that conversation's key, sent as {@code Authorization: Bearer}; {@code null} sends none
     */
    public McpToolClient(String endpoint, String conversation, String key) {
        this.endpoint = endpoint;
        this.conversation = conversation;
        this.key = key;
    }

    /** @return the server's MCP endpoint */
    public String endpoint() {
        return endpoint;
    }

    /**
     * Lists the server's tools, every page of them.
     *
     * @return the tools in the server's order
     * @throws McpException when the server cannot be reached or answers with an error
     */
    public List<ToolDefinition> listTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        String cursor = null;
        do {
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            JSONObject result = request("tools/list", null, params, Duration.ofSeconds(30));
            JSONArray page = result.optJSONArray("tools");
            for (int i = 0; page != null && i < page.length(); i++) {
                JSONObject t = page.getJSONObject(i);
                JSONObject schema = t.optJSONObject("inputSchema");
                tools.add(new ToolDefinition(t.getString("name"), t.optString("description", ""),
                        (schema == null ? new JSONObject().put("type", "object") : schema).toString()));
            }
            cursor = result.optString("nextCursor", null);
            if (cursor != null && cursor.isEmpty()) cursor = null;
        } while (cursor != null);
        return tools;
    }

    /**
     * Runs one tool.
     *
     * @param name          the tool's name, as {@link #listTools()} gave it
     * @param argumentsJson the arguments as a JSON object text
     * @return the result; a call the server refused, or one that did not reach it, is an error result whose
     *         text says why
     */
    public Result callTool(String name, String argumentsJson) {
        return callTool(name, argumentsJson, CancelScope.NEVER);
    }

    /**
     * As {@link #callTool(String, String)}, ended by {@code scope} when the turn is cancelled: the HTTP request itself
     * is cancelled (the connection closes) and the call returns an error result at once. A scope that is already
     * cancelled sends nothing.
     */
    public Result callTool(String name, String argumentsJson, CancelScope scope) {
        JSONObject arguments;
        try {
            arguments = new JSONObject(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (JSONException e) {
            return new Result("error: the arguments are not a JSON object: " + e.getMessage(), true);
        }
        try {
            JSONObject result = request("tools/call", name,
                    new JSONObject().put("name", name).put("arguments", arguments), CALL_TIMEOUT, scope);
            StringBuilder text = new StringBuilder();
            JSONArray content = result.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject part = content.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) {
                    if (text.length() > 0) text.append("\n");
                    text.append(part.optString("text", ""));
                }
            }
            return new Result(text.toString(), result.optBoolean("isError", false));
        } catch (McpException e) {
            return new Result("error: " + e.getMessage(), true);
        }
    }

    /** Sends one stateless request and returns its {@code result}. */
    private JSONObject request(String method, String name, JSONObject params, Duration timeout) {
        return request(method, name, params, timeout, CancelScope.NEVER);
    }

    private JSONObject request(String method, String name, JSONObject params, Duration timeout, CancelScope scope) {
        params.put("_meta", new JSONObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JSONObject()));
        JSONObject body = new JSONObject().put("jsonrpc", "2.0").put("id", nextId())
                .put("method", method).put("params", params);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        if (name != null) b.header("Mcp-Name", name);
        if (conversation != null) b.header(CONVERSATION_HEADER, conversation);
        if (key != null) b.header("Authorization", "Bearer " + key);
        HttpResponse<String> response;
        // What the turn's cancel completes; the request is cancelled from it, which closes the connection.
        CompletableFuture<Void> stop = new CompletableFuture<>();
        CancelScope.Registration registration = scope.register(() -> stop.complete(null));
        if (registration == null) throw new McpException(method + " to " + endpoint + " was cancelled");
        try {
            CompletableFuture<HttpResponse<String>> call =
                    http.sendAsync(b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                            HttpResponse.BodyHandlers.ofString());
            stop.thenRun(() -> call.cancel(true));
            response = call.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(method + " to " + endpoint + " was interrupted");
        } catch (Exception e) {
            if (stop.isDone()) throw new McpException(method + " to " + endpoint + " was cancelled");
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new McpException(method + " to " + endpoint + " failed: " + cause.getMessage());
        } finally {
            registration.close();
        }
        JSONObject message = messageOf(response.body());
        if (message == null) {
            throw new McpException(method + " to " + endpoint + ": HTTP " + response.statusCode()
                    + " with no JSON-RPC answer: " + response.body());
        }
        JSONObject error = message.optJSONObject("error");
        if (error != null) {
            throw new McpException(error.optString("message", error.toString()));
        }
        JSONObject result = message.optJSONObject("result");
        if (result == null) throw new McpException(method + " to " + endpoint + ": the answer has no result");
        return result;
    }

    private int nextId() {
        return IDS.ask(n -> n[0]++);
    }

    /**
     * The JSON-RPC answer in a response body: the body itself when the server answered with JSON, or the last
     * {@code data:} line that holds an answer when it answered with an event stream.
     *
     * @return the answer, or {@code null} when the body holds none
     */
    static JSONObject messageOf(String body) {
        if (body == null) return null;
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) {
            try {
                return new JSONObject(trimmed);
            } catch (JSONException e) {
                return null;
            }
        }
        JSONObject answer = null;
        for (String line : trimmed.split("\n")) {
            if (!line.startsWith("data:")) continue;
            try {
                JSONObject o = new JSONObject(line.substring(5).strip());
                if (o.has("result") || o.has("error")) answer = o;
            } catch (JSONException e) {
                logger.fine(() -> "Unreadable event-stream line: " + line);
            }
        }
        return answer;
    }

    /** A request that did not come back with a result. */
    public static final class McpException extends RuntimeException {
        McpException(String message) {
            super(message);
        }
    }
}
