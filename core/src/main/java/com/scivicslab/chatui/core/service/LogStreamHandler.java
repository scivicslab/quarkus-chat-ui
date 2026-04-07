package com.scivicslab.chatui.core.service;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.multiuser.MultiUserExtension;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * JUL Handler that forwards log records to {@link ChatActor}.
 *
 * <p>The actor reference is wired in by
 * {@link com.scivicslab.chatui.core.actor.LlmConsoleActorSystem} after the actor is
 * constructed, avoiding the circular-init problem that occurs when ChatActor logs during
 * construction and publish() tries to access CDI.</p>
 */
@ApplicationScoped
@Startup
public class LogStreamHandler extends Handler {

    private static final String OWN_LOGGER = LogStreamHandler.class.getName();

    private volatile ActorRef<ChatActor> chatActorRef;
    private volatile MultiUserExtension multiUserExtension;

    /**
     * Attaches this handler to the root JUL logger so that all log records
     * are forwarded to the chat actor for SSE streaming.
     */
    @PostConstruct
    void init() {
        Logger.getLogger("").addHandler(this);
    }

    /** Called by LlmConsoleActorSystem once the single-user ChatActor is ready. */
    public void wireActorRef(ActorRef<ChatActor> ref) {
        this.chatActorRef = ref;
    }

    /** Called by LlmConsoleActorSystem once the MultiUserExtension is ready. */
    public void wireMultiUserExtension(MultiUserExtension ext) {
        this.multiUserExtension = ext;
    }

    /**
     * Sets the SSE emitter on the underlying chat actor so that log events
     * are pushed to the connected client.
     *
     * @param emitter the consumer that writes chat events to the SSE response
     */
    public void setSseEmitter(Consumer<ChatEvent> emitter) {
        var actor = chatActorRef;
        if (actor != null) actor.tell(a -> a.setSseEmitter(emitter));
    }

    /**
     * Clears the SSE emitter on the underlying chat actor, stopping log event delivery.
     */
    public void clearSseEmitter() {
        var actor = chatActorRef;
        if (actor != null) actor.tell(a -> a.clearSseEmitter());
    }

    /**
     * Publishes a log record by forwarding it to the chat actor.
     * Records from this handler's own logger are suppressed to prevent recursion.
     *
     * @param record the log record to publish; {@code null} records are ignored
     */
    @Override
    public void publish(LogRecord record) {
        if (record == null) return;
        if (OWN_LOGGER.equals(record.getLoggerName())) return;
        String level = record.getLevel().getName();
        String loggerName = record.getLoggerName();
        String message = formatMessage(record);
        long timestamp = record.getMillis();
        var actor = chatActorRef;
        if (actor != null) {
            actor.tell(a -> a.publishLog(level, loggerName, message, timestamp));
        }
        var muExt = multiUserExtension;
        if (muExt != null) {
            muExt.publishLog(level, loggerName, message, timestamp);
        }
    }

    private String formatMessage(LogRecord record) {
        String msg = record.getMessage();
        if (msg == null) return "";
        Object[] params = record.getParameters();
        if (params != null && params.length > 0) {
            try {
                return java.text.MessageFormat.format(msg, params);
            } catch (Exception e) {
                return msg;
            }
        }
        return msg;
    }

    /**
     * Retrieves the most recent log entries buffered in the chat actor.
     *
     * @return a list of log events, or an empty list if the actor is not yet wired
     */
    public List<ChatEvent> getRecentLogs() {
        var actor = chatActorRef;
        if (actor == null) return List.of();
        return actor.ask(a -> a.getRecentLogs()).join();
    }

    /** No-op; log records are forwarded immediately on publish. */
    @Override public void flush() {}
    /** No-op; this handler holds no closeable resources of its own. */
    @Override public void close() throws SecurityException {}
}
