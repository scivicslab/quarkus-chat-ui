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

    /**
     * How much of each tool result the model may see.
     *
     * @param maxObservationChars characters of each observation kept in the model's copy; the I/O log keeps all
     */
    public record RunToolsArgs(Integer maxObservationChars) {}

    public AgentTurnIIAR(String name, AgentTurn turn, IIActorSystem system) {
        super(name, turn, system);
    }

    /** Resets the turn's state. */
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

    /** Runs the tool calls the last reply asked for and feeds the results back; the message is the count. */
    @Action(value = "runTools", argsType = RunToolsArgs.class)
    public ActionResult runTools(RunToolsArgs args) {
        try {
            int max = (args == null || args.maxObservationChars() == null) ? 20000 : args.maxObservationChars();
            return new ActionResult(true, String.valueOf(wrapped().runTools(max)));
        } catch (Exception e) {
            return new ActionResult(false, "runTools: " + e.getMessage());
        }
    }

    /** Sends the final answer to the browser and ends the turn. */
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
