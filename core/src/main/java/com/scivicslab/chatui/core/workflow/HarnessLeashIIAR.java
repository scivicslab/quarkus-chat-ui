package com.scivicslab.chatui.core.workflow;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import jakarta.validation.constraints.NotNull;

/**
 * The workflow actor {@code harness}: wraps a {@link HarnessLeash} and maps each of its methods to
 * one {@code @Action}. Only workflow plumbing lives here. An action fails when the leash could not
 * do what was asked (it threw); a fact about the data comes back as the action's message, so the
 * YAML stores it with {@code this.putJson} and decides with {@code this.onlyIf}.
 *
 * <p>Actions that take an argument declare it as a record: the record's components are the keys the
 * YAML writes under {@code arguments:}, its Javadoc {@code @param} lines are what the Workflow tab shows
 * beside the step, and {@code @NotNull} marks the required ones ({@code ActionCatalogWithJavadoc_260930_oo01}).</p>
 */
public class HarnessLeashIIAR extends IIActorRef<HarnessLeash> {

    /**
     * Which checklist item to send.
     *
     * @param index 0-based index into the items loadChecklist read
     */
    public record SendItemArgs(@NotNull Integer index) {}

    /**
     * One instruction turn.
     *
     * @param instruction the text sent to the LLM as this turn's prompt
     */
    public record SendArgs(@NotNull String instruction) {}

    /**
     * One YES/NO turn.
     *
     * @param question the question; the leash appends the instruction to answer YES or NO on the first line
     */
    public record CheckArgs(@NotNull String question) {}

    public HarnessLeashIIAR(String name, HarnessLeash leash, IIActorSystem system) {
        super(name, leash, system);
    }

    /** Reads the run input and resets the per-run state; no turn is sent. */
    @Action("start")
    public ActionResult start(String args) {
        try {
            wrapped().start();
            return new ActionResult(true, "started");
        } catch (Exception e) {
            return new ActionResult(false, "start failed: " + e.getMessage());
        }
    }

    /** Sends one framing turn telling the LLM the run input's target and asking only for an acknowledgement. */
    @Action("frame")
    public ActionResult frame(String args) {
        try {
            return new ActionResult(true, wrapped().frame());
        } catch (Exception e) {
            return new ActionResult(false, "frame: " + e.getMessage());
        }
    }

    /** Reads the checklist file named by the run input; the message is the item count. */
    @Action("loadChecklist")
    public ActionResult loadChecklist(String args) {
        try {
            return new ActionResult(true, String.valueOf(wrapped().loadChecklist()));
        } catch (Exception e) {
            return new ActionResult(false, "could not read checklist: " + e.getMessage());
        }
    }

    /** Sends one checklist item to the LLM as one constrained turn; the message is the reply. */
    @Action(value = "sendNextItem", argsType = SendItemArgs.class)
    public ActionResult sendNextItem(SendItemArgs args) {
        try {
            return new ActionResult(true, wrapped().sendItem(args.index()));
        } catch (Exception e) {
            return new ActionResult(false, "sendNextItem: " + e.getMessage());
        }
    }

    /** Tells the browser the checklist run is complete. */
    @Action("finish")
    public ActionResult finish(String args) {
        wrapped().finish();
        return new ActionResult(true, "finished");
    }

    /** Asks the LLM to explain its intended approach without implementing; the message is the plan. */
    @Action("explain")
    public ActionResult explain(String args) {
        try {
            return new ActionResult(true, wrapped().explain());
        } catch (Exception e) {
            return new ActionResult(false, "explain: " + e.getMessage());
        }
    }

    /** Judges the plan with an independent turn; the message is PASS or FAIL. */
    @Action("judge")
    public ActionResult judge(String args) {
        try {
            return new ActionResult(true, wrapped().judge());
        } catch (Exception e) {
            return new ActionResult(false, "judge: " + e.getMessage());
        }
    }

    /** Counts one refine round after a FAIL verdict. */
    @Action("refine")
    public ActionResult refine(String args) {
        return new ActionResult(true, "refine " + wrapped().refine());
    }

    /** Tells the LLM the plan is approved and to implement it now; the message is the reply. */
    @Action("implement")
    public ActionResult implement(String args) {
        try {
            return new ActionResult(true, wrapped().implement());
        } catch (Exception e) {
            return new ActionResult(false, "implement: " + e.getMessage());
        }
    }

    /** Waits up to 30 minutes for a human decision on the plan; the message is APPROVED, REJECTED or TIMEOUT. */
    @Action("awaitApproval")
    public ActionResult awaitApproval(String args) {
        try {
            return new ActionResult(true, wrapped().awaitApproval());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "awaitApproval: interrupted");
        } catch (Exception e) {
            return new ActionResult(false, "awaitApproval: " + e.getMessage());
        }
    }

    /** Sends one instruction to the LLM as one turn; the message is the reply. */
    @Action(value = "send", argsType = SendArgs.class)
    public ActionResult send(SendArgs args) {
        try {
            return new ActionResult(true, wrapped().send(args.instruction()));
        } catch (Exception e) {
            return new ActionResult(false, "send: " + e.getMessage());
        }
    }

    /** Sends one question to be answered YES or NO; the message is YES or NO. */
    @Action(value = "check", argsType = CheckArgs.class)
    public ActionResult check(CheckArgs args) {
        try {
            return new ActionResult(true, wrapped().check(args.question()));
        } catch (Exception e) {
            return new ActionResult(false, "check: " + e.getMessage());
        }
    }
}
