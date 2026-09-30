package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * quarkus-chat-ui's {@link QueueSink}: the prompt queue is the browser's, so an item is appended by
 * sending a {@code queue_add} SSE event whose content is the item as JSON.
 */
public class SseQueueSink implements QueueSink {

    private final Consumer<ChatEvent> emitter;
    private final ObjectMapper mapper;

    public SseQueueSink(Consumer<ChatEvent> emitter, ObjectMapper mapper) {
        this.emitter = emitter;
        this.mapper = mapper;
    }

    @Override
    public void add(QueueItem item) {
        ObjectNode node = mapper.createObjectNode();
        node.put("kind", item.kind());
        node.put("text", item.text());
        if (item.yaml() != null) node.put("yaml", item.yaml());
        if (item.input() != null) node.put("input", item.input());
        node.put("auto", item.auto());
        emitter.accept(ChatEvent.queueAdd(node.toString()));
    }
}
