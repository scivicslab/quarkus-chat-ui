package com.scivicslab.chatui.agent;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actor {@code agent}: wraps an {@link AgentTurn} and maps its methods to actions. An
 * action fails only when the turn could not do the work; a fact about the data is the message.
 */
public class AgentTurnIIAR extends IIActorRef<AgentTurn> {

    public AgentTurnIIAR(String name, AgentTurn turn, IIActorSystem system) {
        super(name, turn, system);
    }

    @Action("start")
    public ActionResult start(String args) {
        wrapped().start();
        return new ActionResult(true, "started");
    }

    /** One model call; the message is {@code TOOL} or {@code ANSWER}. */
    @Action("step")
    public ActionResult step(String args) {
        try {
            return new ActionResult(true, wrapped().step());
        } catch (Exception e) {
            return new ActionResult(false, "step: " + e.getMessage());
        }
    }

    /** Runs the pending tool calls; argument {@code {maxObservationChars: N}}; the message is the count. */
    @Action("runTools")
    public ActionResult runTools(String args) {
        try {
            int max = 20000;
            if (args != null && args.trim().startsWith("{")) {
                max = new org.json.JSONObject(args).optInt("maxObservationChars", max);
            }
            return new ActionResult(true, String.valueOf(wrapped().runTools(max)));
        } catch (Exception e) {
            return new ActionResult(false, "runTools: " + e.getMessage());
        }
    }

    @Action("finish")
    public ActionResult finish(String args) {
        wrapped().finish();
        return new ActionResult(true, "finished");
    }

    /** The number of model calls made so far, as the message. */
    @Action("stepCount")
    public ActionResult stepCount(String args) {
        return new ActionResult(true, String.valueOf(wrapped().stepCount()));
    }
}
