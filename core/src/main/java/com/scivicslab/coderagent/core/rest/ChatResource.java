package com.scivicslab.coderagent.core.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.coderagent.core.actor.ChatActor;
import com.scivicslab.coderagent.core.actor.LlmConsoleActorSystem;
import com.scivicslab.coderagent.core.actor.WatchdogActor;
import com.scivicslab.coderagent.core.provider.LlmProvider;
import com.scivicslab.coderagent.core.service.UrlFetchService;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unified REST + SSE endpoint for chat interaction.
 *
 * <p>Conditional endpoints:</p>
 * <ul>
 *   <li>{@code /api/respond} - only meaningful when provider supports interactive prompts</li>
 *   <li>{@code /api/command} - only meaningful when provider supports slash commands</li>
 *   <li>{@code /api/fetch-url} - only meaningful when provider supports URL fetch</li>
 * </ul>
 */
@Path("/api")
@Blocking
public class ChatResource {

    private static final Logger logger = Logger.getLogger(ChatResource.class.getName());

    @Inject
    LlmConsoleActorSystem actorSystem;

    @Inject
    Vertx vertx;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "coder-agent.title", defaultValue = "Coder Agent")
    String appTitle;

    @ConfigProperty(name = "coder-agent.keybind", defaultValue = "default")
    String keybind;

    @Inject
    Instance<UrlFetchService> urlFetchService;

    private volatile HttpServerResponse sseResponse;
    private volatile Long heartbeatTimerId;

    void registerSseRoute(@Observes Router router) {
        router.get("/api/chat/stream").blockingHandler(this::handleSseConnect);
    }

    private void handleSseConnect(RoutingContext rc) {
        var prev = sseResponse;
        if (prev != null && !prev.ended()) {
            try { prev.end(); } catch (Exception ignored) {}
        }
        if (heartbeatTimerId != null) {
            vertx.cancelTimer(heartbeatTimerId);
        }

        var response = rc.response();
        response.setChunked(true);
        response.putHeader("Content-Type", "text/event-stream");
        response.putHeader("Cache-Control", "no-cache");
        response.putHeader("X-Accel-Buffering", "no");

        sseResponse = response;

        var actor = actorSystem.getChatActor();
        actor.tell(a -> a.setSseEmitter(this::emitSse));

        var watchdog = actorSystem.getWatchdog();
        if (watchdog != null) watchdog.tell(w -> w.setSseEmitter(this::emitSse));

        writeSse(response, ChatEvent.status(
            actor.ask(ChatActor::getModel).join(),
            actor.ask(ChatActor::getSessionId).join(),
            actor.ask(ChatActor::isBusy).join()
        ));

        heartbeatTimerId = vertx.setPeriodic(15_000, id -> {
            var r = sseResponse;
            if (r != null && !r.ended()) {
                writeSse(r, ChatEvent.heartbeat());
            } else {
                vertx.cancelTimer(id);
            }
        });

        response.closeHandler(v -> {
            actor.tell(a -> a.clearSseEmitter());
            if (watchdog != null) watchdog.tell(w -> w.clearSseEmitter());
            if (heartbeatTimerId != null) {
                vertx.cancelTimer(heartbeatTimerId);
                heartbeatTimerId = null;
            }
            if (sseResponse == response) sseResponse = null;
        });
    }

    private void writeSse(HttpServerResponse response, ChatEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            response.write("data: " + json + "\n\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write SSE event: type=" + event.type(), e);
        }
    }

    public void emitSse(ChatEvent event) {
        var resp = sseResponse;
        if (resp != null && !resp.ended()) {
            vertx.runOnContext(v -> writeSse(resp, event));
        } else {
            logger.warning("SSE event DROPPED (no connection): type=" + event.type());
        }
    }

    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent chat(PromptRequest request) {
        if (request == null || request.text == null || request.text.isBlank()) {
            return ChatEvent.error("Empty prompt");
        }
        if (sseResponse == null) {
            return ChatEvent.error("No SSE connection. Please refresh the page.");
        }
        var ref = actorSystem.getChatActor();
        String model = (request.model != null && !request.model.isBlank())
                ? request.model : ref.ask(ChatActor::getModel).join();
        ref.tell(a -> a.startPrompt(request.text, model, this::emitSse, ref, new CompletableFuture<>()));
        return ChatEvent.info("Processing");
    }

    @POST
    @Path("/respond")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent respond(RespondRequest request) {
        if (!actorSystem.getProvider().capabilities().supportsInteractivePrompts()) {
            return ChatEvent.error("Interactive prompts not supported by provider: "
                    + actorSystem.getProvider().id());
        }
        if (request == null || request.response == null || request.response.isBlank()) {
            return ChatEvent.error("Empty response");
        }
        try {
            actorSystem.getChatActor().ask(a -> {
                try {
                    a.respond(request.promptId, request.response);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }).join();
            return ChatEvent.info("Response sent");
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() instanceof IOException ? e.getCause() : e;
            logger.log(Level.WARNING, "Failed to send response to provider", cause);
            return ChatEvent.error("Failed to send response: " + cause.getMessage());
        }
    }

    @POST
    @Path("/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent cancel() {
        actorSystem.getChatActor().tell(ChatActor::cancel);
        return ChatEvent.info("Cancelled");
    }

    @POST
    @Path("/command")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<ChatEvent> command(CommandRequest request) {
        if (!actorSystem.getProvider().capabilities().supportsSlashCommands()) {
            return List.of(ChatEvent.error("Slash commands not supported by provider: "
                    + actorSystem.getProvider().id()));
        }
        List<ChatEvent> responses = new ArrayList<>();
        var ref = actorSystem.getChatActor();
        if (request != null && request.text != null && ref.ask(a -> a.isCommand(request.text)).join()) {
            ref.ask(a -> a.handleCommand(request.text)).join().forEach(responses::add);
        } else {
            responses.add(ChatEvent.error("Invalid command"));
        }
        return responses;
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent status() {
        var ref = actorSystem.getChatActor();
        return ChatEvent.status(
            ref.ask(ChatActor::getModel).join(),
            ref.ask(ChatActor::getSessionId).join(),
            ref.ask(ChatActor::isBusy).join()
        );
    }

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ModelInfo> models() {
        return actorSystem.getChatActor().ask(ChatActor::getAvailableModels).join().stream()
                .map(e -> new ModelInfo(e.name(), e.type(), e.server()))
                .toList();
    }

    @GET
    @Path("/logs")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ChatEvent> logs() {
        return actorSystem.getChatActor().ask(ChatActor::getRecentLogs).join();
    }

    @GET
    @Path("/history")
    @Produces(MediaType.APPLICATION_JSON)
    public List<HistoryResponse> history(@QueryParam("limit") @DefaultValue("50") int limit) {
        return actorSystem.getChatActor().ask(a -> a.getHistory(limit)).join().stream()
                .map(e -> new HistoryResponse(e.role(), e.content()))
                .toList();
    }

    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    public AppConfig config() {
        var ref = actorSystem.getChatActor();
        LlmProvider p = actorSystem.getProvider();
        return new AppConfig(
                appTitle,
                ref.ask(ChatActor::isAuthenticated).join(),
                ref.ask(ChatActor::getAuthMode).join().name(),
                keybind,
                p.id(),
                p.capabilities().supportsInteractivePrompts(),
                p.capabilities().supportsSlashCommands(),
                p.capabilities().supportsImages(),
                p.capabilities().supportsUrlFetch()
        );
    }

    @POST
    @Path("/auth")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent auth(AuthRequest request) {
        if (request == null || request.apiKey == null || request.apiKey.isBlank()) {
            return ChatEvent.error("API key is required");
        }
        actorSystem.getChatActor().tell(a -> a.setApiKey(request.apiKey));
        return ChatEvent.info("API key configured");
    }

    @POST
    @Path("/fetch-url")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public FetchResult fetchUrl(FetchRequest request) {
        if (!actorSystem.getProvider().capabilities().supportsUrlFetch()) {
            return new FetchResult(false, "", "URL fetch not supported by provider: "
                    + actorSystem.getProvider().id());
        }
        if (request == null || request.url == null || request.url.isBlank()) {
            return new FetchResult(false, "", "Empty URL");
        }
        String url = request.url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new FetchResult(false, "", "Invalid URL (must start with http:// or https://)");
        }
        if (urlFetchService.isUnsatisfied()) {
            return new FetchResult(false, "", "URL fetch service not available");
        }
        String content = urlFetchService.get().fetchAndExtract(url);
        if (content.startsWith("[Error]")) {
            return new FetchResult(false, "", content);
        }
        return new FetchResult(true, content, null);
    }

    // ---- Request/Response records ----

    public record AppConfig(
        String title, boolean authenticated, String authMode, String keybind,
        String providerId, boolean supportsInteractivePrompts,
        boolean supportsSlashCommands, boolean supportsImages, boolean supportsUrlFetch
    ) {}

    public record HistoryResponse(String role, String content) {}
    public record ModelInfo(String name, String type, String server) {}
    public record FetchResult(boolean success, String content, String error) {}

    public static class PromptRequest {
        public String text;
        public String model;
    }

    public static class CommandRequest {
        public String text;
    }

    public static class RespondRequest {
        public String promptId;
        public String response;
    }

    public static class AuthRequest {
        public String apiKey;
    }

    public static class FetchRequest {
        public String url;
    }
}
