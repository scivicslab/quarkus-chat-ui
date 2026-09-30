package com.scivicslab.chatui.core.workflow;

import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Puts items into the browser's prompt queue on behalf of a running workflow.
 *
 * <p>Where the queue lives is the {@link QueueSink}'s business: in quarkus-chat-ui it is the browser's
 * and the sink sends a {@code queue_add} event; elsewhere it may be a server-side actor.
 * {@link QueueBridgeIIAR} wraps this as the workflow actor {@code queue}.</p>
 */
public class QueueBridge {

    private final QueueSink sink;
    private final Consumer<ChatEvent> emitter;
    private final String title;
    private final String yaml;
    private final String input;

    /**
     * @param sink    where queue items go
     * @param emitter where the one-line notices go (the SSE actor, or a list in tests)
     * @param title   display title of the running workflow (shown in the queue list)
     * @param yaml    the running workflow's YAML text, re-sent verbatim by {@link #requeue}
     * @param input   the running workflow's input JSON, re-sent verbatim by {@link #requeue}
     */
    public QueueBridge(QueueSink sink, Consumer<ChatEvent> emitter, String title, String yaml, String input) {
        this.sink = sink;
        this.emitter = emitter;
        this.title = title == null ? "" : title;
        this.yaml = yaml == null ? "" : yaml;
        this.input = input == null ? "" : input;
    }

    /** Puts this same workflow (same YAML, same input) at the end of the queue as an auto item. */
    public void requeue() {
        sink.add(QueueItem.workflow(title, yaml, input));
        emitter.accept(ChatEvent.info("↻ Workflow re-enqueued: " + title));
    }

    /**
     * Puts a plain prompt at the end of the queue as an auto item.
     *
     * @throws IllegalArgumentException when the text is blank
     */
    public void enqueue(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty prompt");
        }
        sink.add(QueueItem.prompt(text));
        emitter.accept(ChatEvent.info("＋ Prompt enqueued by workflow"));
    }
}
