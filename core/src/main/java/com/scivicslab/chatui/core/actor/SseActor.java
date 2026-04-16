package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actor that owns the SSE connection lifecycle for a single-user chat session.
 *
 * <p>Eliminates the race conditions that arise when {@code heartbeatTimerId} and
 * {@code sseResponse} are held as mutable instance fields on a JAX-RS resource —
 * a pattern where close handlers and new-connection handlers execute on different
 * threads and can interleave. By making all SSE state private to this actor and
 * routing every mutation through its sequential message queue, the race condition
 * cannot exist by construction.</p>
 *
 * <p>Thread safety is guaranteed by the actor's sequential message processing.
 * No {@code volatile}, no {@code synchronized}, no captured-local-variable hacks.</p>
 *
 * <p>All writes to the Vert.x {@link HttpServerResponse} are dispatched via
 * {@link Vertx#runOnContext} to ensure they execute on the event loop.</p>
 */
public class SseActor {

    private static final Logger logger = Logger.getLogger(SseActor.class.getName());

    private final Vertx vertx;
    private final ObjectMapper objectMapper;

    // Wired after creation by ChatUiActorSystem
    private ActorRef<SseActor> self;
    private ActorRef<ChatActor> chatActorRef;
    private ActorRef<WatchdogActor> watchdogRef;

    // SSE connection state — safe to access without synchronization because
    // all mutations go through this actor's sequential message queue.
    private HttpServerResponse response;
    private long heartbeatTimerId = -1;

    public SseActor(Vertx vertx, ObjectMapper objectMapper) {
        this.vertx = vertx;
        this.objectMapper = objectMapper;
    }

    /**
     * Wires actor references. Called once by {@link ChatUiActorSystem} immediately after creation.
     */
    public void init(ActorRef<SseActor> self,
                     ActorRef<ChatActor> chatActorRef,
                     ActorRef<WatchdogActor> watchdogRef) {
        this.self = self;
        this.chatActorRef = chatActorRef;
        this.watchdogRef = watchdogRef;
    }

    /**
     * Handles a new SSE connection. Closes any existing connection, cancels the previous
     * heartbeat timer, and sets up a fresh heartbeat and close handler for the new response.
     *
     * <p>Also updates the SSE emitter on {@link ChatActor} and {@link WatchdogActor}
     * so that events flowing through the actor system are forwarded to the new connection.</p>
     *
     * @param newResponse   the new HTTP response to stream SSE events into
     * @param initialStatus the first event to send (status event built by the caller)
     */
    public void onConnect(HttpServerResponse newResponse, ChatEvent initialStatus) {
        logger.info("SSE connect: new=" + System.identityHashCode(newResponse)
                + (response != null
                   ? " (replacing prev=" + System.identityHashCode(response)
                      + " ended=" + response.ended() + ")"
                   : ""));

        // Close the previous connection if still open
        if (response != null && !response.ended()) {
            try { response.end(); } catch (Exception ignored) {}
        }

        // Cancel the previous heartbeat timer using its own ID (not the field, which
        // may be -1 if this is the first connection)
        if (heartbeatTimerId >= 0) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }

        response = newResponse;

        // Propagate the new emitter to the rest of the actor system
        Consumer<ChatEvent> emitter = this::emit;
        chatActorRef.tell(a -> a.setSseEmitter(emitter));
        if (watchdogRef != null) watchdogRef.tell(w -> w.setSseEmitter(emitter));

        // Send the initial status event
        writeToResponse(newResponse, initialStatus);

        // Start a new heartbeat timer. The timer lambda captures the actor ref and
        // the timer ID so that doHeartbeat can detect stale firings.
        ActorRef<SseActor> selfRef = self;
        heartbeatTimerId = vertx.setPeriodic(15_000, id -> selfRef.tell(a -> a.doHeartbeat(id)));

        // Wire the close handler. It captures the specific response object so that
        // an old close handler firing after a reconnect is a no-op.
        newResponse.closeHandler(v -> selfRef.tell(a -> a.onClose(newResponse)));
    }

    /**
     * Emits a {@link ChatEvent} to the connected browser. Safe to call from any thread.
     * The actual write is dispatched to the Vert.x event loop via {@link Vertx#runOnContext}.
     */
    public void emit(ChatEvent event) {
        HttpServerResponse r = response;
        if (r == null || r.ended()) {
            logger.warning("SSE event DROPPED (no connection): type=" + event.type());
            return;
        }
        try {
            String data = "data: " + objectMapper.writeValueAsString(event) + "\n\n";
            vertx.runOnContext(v -> {
                if (!r.ended()) {
                    try { r.write(data); } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to serialize SSE event: type=" + event.type(), e);
        }
    }

    // ---- Internal ----

    /**
     * Called by the periodic heartbeat timer via {@code tell}.
     *
     * <p>If {@code timerId} does not match the current {@code heartbeatTimerId}, this is a
     * stale firing from an old timer that was replaced by a new connection — cancel it and
     * return. Otherwise emit a heartbeat or cancel if the connection is gone.</p>
     */
    private void doHeartbeat(long timerId) {
        if (timerId != heartbeatTimerId) {
            // Stale timer firing from a replaced connection — just cancel it
            vertx.cancelTimer(timerId);
            return;
        }
        if (response == null || response.ended()) {
            vertx.cancelTimer(timerId);
            heartbeatTimerId = -1;
        } else {
            emit(ChatEvent.heartbeat());
        }
    }

    /**
     * Called by the response close handler via {@code tell}.
     *
     * <p>If {@code closedResponse} is not the current active response, this is a stale
     * close event for a replaced connection — ignore it. Otherwise clean up the timer
     * and clear SSE emitters from the actor system.</p>
     */
    private void onClose(HttpServerResponse closedResponse) {
        if (response != closedResponse) {
            logger.fine("SSE close handler fired for stale response, ignoring");
            return;
        }
        logger.info("SSE close handler fired: response=" + System.identityHashCode(closedResponse));

        if (heartbeatTimerId >= 0) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }
        response = null;

        chatActorRef.tell(a -> a.clearSseEmitter());
        if (watchdogRef != null) watchdogRef.tell(w -> w.clearSseEmitter());
    }

    private void writeToResponse(HttpServerResponse resp, ChatEvent event) {
        try {
            String data = "data: " + objectMapper.writeValueAsString(event) + "\n\n";
            vertx.runOnContext(v -> {
                if (!resp.ended()) {
                    try { resp.write(data); } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write initial SSE event", e);
        }
    }
}
