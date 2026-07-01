package com.scivicslab.chatui.core.workflow;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds pending workflow-approval gates keyed by prompt id. A workflow step (see
 * {@code ClaudeHarnessActor.awaitApproval}) registers a future and blocks on it; an external
 * approver — a human clicking a button, a queue consumer, or another workflow — resolves it by
 * calling {@link #resolve} (via {@code POST /api/respond}). This decouples "who approves" from the
 * workflow: any caller that can reach the REST layer can supply the decision.
 */
@ApplicationScoped
public class WorkflowApprovalRegistry {

    /** The decision supplied by the approver. */
    public record Decision(boolean approved, String feedback) {
    }

    private final Map<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    /** Registers a pending approval so {@link #resolve} can complete it later. */
    public void register(String promptId, CompletableFuture<Decision> future) {
        pending.put(promptId, future);
    }

    /** Drops a pending approval (e.g. on timeout) without completing it. */
    public void remove(String promptId) {
        pending.remove(promptId);
    }

    /**
     * Resolves a pending approval from a raw response string (a button label such as
     * "承認して実装" / "却下（やり直し）", or "yes"/"no"). Approval is inferred from the string.
     *
     * @param promptId the prompt being answered
     * @param response the approver's answer
     * @return {@code true} if {@code promptId} was a pending workflow approval (now resolved);
     *         {@code false} if it was not (so the caller handles it another way)
     */
    public boolean resolve(String promptId, String response) {
        CompletableFuture<Decision> future = (promptId == null) ? null : pending.remove(promptId);
        if (future == null) {
            return false;
        }
        boolean approved = isAffirmative(response);
        future.complete(new Decision(approved, approved ? "" : response));
        return true;
    }

    private static boolean isAffirmative(String response) {
        if (response == null) {
            return false;
        }
        String s = response.strip();
        if (s.startsWith("承認") || s.startsWith("実装") || s.startsWith("はい")) {
            return true;
        }
        String lower = s.toLowerCase();
        return lower.equals("yes") || lower.equals("y") || lower.equals("1") || lower.equals("ok")
                || lower.startsWith("approve") || lower.startsWith("allow");
    }
}
