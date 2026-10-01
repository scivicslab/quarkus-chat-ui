package com.scivicslab.chatui.core.workflow;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import jakarta.validation.constraints.NotNull;

/**
 * The workflow actor {@code queue}: wraps a {@link QueueBridge} and maps its two methods to the
 * actions {@code requeue} and {@code enqueue}. Only workflow plumbing lives here.
 */
public class QueueBridgeIIAR extends IIActorRef<QueueBridge> {

    /**
     * A prompt to put into the queue.
     *
     * @param text the prompt text, sent as is when its turn comes
     */
    public record EnqueueArgs(@NotNull String text) {}

    public QueueBridgeIIAR(String name, QueueBridge bridge, IIActorSystem system) {
        super(name, bridge, system);
    }

    /** Puts this same workflow, with the same input, at the end of the prompt queue. */
    @Action("requeue")
    public ActionResult requeue(String args) {
        wrapped().requeue();
        return new ActionResult(true, "requeued");
    }

    /** Puts a plain prompt at the end of the prompt queue. */
    @Action(value = "enqueue", argsType = EnqueueArgs.class)
    public ActionResult enqueue(EnqueueArgs args) {
        try {
            wrapped().enqueue(args.text());
            return new ActionResult(true, "enqueued");
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "enqueue: " + e.getMessage());
        }
    }
}
