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
 * YAML writes under {@code arguments:}, its Javadoc {@code @param} lines are what the Actions tab shows
 * for the action, and {@code @NotNull} marks the required ones. Actions that take none still receive a
 * String, which their {@code @param args} line says is ignored. The {@code <pre>} block of each action's
 * Javadoc is the step as a workflow YAML writes it; the tab shows it as the usage example
 * ({@code ActionCatalogWithJavadoc_260930_oo01}).</p>
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

    /**
     * Reads the run input and resets the per-run state; no turn is sent.
     *
     * <p>The run input is the JSON given with the workflow:
     * {@code {"target": "<the task>", "path": "<checklist file>", "maxRefines": 2}}; which of the three
     * keys matter depends on the actions the workflow goes on to call. Put this first.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: start
     * }</pre>
     *
     * @param args ignored
     */
    @Action("start")
    public ActionResult start(String args) {
        try {
            wrapped().start();
            return new ActionResult(true, "started");
        } catch (Exception e) {
            return new ActionResult(false, "start failed: " + e.getMessage());
        }
    }

    /**
     * Sends one framing turn telling the LLM the run input's target and asking only for an acknowledgement.
     *
     * <p>Fails when the run input gave no {@code target}.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: frame
     * }</pre>
     *
     * @param args ignored
     */
    @Action("frame")
    public ActionResult frame(String args) {
        try {
            return new ActionResult(true, wrapped().frame());
        } catch (Exception e) {
            return new ActionResult(false, "frame: " + e.getMessage());
        }
    }

    /**
     * Reads the checklist file named by the run input; the message is the item count.
     *
     * <p>The file is the run input's {@code path}; markdown horizontal rules ({@code ---}) separate the
     * items. The count is 0 when the run input names no file. Store it, and a cursor, for sendNextItem.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: loadChecklist
     * - actor: this
     *   method: putJson
     *   arguments: {path: items.total, value: "jexl: result"}
     * - actor: this
     *   method: putJson
     *   arguments: {path: items.next, value: 0}
     * }</pre>
     *
     * @param args ignored
     */
    @Action("loadChecklist")
    public ActionResult loadChecklist(String args) {
        try {
            return new ActionResult(true, String.valueOf(wrapped().loadChecklist()));
        } catch (Exception e) {
            return new ActionResult(false, "could not read checklist: " + e.getMessage());
        }
    }

    /**
     * Sends one checklist item to the LLM as one constrained turn; the message is the reply.
     *
     * <p>Fails when no item has that index, so guard the step with the count loadChecklist gave.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: onlyIf
     *   arguments: "jexl: state.getInt('items.next', 0) < state.getInt('items.total', 0)"
     * - actor: harness
     *   method: sendNextItem
     *   arguments: {index: "jexl: state.getInt('items.next', 0)"}
     * - actor: this
     *   method: putJson
     *   arguments: {path: items.next, value: "jexl: state.getInt('items.next', 0) + 1"}
     * }</pre>
     */
    @Action(value = "sendNextItem", argsType = SendItemArgs.class)
    public ActionResult sendNextItem(SendItemArgs args) {
        try {
            return new ActionResult(true, wrapped().sendItem(args.index()));
        } catch (Exception e) {
            return new ActionResult(false, "sendNextItem: " + e.getMessage());
        }
    }

    /**
     * Tells the browser the checklist run is complete.
     *
     * <pre>{@code
     * - actor: harness
     *   method: finish
     * }</pre>
     *
     * @param args ignored
     */
    @Action("finish")
    public ActionResult finish(String args) {
        wrapped().finish();
        return new ActionResult(true, "finished");
    }

    /**
     * Asks the LLM to explain its intended approach without implementing; the message is the plan.
     *
     * <p>The task explained is the run input's {@code target}. The reply is kept as the current plan
     * for judge, awaitApproval and implement. When a judge turn has failed the plan, its feedback is
     * folded into the next explain, so a workflow may loop judge → refine → explain.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: explain
     * }</pre>
     *
     * @param args ignored
     */
    @Action("explain")
    public ActionResult explain(String args) {
        try {
            return new ActionResult(true, wrapped().explain());
        } catch (Exception e) {
            return new ActionResult(false, "explain: " + e.getMessage());
        }
    }

    /**
     * Judges the plan with an independent turn; the message is PASS or FAIL.
     *
     * <p>The plan is the one explain kept. The verdict is a fact about the data, so the action succeeds
     * either way: store the message and decide with onlyIf. Once refine has been counted
     * {@code maxRefines} times (from the run input) the message is PASS without a turn, so the loop ends.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: judge
     * - actor: this
     *   method: putJson
     *   arguments: {path: judge.verdict, value: "jexl: result"}
     * - actor: this
     *   method: onlyIf
     *   arguments: "jexl: state.getString('judge.verdict') == 'PASS'"
     * }</pre>
     *
     * @param args ignored
     */
    @Action("judge")
    public ActionResult judge(String args) {
        try {
            return new ActionResult(true, wrapped().judge());
        } catch (Exception e) {
            return new ActionResult(false, "judge: " + e.getMessage());
        }
    }

    /**
     * Counts one refine round after a FAIL verdict.
     *
     * <p>The message is {@code refine <n>}. Put it on the transition back to explain; judge compares
     * the count with {@code maxRefines}.</p>
     *
     * <pre>{@code
     * - states: ["judge", "explain"]
     *   actions:
     *     - actor: harness
     *       method: refine
     * }</pre>
     *
     * @param args ignored
     */
    @Action("refine")
    public ActionResult refine(String args) {
        return new ActionResult(true, "refine " + wrapped().refine());
    }

    /**
     * Tells the LLM the plan is approved and to implement it now; the message is the reply.
     *
     * <p>The plan is the one explain kept; the turn quotes it back.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: implement
     * }</pre>
     *
     * @param args ignored
     */
    @Action("implement")
    public ActionResult implement(String args) {
        try {
            return new ActionResult(true, wrapped().implement());
        } catch (Exception e) {
            return new ActionResult(false, "implement: " + e.getMessage());
        }
    }

    /**
     * Waits up to 30 minutes for a human decision on the plan; the message is APPROVED, REJECTED or TIMEOUT.
     *
     * <p>The plan explain kept is shown and the step blocks until {@code POST /api/respond} answers or
     * the time is up. The decision is a fact about the data: store the message and decide with onlyIf.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: awaitApproval
     * - actor: this
     *   method: putJson
     *   arguments: {path: approval.decision, value: "jexl: result"}
     * - actor: this
     *   method: onlyIf
     *   arguments: "jexl: state.getString('approval.decision') == 'APPROVED'"
     * }</pre>
     *
     * @param args ignored
     */
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

    /**
     * Sends one instruction to the LLM as one turn; the message is the reply.
     *
     * <p>Fails when the instruction is blank. A value from the run input is read with a jexl
     * expression over the interpreter's state.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: send
     *   arguments: {instruction: "jexl: state.getString('action')"}
     * }</pre>
     */
    @Action(value = "send", argsType = SendArgs.class)
    public ActionResult send(SendArgs args) {
        try {
            return new ActionResult(true, wrapped().send(args.instruction()));
        } catch (Exception e) {
            return new ActionResult(false, "send: " + e.getMessage());
        }
    }

    /**
     * Sends one question to be answered YES or NO; the message is YES or NO.
     *
     * <p>The answer format is appended to the question. NO is a fact about the data, not a failure:
     * store the message and decide with onlyIf, with a second transition from the same state as the
     * fallback.</p>
     *
     * <pre>{@code
     * - actor: harness
     *   method: check
     *   arguments: {question: "jexl: state.getString('condition')"}
     * - actor: this
     *   method: putJson
     *   arguments: {path: check.answer, value: "jexl: result"}
     * - actor: this
     *   method: onlyIf
     *   arguments: "jexl: state.getString('check.answer') == 'YES'"
     * }</pre>
     */
    @Action(value = "check", argsType = CheckArgs.class)
    public ActionResult check(CheckArgs args) {
        try {
            return new ActionResult(true, wrapped().check(args.question()));
        } catch (Exception e) {
            return new ActionResult(false, "check: " + e.getMessage());
        }
    }
}
