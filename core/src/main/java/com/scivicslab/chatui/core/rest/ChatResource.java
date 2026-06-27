package com.scivicslab.chatui.core.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.ActorNode;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.WatchdogActor;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.multiuser.MultiUserExtension;
import com.scivicslab.chatui.core.plugin.PromptPreprocessor;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.service.UrlFetchService;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.SessionSummary;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
    IoLogStore ioLog;

    @Inject
    IoLogView ioLogView;

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

    @Inject
    Instance<PromptPreprocessor> promptPreprocessors;

    // Single-user SSE state is now owned by SseActor — no mutable fields here.

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
        var response = rc.response();
        response.setChunked(true);
        response.putHeader("Content-Type", "text/event-stream");
        response.putHeader("Cache-Control", "no-cache");
        response.putHeader("X-Accel-Buffering", "no");

        // Build the initial status event synchronously (blocking handler — .join() is safe here)
        var actor = actorSystem.getChatActor();
        ChatEvent statusEvent = ChatEvent.status(
                actor.ask(ChatActor::getModel).join(),
                actor.ask(ChatActor::getSessionId).join(),
                actor.ask(ChatActor::isBusy).join()
        );

        // Delegate the entire SSE lifecycle to SseActor. All mutable SSE state
        // (response reference, heartbeat timer) now lives inside the actor and is
        // managed through its sequential message queue — no race conditions possible.
        actorSystem.getSseActor().tell(a -> a.onConnect(response, statusEvent));
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
            logger.info("[SSE_OUT] " + json);
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
        var sseActor = actorSystem.getSseActor();
        if (sseActor != null) {
            sseActor.tell(a -> a.emit(event));
        } else {
            logger.warning("SSE event DROPPED (no SSE actor): type=" + event.type());
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

        if (actorSystem.getSseActor() == null) {
            return ChatEvent.error("No SSE connection. Please refresh the page.");
        }
        var chatRef = actorSystem.getChatActor();
        String model = (request.model != null && !request.model.isBlank())
                ? request.model : chatRef.ask(ChatActor::getModel).join();
        var queueRef = actorSystem.getQueueActor();
        boolean noThink = request.noThink;

        // Apply optional prompt preprocessor (e.g., translate to English).
        // If no preprocessor is on the classpath this is a no-op.
        String promptText = request.text;
        if (!promptPreprocessors.isUnsatisfied()) {
            promptText = promptPreprocessors.get().process(promptText, this::emitSse);
        }
        final String finalPrompt = promptText;

        queueRef.tell(q -> q.enqueue(
                finalPrompt, model, "queue",
                this::emitSse, chatRef, "human", null,
                new java.util.concurrent.CompletableFuture<>(), noThink));
        return ChatEvent.info("Processing");
    }

    @POST
    @Path("/chat/submit")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Submits a user prompt to the LLM asynchronously via QueueActor.
     * Returns immediately with a UUID result key. Use GET /api/chat/status/{key}
     * and GET /api/chat/result/{key} to poll for completion and retrieve the result.
     *
     * @param request the prompt request containing user text and optional model override
     * @return a submit response with UUID result key and status, or an error
     */
    public SubmitResponse submit(PromptRequest request) {
        if (request == null || request.text == null || request.text.isBlank()) {
            return new SubmitResponse(null, "error", "Empty prompt");
        }
        var chatRef = actorSystem.getChatActor();
        var queueRef = actorSystem.getQueueActor();
        String model = (request.model != null && !request.model.isBlank())
                ? request.model : chatRef.ask(ChatActor::getModel).join();

        // Display the prompt as a user message in the chat area
        emitSse(ChatEvent.mcpUser(request.text));

        String resultKey = java.util.UUID.randomUUID().toString();
        chatRef.tell(a -> a.registerPendingResultKey(resultKey));

        CompletableFuture<Void> done = new CompletableFuture<>();
        queueRef.tell(q -> q.enqueue(
                request.text, model, "queue",
                this::emitSse, chatRef, "agent:api", resultKey, done));

        return new SubmitResponse(resultKey, "submitted", null);
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

        // Check if this is an MCP result key (UUID-based tracking)
        String resultStatus = ref.ask(a -> a.getResultStatus(sessionId)).join();
        if (!"unknown".equals(resultStatus)) {
            return new StatusResponse(sessionId, resultStatus, null);
        }

        // Fall back to provider session ID logic
        String currentSessionId = ref.ask(ChatActor::getSessionId).join();
        if (sessionId == null || !sessionId.equals(currentSessionId)) {
            return new StatusResponse(sessionId, "unknown", null);
        }
        boolean isBusy = ref.ask(ChatActor::isBusy).join();
        return new StatusResponse(sessionId, isBusy ? "processing" : "completed", null);
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

        // First check for a stored MCP result keyed by this session ID (UUID)
        String completedText = ref.ask(a -> a.getCompletedResult(sessionId)).join();
        if (completedText != null) {
            return new ResultResponse(sessionId, completedText, null);
        }

        // Fall back to provider session ID logic for browser-submitted prompts
        String currentSessionId = ref.ask(ChatActor::getSessionId).join();
        if (sessionId == null || !sessionId.equals(currentSessionId)) {
            // Check if it's a pending/active MCP key
            String status = ref.ask(a -> a.getResultStatus(sessionId)).join();
            if ("processing".equals(status)) {
                return new ResultResponse(sessionId, null, "Processing still in progress");
            }
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

        // Plan-approval answers (ExitPlanMode) are not tool-permission replies: the turn
        // that proposed the plan has already ended, so writing a tool result back to the
        // subprocess would reach no active read loop. Instead, start a fresh turn.
        if (!actorSystem.isMultiUser()
                && actorSystem.getProvider().isPlanApproval(request.promptId)) {
            return respondToPlan(request);
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

    /**
     * Handles a plan-approval answer (ExitPlanMode) by starting a fresh turn.
     *
     * <p>On approval, a continuation prompt instructing Claude to proceed is enqueued so its
     * implementation streams over SSE just like a normal message. On rejection, the user is
     * asked to type revised guidance — the next ordinary message resumes the live session.</p>
     *
     * @param request the response carrying the plan prompt id and the user's answer
     * @return an info event acknowledging the action
     */
    private ChatEvent respondToPlan(RespondRequest request) {
        actorSystem.getProvider().clearPlanApproval(request.promptId);
        boolean approved = request.response.trim().toLowerCase().startsWith("yes");
        if (!approved) {
            emitSse(ChatEvent.mcpUser("(Plan rejected)"));
            return ChatEvent.info("Plan rejected. Type your revised guidance to continue.");
        }

        emitSse(ChatEvent.mcpUser("(Plan approved — proceeding)"));
        var chatRef = actorSystem.getChatActor();
        var queueRef = actorSystem.getQueueActor();
        String model = chatRef.ask(ChatActor::getModel).join();
        String continuation =
                "The plan above is approved. Proceed with implementing it now.";
        queueRef.tell(q -> q.enqueue(
                continuation, model, "queue",
                this::emitSse, chatRef, "human", null,
                new CompletableFuture<>(), false));
        return ChatEvent.info("Proceeding with the approved plan");
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
    @Path("/actors")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns the live actor tree (right-pane Actors tab): one synthetic root with each actor as a child.
     */
    public ActorNode actors() {
        return actorSystem.getActorTree();
    }

    // ── Complete I/O log (Sessions tab): persistent H2 conversation sessions ─────────────

    /** Lists conversation sessions (most recent first) from the complete I/O log. */
    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sessions() {
        DistributedLogStore store = ioLog.store();
        if (store == null) {
            return Response.ok(List.of()).build();
        }
        List<Map<String, Object>> out = store.listSessions(200).stream()
                .map(this::sessionToMap)
                .toList();
        return Response.ok(out).build();
    }

    /** Deletes one log session and all its logs/node_results. Refuses the active conversation session. */
    @DELETE
    @Path("/sessions/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteSession(@PathParam("id") long id) {
        int n = ioLog.deleteSession(id);
        if (n < 0) return Response.status(500).entity(Map.of("error", "delete failed")).build();
        return Response.ok(Map.of("deleted", n)).build();
    }

    /** Bulk-deletes sessions started more than {@code days} days ago (active session excluded). */
    @DELETE
    @Path("/sessions/old")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteOldSessions(@QueryParam("days") @DefaultValue("30") int days) {
        int n = ioLog.deleteSessionsOlderThan(days);
        if (n < 0) return Response.status(500).entity(Map.of("error", "delete failed")).build();
        return Response.ok(Map.of("deleted", n, "olderThanDays", days)).build();
    }

    /** Returns the full (untruncated) message of one log entry, for on-expand lazy loading. */
    @GET
    @Path("/sessions/{id}/entry/{logId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sessionEntry(@PathParam("id") long id, @PathParam("logId") long logId) {
        return Response.ok(Map.of("message", ioLogView.fullMessage(id, logId))).build();
    }

    /** Returns the reconstructed per-turn trace for a session (the Sessions tab's inline view). */
    @GET
    @Path("/sessions/{id}/trace")
    @Produces(MediaType.APPLICATION_JSON)
    public List<IoLogView.TraceTurn> sessionTrace(@PathParam("id") long id) {
        return ioLogView.trace(id);
    }

    private Map<String, Object> sessionToMap(SessionSummary s) {
        // String.valueOf guards nulls (endedAt/status may be null): Map.of rejects null values.
        return Map.of(
                "sessionId",       s.getSessionId(),
                "workflowName",    String.valueOf(s.getWorkflowName()),
                "startedAt",       String.valueOf(s.getStartedAt()),
                "endedAt",         String.valueOf(s.getEndedAt()),
                "status",          String.valueOf(s.getStatus()),
                "totalLogEntries", s.getTotalLogEntries());
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
        boolean thinkToggle = "openai-compat".equals(p.id());
        if (actorSystem.isMultiUser()) {
            return new AppConfig(
                    appTitle, true, "NONE", keybind, p.id(),
                    false, false,
                    p.capabilities().supportsImages(),
                    p.capabilities().supportsUrlFetch(),
                    false,  // logs disabled in multi-user mode
                    true, thinkToggle
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
                false, thinkToggle
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
    @Path("/sub-queue/push")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Pushes a new SubQueueActor onto the stack. All traffic is routed to it until popped.
     * Supports nesting: each push adds one level. Only active in single-user mode.
     */
    public java.util.Map<String, Object> pushSubQueue(SubQueueRequest request) {
        if (actorSystem.isMultiUser()) {
            return java.util.Map.of("error", "SubQueue not supported in multi-user mode");
        }
        String jobId = (request != null && request.jobId != null && !request.jobId.isBlank())
                ? request.jobId : java.util.UUID.randomUUID().toString();
        actorSystem.pushSubQueue(jobId);
        return java.util.Map.of("jobId", jobId, "status", "pushed");
    }

    @POST
    @Path("/sub-queue/pop")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Pops the top SubQueueActor, restoring the previous queue level.
     * Only active in single-user mode.
     */
    public java.util.Map<String, Object> popSubQueue() {
        if (actorSystem.isMultiUser()) {
            return java.util.Map.of("error", "SubQueue not supported in multi-user mode");
        }
        actorSystem.popSubQueue();
        return java.util.Map.of("status", "popped");
    }

    @POST
    @Path("/receive-reply")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Receives a reply from another agent sent via the automatic callback mechanism.
     * Displays the reply in the chat UI as an mcp_user event without triggering LLM.
     *
     * @param request the reply containing the sender URL and reply text
     * @return an info event on success, or an error event on failure
     */
    public ChatEvent receiveReply(ReplyRequest request) {
        if (request == null || request.text == null || request.text.isBlank()) {
            return ChatEvent.error("Empty reply");
        }
        String fromLabel = (request.from != null && !request.from.isBlank()) ? request.from : "unknown";
        emitSse(ChatEvent.mcpUser("[Reply from " + fromLabel + "] " + request.text));
        return ChatEvent.info("Reply received");
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
        boolean logsEnabled, boolean multiUser, boolean supportsThinkToggle
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
        public boolean noThink;
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

    public static class ReplyRequest {
        public String from;
        public String text;
    }

    public static class SubQueueRequest {
        public String jobId;
    }
}
