package com.scivicslab.chatui.core.mcp;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.LlmConsoleActorSystem;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.rest.ChatResource;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * MCP tools for quarkus-chat-ui.
 *
 * <p>Exposes 4 tools via the {@code /mcp} endpoint so that external MCP clients
 * (e.g., another Claude Code instance via MCP Gateway) can interact with this
 * chat-ui instance programmatically.</p>
 */
public class McpTools {

    private static final Logger logger = Logger.getLogger(McpTools.class.getName());

    @Inject
    LlmConsoleActorSystem actorSystem;

    @Inject
    ChatResource chatResource;

    @Tool(description = "Send a prompt to the LLM and return the response. "
            + "This is a synchronous call that waits for the full response.")
    String sendPrompt(
            @ToolArg(description = "The prompt text to send to the LLM") String prompt,
            @ToolArg(description = "The model to use (e.g., sonnet, opus, haiku). Leave empty for current model.") String model,
            @ToolArg(description = "Caller info injected by MCP Gateway (optional)") String _caller
    ) {
        var ref = actorSystem.getChatActor();
        if (ref.ask(ChatActor::isBusy).join()) {
            return "Error: LLM is currently processing another prompt. Try again later.";
        }

        String effectiveModel = (model == null || model.isBlank())
                ? ref.ask(ChatActor::getModel).join() : model;

        var response = new StringBuilder();

        String callerLabel = formatCaller(_caller);
        chatResource.emitSse(ChatEvent.info("[MCP" + callerLabel + "] " + prompt));

        try {
            var done = new CompletableFuture<Void>();
            ref.tell(a -> a.startPrompt(prompt, effectiveModel, event -> {
                if ("delta".equals(event.type())) {
                    response.append(event.content());
                } else if ("error".equals(event.type())) {
                    response.append("[ERROR] ").append(event.content());
                }
                chatResource.emitSse(event);
            }, ref, done));
            done.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            return "Error: Prompt timed out after 5 minutes. Partial response: " + response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted. Partial response: " + response;
        } catch (Exception e) {
            return "Error: " + e.getMessage() + ". Partial response: " + response;
        }

        return response.toString();
    }

    @Tool(description = "Get the current status of the chat-ui instance (model, session, busy state)")
    String getStatus() {
        var ref = actorSystem.getChatActor();
        return String.format("model=%s, session=%s, busy=%s",
                ref.ask(ChatActor::getModel).join(),
                ref.ask(ChatActor::getSessionId).join(),
                ref.ask(ChatActor::isBusy).join());
    }

    @Tool(description = "List available LLM models")
    String listModels() {
        return actorSystem.getChatActor()
                .ask(ChatActor::getAvailableModels).join().stream()
                .map(e -> e.name())
                .collect(Collectors.joining(", "));
    }

    @Tool(description = "Cancel the currently running LLM request")
    String cancelRequest() {
        var ref = actorSystem.getChatActor();
        if (!ref.ask(ChatActor::isBusy).join()) {
            return "No request is currently running.";
        }
        ref.tell(ChatActor::cancel);
        return "Cancel requested.";
    }

    /**
     * Formats _caller into a readable label.
     * HATEOAS: _caller is a URL like "http://localhost:8888/api/sessions/abc123"
     * Fetches the URL to get caller metadata (name, remoteAddress, etc.)
     */
    private String formatCaller(String callerUrl) {
        if (callerUrl == null || callerUrl.isBlank()) {
            return "";
        }
        if (!callerUrl.startsWith("http")) {
            return " from " + callerUrl;
        }
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(callerUrl))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var obj = mapper.readTree(resp.body());
                String name = obj.has("caller") ? obj.get("caller").asText() : "unknown";
                boolean registered = obj.has("registered") && obj.get("registered").asBoolean();
                if (registered && obj.has("callerUrl")) {
                    return " from " + name + " (" + obj.get("callerUrl").asText() + ")";
                }
                String addr = obj.has("remoteAddress") ? obj.get("remoteAddress").asText() : "";
                return " from " + name + (addr.isEmpty() ? "" : "@" + addr);
            }
        } catch (Exception e) {
            logger.fine("Failed to fetch caller metadata: " + e.getMessage());
        }
        return " from " + callerUrl;
    }
}
