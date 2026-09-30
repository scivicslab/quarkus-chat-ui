package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.function.Consumer;

/**
 * Workflow actor {@code queue}: lets a running workflow put items into the browser's prompt queue.
 *
 * <p>The prompt queue is owned by the browser (localStorage), so this actor does not touch any
 * server-side queue. Each action emits one {@code queue_add} SSE event whose content is the queue
 * item as JSON; the browser appends it to its queue and runs it when its turn comes.</p>
 *
 * <p>Actions (actor name {@code queue}):</p>
 * <ul>
 *   <li>{@code requeue} — re-enqueue this same workflow (same YAML and input) at the end of the queue.
 *       Combine with the step's {@code delay:} field to poll a condition at an interval.</li>
 *   <li>{@code enqueue} — enqueue a plain prompt; the argument is the prompt text.</li>
 * </ul>
 */
public class QueueBridgeActor extends IIActorRef<Object> {

    private final Consumer<ChatEvent> emitter;
    private final ObjectMapper mapper;
    private final String title;
    private final String yaml;
    private final String input;

    /**
     * @param name    actor name (the YAML refers to it; conventionally {@code queue})
     * @param system  the per-run workflow actor system
     * @param emitter where {@code queue_add} events go (normally the SSE actor)
     * @param mapper  JSON mapper for building the item
     * @param title   display title of the running workflow (shown in the queue list)
     * @param yaml    the running workflow's YAML text, re-sent verbatim by {@code requeue}
     * @param input   the running workflow's input JSON, re-sent verbatim by {@code requeue}
     */
    public QueueBridgeActor(String name, IIActorSystem system, Consumer<ChatEvent> emitter,
                            ObjectMapper mapper, String title, String yaml, String input) {
        super(name, new Object(), system);
        this.emitter = emitter;
        this.mapper = mapper;
        this.title = title == null ? "" : title;
        this.yaml = yaml == null ? "" : yaml;
        this.input = input == null ? "" : input;
    }

    /** Re-enqueues this workflow (same YAML, same input) as an auto item at the end of the queue. */
    @Action("requeue")
    public ActionResult requeue(String args) {
        ObjectNode item = mapper.createObjectNode();
        item.put("kind", "workflow");
        item.put("text", title);
        item.put("yaml", yaml);
        item.put("input", input);
        item.put("auto", true);
        emitter.accept(ChatEvent.queueAdd(item.toString()));
        emitter.accept(ChatEvent.info("↻ Workflow re-enqueued: " + title));
        return new ActionResult(true, "requeued");
    }

    /** Enqueues a plain prompt (the argument) as an auto item at the end of the queue. */
    @Action("enqueue")
    public ActionResult enqueue(String args) {
        String text = parseFirstArgument(args);
        if (text == null || text.isBlank()) {
            return new ActionResult(false, "enqueue: empty prompt");
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("kind", "prompt");
        item.put("text", text);
        item.put("auto", true);
        emitter.accept(ChatEvent.queueAdd(item.toString()));
        emitter.accept(ChatEvent.info("＋ Prompt enqueued by workflow"));
        return new ActionResult(true, "enqueued");
    }
}
