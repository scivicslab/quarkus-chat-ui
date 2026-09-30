package com.scivicslab.chatui.core.workflow;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actor {@code harness}: wraps a {@link HarnessLeash} and maps each of its methods to
 * one {@code @Action}. Only workflow plumbing lives here. An action fails when the leash could not
 * do what was asked (it threw); a fact about the data comes back as the action's message, so the
 * YAML stores it with {@code this.putJson} and decides with {@code this.onlyIf}.
 */
public class HarnessLeashIIAR extends IIActorRef<HarnessLeash> {

    public HarnessLeashIIAR(String name, HarnessLeash leash, IIActorSystem system) {
        super(name, leash, system);
    }

    /** Initialises the run from the JSON input; SUCCESS "started". */
    @Action("start")
    public ActionResult start(String args) {
        try {
            wrapped().start();
            return new ActionResult(true, "started");
        } catch (Exception e) {
            return new ActionResult(false, "start failed: " + e.getMessage());
        }
    }

    /** One framing turn about the run input's target; the message is the reply. */
    @Action("frame")
    public ActionResult frame(String args) {
        try {
            return new ActionResult(true, wrapped().frame());
        } catch (Exception e) {
            return new ActionResult(false, "frame: " + e.getMessage());
        }
    }

    /** Reads the checklist; the message is the item count, for {@code this.putJson}. */
    @Action("loadChecklist")
    public ActionResult loadChecklist(String args) {
        try {
            return new ActionResult(true, String.valueOf(wrapped().loadChecklist()));
        } catch (Exception e) {
            return new ActionResult(false, "could not read checklist: " + e.getMessage());
        }
    }

    /** Sends the item at the 0-based index given as the argument; the message is the reply. */
    @Action("sendNextItem")
    public ActionResult sendNextItem(String args) {
        try {
            int index = Integer.parseInt(firstArgument(args).trim());
            return new ActionResult(true, wrapped().sendItem(index));
        } catch (Exception e) {
            return new ActionResult(false, "sendNextItem: " + e.getMessage());
        }
    }

    @Action("finish")
    public ActionResult finish(String args) {
        wrapped().finish();
        return new ActionResult(true, "finished");
    }

    /** One explanation turn; the message is the plan. */
    @Action("explain")
    public ActionResult explain(String args) {
        try {
            return new ActionResult(true, wrapped().explain());
        } catch (Exception e) {
            return new ActionResult(false, "explain: " + e.getMessage());
        }
    }

    /** One judging turn; the message is {@code PASS} or {@code FAIL}. */
    @Action("judge")
    public ActionResult judge(String args) {
        try {
            return new ActionResult(true, wrapped().judge());
        } catch (Exception e) {
            return new ActionResult(false, "judge: " + e.getMessage());
        }
    }

    @Action("refine")
    public ActionResult refine(String args) {
        return new ActionResult(true, "refine " + wrapped().refine());
    }

    /** One implementation turn; the message is the reply. */
    @Action("implement")
    public ActionResult implement(String args) {
        try {
            return new ActionResult(true, wrapped().implement());
        } catch (Exception e) {
            return new ActionResult(false, "implement: " + e.getMessage());
        }
    }

    /** Blocks for the human decision; the message is {@code APPROVED}, {@code REJECTED} or {@code TIMEOUT}. */
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

    /** One instruction turn (the argument); the message is the reply. */
    @Action("send")
    public ActionResult send(String args) {
        try {
            return new ActionResult(true, wrapped().send(firstArgument(args)));
        } catch (Exception e) {
            return new ActionResult(false, "send: " + e.getMessage());
        }
    }

    /** One YES/NO turn (the argument is the question); the message is {@code YES} or {@code NO}. */
    @Action("check")
    public ActionResult check(String args) {
        try {
            return new ActionResult(true, wrapped().check(firstArgument(args)));
        } catch (Exception e) {
            return new ActionResult(false, "check: " + e.getMessage());
        }
    }

    /** The first element of a JSON-array argument as text, whatever its JSON type; else the text itself. */
    static String firstArgument(String args) {
        if (args == null) return "";
        if (args.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(args);
                return arr.length() == 0 ? "" : String.valueOf(arr.get(0));
            } catch (Exception ignored) {
                // not a JSON array: use the text as written
            }
        }
        return args;
    }
}
