package com.scivicslab.chatui.core.workflow;

/**
 * Appends items to the prompt queue on behalf of a running workflow. In quarkus-chat-ui the queue is
 * the browser's, so the implementation sends a {@code queue_add} SSE event; in chat-ui-with-audit-trail
 * it is a server-side actor ({@code AgentLoopTab_260930_oo01}).
 */
public interface QueueSink {

    /** Appends one item to the end of the queue. */
    void add(QueueItem item);
}
