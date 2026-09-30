package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.openaicompat.ToolDefinition;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * {@link ToolCaller} over MCP servers: JSON-RPC {@code tools/list} and {@code tools/call} by HTTP POST.
 * A server that answers {@code initialize} with an {@code Mcp-Session-Id} header gets that id on every
 * later request; a server without sessions is used as is. Tool names are routed to the server that
 * listed them.
 */
public class McpToolCaller implements ToolCaller {

    private static final Logger LOG = Logger.getLogger(McpToolCaller.class.getName());

    private final List<String> urls;
    private final Duration callTimeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> toolToUrl = new ConcurrentHashMap<>();
    private volatile List<ToolDefinition> cached;

    public McpToolCaller(List<String> urls, Duration callTimeout) {
        this.urls = urls == null ? List.of() : urls;
        this.callTimeout = callTimeout;
    }

    @Override
    public List<ToolDefinition> listTools() {
        List<ToolDefinition> c = cached;
        if (c != null) return c;
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        for (String url : urls) {
            try {
                for (ToolDefinition t : fetchTools(url)) {
                    if (byName.putIfAbsent(t.name(), t) == null) toolToUrl.put(t.name(), url);
                }
            } catch (Exception e) {
                LOG.warning("Could not list tools from " + url + ": " + e.getMessage());
            }
        }
        c = List.copyOf(byName.values());
        cached = c;
        return c;
    }

    @Override
    public String call(String name, String argumentsJson) {
        listTools();
        String url = toolToUrl.get(name);
        if (url == null) return "Error: unknown tool '" + name + "'";
        try {
            String mcp = mcpPath(url);
            String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":" + JSONObject.quote(name) + ",\"arguments\":"
                    + (argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson) + "}}";
            HttpResponse<String> r = post(mcp, body, callTimeout);
            if (r.statusCode() != 200) return "Error: HTTP " + r.statusCode() + ": " + r.body();
            return parseCallResult(r.body());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private List<ToolDefinition> fetchTools(String url) throws Exception {
        HttpResponse<String> r = post(mcpPath(url),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", Duration.ofSeconds(10));
        if (r.statusCode() != 200) throw new IllegalStateException("tools/list HTTP " + r.statusCode());
        JSONArray tools = new JSONObject(r.body()).getJSONObject("result").getJSONArray("tools");
        List<ToolDefinition> out = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.getJSONObject(i);
            JSONObject schema = t.optJSONObject("inputSchema");
            out.add(new ToolDefinition(t.getString("name"), t.optString("description", ""),
                    schema != null ? schema.toString() : "{\"type\":\"object\",\"properties\":{}}"));
        }
        return out;
    }

    private HttpResponse<String> post(String mcp, String body, Duration timeout) throws Exception {
        String session = sessions.computeIfAbsent(mcp, this::initialize);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(mcp)).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (!session.isEmpty()) b.header("Mcp-Session-Id", session);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String initialize(String mcp) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(mcp)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\","
                          + "\"capabilities\":{},\"clientInfo\":{\"name\":\"quarkus-chat-ui-agent\",\"version\":\"3.0.0\"}}}"))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.headers().firstValue("Mcp-Session-Id").orElse("");
        } catch (Exception e) {
            LOG.warning("MCP initialize failed for " + mcp + ": " + e.getMessage());
            return "";
        }
    }

    static String parseCallResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("error")) return "Error: " + obj.getJSONObject("error").optString("message", "unknown error");
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

    static String mcpPath(String url) {
        if (url.endsWith("/mcp") || url.contains("/mcp/")) return url;
        return url.endsWith("/") ? url + "mcp" : url + "/mcp";
    }
}
