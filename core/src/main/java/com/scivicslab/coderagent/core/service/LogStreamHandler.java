package com.scivicslab.coderagent.core.service;

import com.scivicslab.coderagent.core.actor.ChatActor;
import com.scivicslab.coderagent.core.rest.ChatEvent;
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
 * {@link com.scivicslab.coderagent.core.actor.LlmConsoleActorSystem} after the actor is
 * constructed, avoiding the circular-init problem that occurs when ChatActor logs during
 * construction and publish() tries to access CDI.</p>
 */
@ApplicationScoped
@Startup
public class LogStreamHandler extends Handler {

    private static final String OWN_LOGGER = LogStreamHandler.class.getName();

    private volatile ActorRef<ChatActor> chatActorRef;

    @PostConstruct
    void init() {
        Logger.getLogger("").addHandler(this);
    }

    /** Called by LlmConsoleActorSystem once the ChatActor is ready. */
    public void wireActorRef(ActorRef<ChatActor> ref) {
        this.chatActorRef = ref;
    }

    public void setSseEmitter(Consumer<ChatEvent> emitter) {
        var actor = chatActorRef;
        if (actor != null) actor.tell(a -> a.setSseEmitter(emitter));
    }

    public void clearSseEmitter() {
        var actor = chatActorRef;
        if (actor != null) actor.tell(a -> a.clearSseEmitter());
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null) return;
        if (OWN_LOGGER.equals(record.getLoggerName())) return;
        var actor = chatActorRef;
        if (actor == null) return;
        String level = record.getLevel().getName();
        String loggerName = record.getLoggerName();
        String message = formatMessage(record);
        long timestamp = record.getMillis();
        actor.tell(a -> a.publishLog(level, loggerName, message, timestamp));
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

    public List<ChatEvent> getRecentLogs() {
        var actor = chatActorRef;
        if (actor == null) return List.of();
        return actor.ask(a -> a.getRecentLogs()).join();
    }

    @Override public void flush() {}
    @Override public void close() throws SecurityException {}
}
