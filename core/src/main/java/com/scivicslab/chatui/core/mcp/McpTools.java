package com.scivicslab.chatui.core.mcp;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.LlmConsoleActorSystem;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.rest.ChatResource;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
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
@ApplicationScoped
public class McpTools {

    private static final Logger logger = Logger.getLogger(McpTools.class.getName());

    @Inject
    LlmConsoleActorSystem actorSystem;

    @Inject
    ChatResource chatResource;

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
        ref.tellNow(ChatActor::cancel);
        return "Cancel requested.";
    }

    @Tool(description = "Submit a prompt to the LLM asynchronously. Returns a session ID immediately. "
            + "Use getPromptStatus and getPromptResult to check progress and retrieve results.")
    String submitPrompt(
            @ToolArg(description = "The prompt text to send to the LLM") String prompt,
            @ToolArg(description = "The model to use (e.g., sonnet, opus, haiku). Leave empty for current model.") String model,
            @ToolArg(description = "Caller info injected by MCP Gateway (optional)") String _caller
    ) {
        // Display the MCP message as a user message in the chat area
        // so the human can see what was received and the LLM response has context
        String callerLabel = (_caller != null && !_caller.isBlank())
                ? formatCallerLabel(_caller) : "unknown";
        chatResource.emitSse(ChatEvent.mcpUser("[MCP from " + callerLabel + "] " + prompt));

        // Enrich prompt with position awareness (provider-agnostic solution)
        String enrichedPrompt = buildEnrichedPrompt(prompt, _caller);

        // Use QueueActor to handle busy state gracefully
        var queueRef = actorSystem.getQueueActor();
        var chatRef = actorSystem.getChatActor();

        queueRef.tell(q -> q.enqueue(
            enrichedPrompt,
            model,
            "queue",  // default queue mode
            0,        // no timeout
            chatResource::emitSse,  // emitter for SSE events
            chatRef,
            queueRef,
            "mcp"
        ));

        int queueSize = queueRef.ask(com.scivicslab.chatui.core.actor.QueueActor::getQueueSize).join();
        if (queueSize > 1) {
            return String.format("Queued at position %d. Your message will be sent when the current prompt finishes.", queueSize);
        } else {
            return "Message accepted. Processing will begin shortly.";
        }
    }

    @Tool(description = "Get the processing status of a submitted prompt")
    String getPromptStatus(
            @ToolArg(description = "The session ID returned from submitPrompt") String sessionId
    ) {
        var response = chatResource.getStatus(sessionId);
        return String.format("sessionId=%s, status=%s", response.sessionId(), response.status());
    }

    @Tool(description = "Get the result of a completed prompt. Returns an error if still processing.")
    String getPromptResult(
            @ToolArg(description = "The session ID returned from submitPrompt") String sessionId
    ) {
        var response = chatResource.getResult(sessionId);
        if (response.error() != null) {
            return String.format("Error: %s", response.error());
        }
        return response.result();
    }

    /**
     * Builds an enriched prompt with position awareness for MCP messages.
     * Solves 3 challenges:
     * 1. Self-awareness: LLM knows its own URL
     * 2. Sender recognition: LLM knows who sent the message
     * 3. Reply method: LLM knows how to reply using callMcpServer tool
     */
    private String buildEnrichedPrompt(String prompt, String _caller) {
        if (_caller == null || _caller.isBlank()) {
            return prompt; // No enrichment for non-MCP messages
        }

        String selfUrl = getSelfUrl();
        String callerUrl = extractUrl(_caller);
        String callerLabel = formatCallerLabel(_caller);

        return String.format(
            "[Context]\n" +
            "You are running on: %s\n" +
            "Received via MCP from: %s\n\n" +
            "[Message]\n" +
            "%s\n\n" +
            "[How to Reply]\n" +
            "Use callMcpServer tool:\n" +
            "- serverUrl: %s\n" +
            "- toolName: submitPrompt\n" +
            "- arguments: {\"prompt\":\"your reply\",\"model\":\"sonnet\",\"_caller\":\"%s\"}",
            selfUrl,      // Self-awareness
            callerLabel,  // Sender recognition
            prompt,
            callerUrl,    // Reply method
            selfUrl
        );
    }

    /**
     * Gets the URL of this chat-ui instance.
     */
    private String getSelfUrl() {
        int port = Integer.parseInt(System.getProperty("quarkus.http.port", "8080"));
        return "http://localhost:" + port;
    }

    /**
     * Extracts base URL from _caller parameter.
     * Example: "http://localhost:28010/api/sessions/abc" -> "http://localhost:28010"
     */
    private String extractUrl(String caller) {
        if (caller == null) {
            return null;
        }
        if (caller.startsWith("http://") || caller.startsWith("https://")) {
            try {
                URI uri = new URI(caller);
                return uri.getScheme() + "://" + uri.getAuthority();
            } catch (Exception e) {
                return caller;
            }
        }
        // Fallback: if just a port number or identifier
        return caller;
    }

    /**
     * Formats _caller into a short label for display.
     * Example: "http://localhost:28010" -> "localhost:28010"
     */
    private String formatCallerLabel(String caller) {
        if (caller == null) {
            return "unknown";
        }
        try {
            URI uri = new URI(caller);
            String authority = uri.getAuthority();
            return authority != null ? authority : caller;
        } catch (Exception e) {
            return caller;
        }
    }

    /**
     * Formats _caller into a readable label (legacy HATEOAS version).
     * Fetches the URL to get caller metadata (name, remoteAddress, etc.)
     */
    @SuppressWarnings("unused")
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
