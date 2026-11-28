package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The {@code list_queue}, {@code remove_queued_message} and {@code move_queued_message_to_front} tools: a
 * conversation reads and edits the waiting messages of a conversation of its own project, its own queue included
 * ({@code AgentEditsAnotherQueue_261128_oo01}). A message is addressed by its {@code entryId}, which the queue
 * never reuses, not by its position. The turn that is running is never touched.
 */
public final class QueueTools {

    private QueueTools() {}

    /** How much of a message's text {@code list_queue} shows. */
    static final int PREVIEW_CHARS = 200;

    private static final long WAIT_SECONDS = 5;

    /** @return one line per waiting message, in the order they will start, or an {@code error: ...} string */
    public static String list(IIActorSystem system, String myProjectId, String target) {
        Target t = resolve(system, myProjectId, target);
        if (t.error() != null) return t.error();
        try {
            List<PromptQueue.QueueEntry> entries = t.queue().ask(PromptQueue::snapshot).get(WAIT_SECONDS, TimeUnit.SECONDS);
            IIActorRef<?> chat = system.getIIActor(t.name() + ".chat");
            String running = chat instanceof ChatSessionIIAR c && c.isBusyDirect()
                    ? "a turn is running" : "no turn is running";
            StringBuilder out = new StringBuilder("queue of ").append(t.name()).append(": ").append(running).append(", ")
                    .append(entries.size()).append(" waiting");
            int n = 0;
            for (PromptQueue.QueueEntry e : entries) {
                out.append('\n').append(++n).append(". entryId=").append(e.entryId())
                        .append(" from=").append(e.source() == null ? "unknown" : e.source())
                        .append(" request=").append(e.requestId() == null ? "none" : e.requestId())
                        .append(" urgent=").append(e.urgent() ? "yes" : "no")
                        .append(" auto=").append(e.auto() ? "yes" : "no")
                        .append(": ").append(preview(e.prompt()));
            }
            return out.toString();
        } catch (Exception e) {
            return "error: could not read the queue of " + t.name() + ": " + e.getClass().getSimpleName();
        }
    }

    /** @return {@code removed}, {@code not found}, or an {@code error: ...} string */
    public static String remove(IIActorSystem system, String myProjectId, String target, String entryId) {
        return edit(system, myProjectId, target, entryId, (q, id) -> q.removeById(id)
                ? "removed: message " + id + " is out of the queue of " + target + ". If it was a request from another "
                        + "conversation, that conversation was told it ended without a reply."
                : NOT_FOUND);
    }

    /** @return {@code moved}, {@code already at the front}, {@code not found}, or an {@code error: ...} string */
    public static String moveToFront(IIActorSystem system, String myProjectId, String target, String entryId) {
        return edit(system, myProjectId, target, entryId, (q, id) -> switch (q.moveToFrontById(id)) {
            case MOVED -> "moved: message " + id + " is first in the queue of " + target
                    + " and starts after the running turn.";
            case ALREADY_AT_THE_FRONT -> "already at the front: message " + id + " starts next.";
            case NOT_FOUND -> NOT_FOUND;
        });
    }

    private static final String NOT_FOUND = "not found: no waiting message has that entryId (it may have started "
            + "already). Call list_queue again.";

    private interface Edit { String run(PromptQueue queue, long entryId); }

    private static String edit(IIActorSystem system, String myProjectId, String target, String entryId, Edit edit) {
        Target t = resolve(system, myProjectId, target);
        if (t.error() != null) return t.error();
        long id;
        try {
            id = Long.parseLong(entryId == null ? "" : entryId.strip());
        } catch (NumberFormatException e) {
            return "error: entryId is a whole number, as list_queue shows it";
        }
        try {
            return t.queue().ask((Function<PromptQueue, String>) q -> edit.run(q, id)).get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "error: could not reach the queue of " + t.name() + ": " + e.getClass().getSimpleName();
        }
    }

    private record Target(String name, ActorRef<PromptQueue> queue, String error) {}

    private static Target resolve(IIActorSystem system, String myProjectId, String target) {
        if (target == null || target.isBlank()) return new Target(null, null, "error: chatId is required");
        String name = ChatUiActorSystem.resolveChatName(myProjectId, target.strip());
        if (!name.startsWith(myProjectId + "/")) {
            return new Target(name, null, "error: not in this project: " + target.strip()
                    + " belongs to another project, whose queue you may not edit");
        }
        ActorRef<PromptQueue> queue = system.getActor(name + ".queue");
        if (queue == null) {
            return new Target(name, null, "error: chat not found: " + target.strip() + OtherInstances.notFoundHint(name));
        }
        return new Target(name, queue, null);
    }

    private static String preview(String prompt) {
        String one = prompt == null ? "" : prompt.replaceAll("\\s+", " ").strip();
        return one.length() <= PREVIEW_CHARS ? one : one.substring(0, PREVIEW_CHARS) + "...";
    }
}
