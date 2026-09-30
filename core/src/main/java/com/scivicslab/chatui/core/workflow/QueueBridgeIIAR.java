package com.scivicslab.chatui.core.workflow;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actor {@code queue}: wraps a {@link QueueBridge} and maps its two methods to the
 * actions {@code requeue} and {@code enqueue}. Only workflow plumbing lives here.
 */
public class QueueBridgeIIAR extends IIActorRef<QueueBridge> {

    public QueueBridgeIIAR(String name, QueueBridge bridge, IIActorSystem system) {
        super(name, bridge, system);
    }

    @Action("requeue")
    public ActionResult requeue(String args) {
        wrapped().requeue();
        return new ActionResult(true, "requeued");
    }

    /** Enqueues the argument as a prompt; fails when it is blank. */
    @Action("enqueue")
    public ActionResult enqueue(String args) {
        try {
            wrapped().enqueue(HarnessLeashIIAR.firstArgument(args));
            return new ActionResult(true, "enqueued");
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "enqueue: " + e.getMessage());
        }
    }
}
