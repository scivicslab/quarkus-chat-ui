package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Puts items into the browser's prompt queue on behalf of a running workflow.
 *
 * <p>The prompt queue is owned by the browser (localStorage), so nothing server-side is touched:
 * each method emits one {@code queue_add} event whose content is the queue item as JSON, and the
 * browser appends it. {@link QueueBridgeIIAR} wraps this as the workflow actor {@code queue}.</p>
 */
public class QueueBridge {

    private final Consumer<ChatEvent> emitter;
    private final ObjectMapper mapper;
    private final String title;
    private final String yaml;
    private final String input;

    /**
     * @param emitter where {@code queue_add} events go (normally the SSE actor)
     * @param mapper  JSON mapper for building the item
     * @param title   display title of the running workflow (shown in the queue list)
     * @param yaml    the running workflow's YAML text, re-sent verbatim by {@link #requeue}
     * @param input   the running workflow's input JSON, re-sent verbatim by {@link #requeue}
     */
    public QueueBridge(Consumer<ChatEvent> emitter, ObjectMapper mapper, String title, String yaml, String input) {
        this.emitter = emitter;
        this.mapper = mapper;
        this.title = title == null ? "" : title;
        this.yaml = yaml == null ? "" : yaml;
        this.input = input == null ? "" : input;
    }

    /** Puts this same workflow (same YAML, same input) at the end of the queue as an auto item. */
    public void requeue() {
        ObjectNode item = mapper.createObjectNode();
        item.put("kind", "workflow");
        item.put("text", title);
        item.put("yaml", yaml);
        item.put("input", input);
        item.put("auto", true);
        emitter.accept(ChatEvent.queueAdd(item.toString()));
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
        ObjectNode item = mapper.createObjectNode();
        item.put("kind", "prompt");
        item.put("text", text);
        item.put("auto", true);
        emitter.accept(ChatEvent.queueAdd(item.toString()));
        emitter.accept(ChatEvent.info("＋ Prompt enqueued by workflow"));
    }
}
