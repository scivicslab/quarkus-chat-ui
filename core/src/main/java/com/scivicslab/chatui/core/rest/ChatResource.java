package com.scivicslab.chatui.core.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.WatchdogActor;
import com.scivicslab.chatui.core.multiuser.MultiUserExtension;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.service.UrlFetchService;
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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    ChatUiActorSystem actorSystem;

    @Inject
    Vertx vertx;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "chat-ui.title", defaultValue = "Coder Agent")
    String appTitle;

    @ConfigProperty(name = "chat-ui.keybind", defaultValue = "default")
    String keybind;

    @Inject
    Instance<UrlFetchService> urlFetchService;

    // ---- Single-user SSE state ----
    private volatile HttpServerResponse sseResponse;
    private volatile Long heartbeatTimerId;

    // ---- Multi-user SSE state ----
    private final ConcurrentHashMap<String, HttpServerResponse> sseConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> heartbeatTimers = new ConcurrentHashMap<>();

    /**
     * Registers the SSE streaming route on the Vert.x router at startup.
     *
     * @param router the Vert.x router observed via CDI
     */
    void registerSseRoute(@Observes Router router) {
        router.get("/api/chat/stream").blockingHandler(this::handleSseConnect);
    }

    private void handleSseConnect(RoutingContext rc) {
        if (actorSystem.isMultiUser()) {
            handleMultiUserSseConnect(rc);
        } else {
            handleSingleUserSseConnect(rc);
        }
    }

    private void handleSingleUserSseConnect(RoutingContext rc) {
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

    private void handleMultiUserSseConnect(RoutingContext rc) {
        // Resolve userId: query param first, then BasicAuth header
        var userParams = rc.queryParam("user");
        String queryUser = (userParams != null && !userParams.isEmpty()) ? userParams.get(0) : null;
        String userId = (queryUser != null && !queryUser.isBlank())
                ? queryUser : extractUserIdFromHeader(rc.request().getHeader("Authorization"));
        if (userId == null || userId.isBlank()) {
            rc.response().setStatusCode(401).end("Unauthorized: provide ?user= or BasicAuth header");
            return;
        }

        // Close any previous SSE connection for this user
        var prev = sseConnections.get(userId);
        if (prev != null && !prev.ended()) {
            try { prev.end(); } catch (Exception ignored) {}
        }
        Long prevTimer = heartbeatTimers.remove(userId);
        if (prevTimer != null) vertx.cancelTimer(prevTimer);

        var response = rc.response();
        response.setChunked(true);
        response.putHeader("Content-Type", "text/event-stream");
        response.putHeader("Cache-Control", "no-cache");
        response.putHeader("X-Accel-Buffering", "no");
        response.write("retry: 10000\n\n");

        sseConnections.put(userId, response);

        MultiUserExtension ext = actorSystem.getMultiUserExtension();
        final String uid = userId;

        boolean busy = ext.isBusy(uid);
        writeSse(response, ChatEvent.status(ext.getModel(), null, busy));

        long timerId = vertx.setPeriodic(15_000, id -> {
            var r = sseConnections.get(uid);
            if (r != null && !r.ended()) {
                writeSse(r, ChatEvent.heartbeat());
            } else {
                vertx.cancelTimer(id);
            }
        });
        heartbeatTimers.put(userId, timerId);

        response.closeHandler(v -> {
            sseConnections.remove(uid, response);
            Long tid = heartbeatTimers.remove(uid);
            if (tid != null) vertx.cancelTimer(tid);
        });

        logger.info("Multi-user SSE connected: user=" + userId);
    }

    /** Sends an SSE event to a specific user in multi-user mode. */
    private void emitSseMulti(String userId, ChatEvent event) {
        var resp = sseConnections.get(userId);
        if (resp != null && !resp.ended()) {
            vertx.runOnContext(v -> writeSse(resp, event));
        } else {
            logger.warning("SSE event DROPPED (no connection for " + userId + "): type=" + event.type());
        }
    }

    private void writeSse(HttpServerResponse response, ChatEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            response.write("data: " + json + "\n\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write SSE event: type=" + event.type(), e);
        }
    }

    /**
     * Emits an SSE event to the currently connected client.
     * If no SSE connection is active, the event is dropped and a warning is logged.
     *
     * @param event the chat event to send
     */
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
    /**
     * Submits a user prompt to the LLM for processing.
     * The response is streamed back asynchronously via the SSE connection.
     */
    public ChatEvent chat(PromptRequest request,
                          @QueryParam("user") String queryUser,
                          @Context HttpHeaders headers) {
        if (request == null || request.text == null || request.text.isBlank()) {
            return ChatEvent.error("Empty prompt");
        }

        if (actorSystem.isMultiUser()) {
            String userId = resolveUserId(queryUser, headers);
            if (userId == null) return ChatEvent.error("Unauthorized");
            if (!sseConnections.containsKey(userId)) {
                return ChatEvent.error("No SSE connection. Please refresh the page.");
            }
            MultiUserExtension ext = actorSystem.getMultiUserExtension();
            String model = (request.model != null && !request.model.isBlank())
                    ? request.model : ext.getModel();
            CompletableFuture<Void> done = new CompletableFuture<>();
            ext.startPrompt(userId, request.text, model,
                    event -> emitSseMulti(userId, event), done);
            return ChatEvent.info("Processing");
        }

        if (sseResponse == null) {
            return ChatEvent.error("No SSE connection. Please refresh the page.");
        }
        var chatRef = actorSystem.getChatActor();
        String model = (request.model != null && !request.model.isBlank())
                ? request.model : chatRef.ask(ChatActor::getModel).join();
        var queueRef = actorSystem.getQueueActor();
        queueRef.tell(q -> q.enqueue(
                request.text, model, "queue", 0,
                this::emitSse, chatRef, queueRef, "human"));
        return ChatEvent.info("Processing");
    }

    @POST
    @Path("/chat/submit")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Submits a user prompt to the LLM asynchronously.
     * Returns immediately with a session ID. The response is streamed via SSE.
     *
     * @param request the prompt request containing user text and optional model override
     * @return a submit response with session ID and status, or an error
     */
    public SubmitResponse submit(PromptRequest request) {
        if (request == null || request.text == null || request.text.isBlank()) {
            return new SubmitResponse(null, "error", "Empty prompt");
        }
        var ref = actorSystem.getChatActor();
        if (ref.ask(ChatActor::isBusy).join()) {
            return new SubmitResponse(null, "busy", "LLM is currently processing another prompt");
        }
        String model = (request.model != null && !request.model.isBlank())
                ? request.model : ref.ask(ChatActor::getModel).join();
        ref.tell(a -> a.startPrompt(request.text, model, this::emitSse, ref, new CompletableFuture<>()));

        // Wait briefly for session ID to be set
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        String sessionId = ref.ask(ChatActor::getSessionId).join();
        return new SubmitResponse(sessionId, "submitted", null);
    }

    @GET
    @Path("/chat/status/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Gets the processing status of a submitted prompt.
     *
     * @param sessionId the session ID returned from submit
     * @return the status response with session ID, status, and optional progress
     */
    public StatusResponse getStatus(@PathParam("sessionId") String sessionId) {
        var ref = actorSystem.getChatActor();
        String currentSessionId = ref.ask(ChatActor::getSessionId).join();

        if (sessionId == null || !sessionId.equals(currentSessionId)) {
            return new StatusResponse(sessionId, "unknown", null);
        }

        boolean isBusy = ref.ask(ChatActor::isBusy).join();
        String status = isBusy ? "processing" : "completed";

        return new StatusResponse(sessionId, status, null);
    }

    @GET
    @Path("/chat/result/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Gets the result of a completed prompt processing.
     *
     * @param sessionId the session ID returned from submit
     * @return the result response with session ID and completion status
     */
    public ResultResponse getResult(@PathParam("sessionId") String sessionId) {
        var ref = actorSystem.getChatActor();
        String currentSessionId = ref.ask(ChatActor::getSessionId).join();

        if (sessionId == null || !sessionId.equals(currentSessionId)) {
            return new ResultResponse(sessionId, null, "Unknown session ID");
        }

        boolean isBusy = ref.ask(ChatActor::isBusy).join();
        if (isBusy) {
            return new ResultResponse(sessionId, null, "Processing still in progress");
        }

        return new ResultResponse(sessionId, "Completed. Results streamed via SSE.", null);
    }

    @POST
    @Path("/respond")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Sends a user response to an interactive prompt from the LLM (e.g., tool permission).
     * Only available when the provider supports interactive prompts.
     *
     * @param request the response containing the prompt ID and user answer
     * @return an info event on success, or an error event on failure
     */
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
    /**
     * Cancels the currently running LLM request, if any.
     */
    public ChatEvent cancel(@QueryParam("user") String queryUser, @Context HttpHeaders headers) {
        if (actorSystem.isMultiUser()) {
            String userId = resolveUserId(queryUser, headers);
            if (userId == null) return ChatEvent.error("Unauthorized");
            actorSystem.getMultiUserExtension().cancel(userId);
            return ChatEvent.info("Cancelled");
        }
        actorSystem.getChatActor().tellNow(ChatActor::cancel);
        actorSystem.getQueueActor().tell(com.scivicslab.chatui.core.actor.QueueActor::clearAgentMessages);
        return ChatEvent.info("Cancelled");
    }

    @POST
    @Path("/command")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Executes a slash command (e.g., {@code /model}, {@code /clear}).
     * Only available when the provider supports slash commands.
     *
     * @param request the command request containing the slash command text
     * @return a list of chat events produced by the command
     */
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
    /**
     * Returns the current status including the active model and busy state.
     */
    public ChatEvent status(@QueryParam("user") String queryUser, @Context HttpHeaders headers) {
        if (actorSystem.isMultiUser()) {
            String userId = resolveUserId(queryUser, headers);
            if (userId == null) return ChatEvent.status(null, null, false);
            MultiUserExtension ext = actorSystem.getMultiUserExtension();
            return ChatEvent.status(ext.getModel(), null, ext.isBusy(userId));
        }
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
    /**
     * Lists all models available from the configured LLM provider.
     */
    public List<ModelInfo> models() {
        if (actorSystem.isMultiUser()) {
            return actorSystem.getMultiUserExtension().getAvailableModels().stream()
                    .map(e -> new ModelInfo(e.name(), e.type(), e.server()))
                    .toList();
        }
        return actorSystem.getChatActor().ask(ChatActor::getAvailableModels).join().stream()
                .map(e -> new ModelInfo(e.name(), e.type(), e.server()))
                .toList();
    }

    @GET
    @Path("/logs")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Retrieves the most recent server log entries.
     */
    public List<ChatEvent> logs() {
        if (actorSystem.isMultiUser()) {
            return actorSystem.getMultiUserExtension().getRecentLogs();
        }
        return actorSystem.getChatActor().ask(ChatActor::getRecentLogs).join();
    }

    @GET
    @Path("/history")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Retrieves conversation history for the current user up to the specified limit.
     */
    public List<HistoryResponse> history(@QueryParam("limit") @DefaultValue("50") int limit,
                                         @QueryParam("user") String queryUser,
                                         @Context HttpHeaders headers) {
        if (actorSystem.isMultiUser()) {
            String userId = resolveUserId(queryUser, headers);
            if (userId == null) return List.of();
            return actorSystem.getMultiUserExtension().getHistory(userId, limit).stream()
                    .map(e -> new HistoryResponse(e.role(), e.content()))
                    .toList();
        }
        return actorSystem.getChatActor().ask(a -> a.getHistory(limit)).join().stream()
                .map(e -> new HistoryResponse(e.role(), e.content()))
                .toList();
    }

    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns the application configuration including title, auth state, keybind,
     * provider ID, and feature capability flags.
     */
    public AppConfig config() {
        LlmProvider p = actorSystem.getProvider();
        if (actorSystem.isMultiUser()) {
            return new AppConfig(
                    appTitle, true, "NONE", keybind, p.id(),
                    false, false,
                    p.capabilities().supportsImages(),
                    p.capabilities().supportsUrlFetch(),
                    false,  // logs disabled in multi-user mode
                    true
            );
        }
        var ref = actorSystem.getChatActor();
        return new AppConfig(
                appTitle,
                ref.ask(ChatActor::isAuthenticated).join(),
                ref.ask(ChatActor::getAuthMode).join().name(),
                keybind,
                p.id(),
                p.capabilities().supportsInteractivePrompts(),
                p.capabilities().supportsSlashCommands(),
                p.capabilities().supportsImages(),
                p.capabilities().supportsUrlFetch(),
                true,   // logs enabled in single-user mode
                false
        );
    }

    @POST
    @Path("/auth")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Configures the API key used for authenticating with the LLM provider.
     *
     * @param request the auth request containing the API key
     * @return an info event on success, or an error event if the key is blank
     */
    public ChatEvent auth(AuthRequest request) {
        if (request == null || request.apiKey == null || request.apiKey.isBlank()) {
            return ChatEvent.error("API key is required");
        }
        actorSystem.getChatActor().tell(a -> a.setApiKey(request.apiKey));
        return ChatEvent.info("API key configured");
    }

    @POST
    @Path("/btw")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Handles a /btw side question: runs a one-shot LLM call independently of the main
     * conversation. Does not read or write ChatActor history. The response is streamed
     * back via SSE using btw_delta / btw_result event types.
     *
     * @param request the side question with optional model override
     * @return an info event on acceptance, or an error event on validation failure
     */
    public ChatEvent btw(BtwRequest request) {
        if (request == null || request.question == null || request.question.isBlank()) {
            return ChatEvent.error("Empty question");
        }
        String apiKey = actorSystem.getChatActor().ask(ChatActor::getApiKey).join();
        String model = (request.model != null && !request.model.isBlank())
                ? request.model : actorSystem.getProvider().getCurrentModel();

        var btwRef = actorSystem.getBtwActor();
        btwRef.tell(a -> a.startBtw(request.question, model, apiKey, this::emitSse, btwRef));

        return ChatEvent.info("BTW processing");
    }

    @POST
    @Path("/fetch-url")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Fetches and extracts text content from the given URL.
     * Only available when the provider supports URL fetch.
     *
     * @param request the fetch request containing the target URL
     * @return the fetch result with extracted content, or an error description
     */
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

    // ---- Multi-user userId resolution ----

    /**
     * Resolves the userId for multi-user mode. Query param takes precedence, then BasicAuth.
     */
    String resolveUserId(String queryUser, HttpHeaders headers) {
        if (queryUser != null && !queryUser.isBlank()) return queryUser;
        String auth = headers != null ? headers.getHeaderString("Authorization") : null;
        return extractUserIdFromHeader(auth);
    }

    /**
     * Extracts the username from a Basic Auth header value.
     */
    static String extractUserIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Basic ")) return null;
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon > 0 ? decoded.substring(0, colon) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Request/Response records ----

    public record AppConfig(
        String title, boolean authenticated, String authMode, String keybind,
        String providerId, boolean supportsInteractivePrompts,
        boolean supportsSlashCommands, boolean supportsImages, boolean supportsUrlFetch,
        boolean logsEnabled, boolean multiUser
    ) {}

    public record HistoryResponse(String role, String content) {}
    public record ModelInfo(String name, String type, String server) {}
    public record FetchResult(boolean success, String content, String error) {}
    public record SubmitResponse(String sessionId, String status, String error) {}
    public record StatusResponse(String sessionId, String status, Double progress) {}
    public record ResultResponse(String sessionId, String result, String error) {}

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

    public static class BtwRequest {
        public String question;
        public String model;
    }
}
