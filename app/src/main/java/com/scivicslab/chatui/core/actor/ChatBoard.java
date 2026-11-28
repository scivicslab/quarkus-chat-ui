package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.SessionEntries;
import com.scivicslab.chatui.core.provider.LlmProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What other threads read of a conversation while its turn runs: whether it is busy and since when, its history, the requests
 * it has out and has received, its MCP servers, the last reminder decision, and the provider and the log it works through.
 *
 * <p>The turn owns these values and changes them only inside its own messages. A page or a tool that shows them must not wait
 * for a running turn to end, so the turn tells every change to this board, an actor of its own that answers {@code ask} at
 * any time. {@link Status} is immutable, so what a reader gets is the one the turn last told, whole.</p>
 */
final class ChatBoard {

    /**
     * One state of the conversation, as the turn last told it.
     *
     * @param busy                whether a turn is running
     * @param turnStartedAt       when the running turn started, in milliseconds
     * @param history             the conversation's history
     * @param outstanding         the requests the conversation sent that are still out
     * @param promised            the requests promised a report
     * @param unanswered          the received requests not yet answered, and who asked each
     * @param replyWatchLines     what the last reply watch said
     * @param lastReminderDecision what the last reminder decision was, or {@code null}
     * @param provider            the conversation's provider
     * @param entries             where the conversation records itself, or {@code null}
     * @param toolSet             the conversation's tool set
     * @param promptWorkflowFile  the prompt-construction sub-workflow file of the conversation
     * @param ioSession           the I/O-log session of the turn, or -1
     * @param ioTurnNo            the number of the turn in the log
     * @param stepCount           the step the turn is at
     * @param runningRequests     the ids of the requests the running turn is for (its own id, the request it answers, the
     *                            request a reminder turn reminds of); empty when no turn runs
     * @param finishedTurn        the number of the last turn that has finished in the conversation's current I/O log session
     *                            (0 when none); the running turn is not counted
     */
    record Status(boolean busy, long turnStartedAt, List<ChatSession.HistoryEntry> history,
                  List<OpenRequests.Outstanding> outstanding, Set<String> promised, Map<String, String> unanswered,
                  List<String> replyWatchLines, String lastReminderDecision,
                  LlmProvider provider, SessionEntries entries, com.scivicslab.chatui.agent.ToolSet toolSet,
                  String promptWorkflowFile, long ioSession, int ioTurnNo, int stepCount, Set<String> runningRequests,
                  int finishedTurn) {}

    /**
     * What a read of a conversation that was deleted gets: a status with nothing in it (not busy, no history, no requests,
     * a provider that answers {@code null}, {@code false}, {@code 0} or an empty collection to everything). A page that lists
     * conversations while one is deleted then sees it as empty instead of getting an exception; it is gone from the next list.
     */
    static final Status GONE = new Status(false, 0, List.of(), List.of(), Set.of(), Map.of(), List.of(), null, noProvider(),
            null, com.scivicslab.chatui.agent.ToolSet.FULL, null, -1, 0, 0, Set.of(), 0);

    private static LlmProvider noProvider() {
        return (LlmProvider) java.lang.reflect.Proxy.newProxyInstance(LlmProvider.class.getClassLoader(),
                new Class<?>[] {LlmProvider.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    if (type == Map.class) return Map.of();
                    if (type == Set.class) return Set.of();
                    return null;
                });
    }

    private Status status;

    void set(Status status) {
        this.status = status;
    }

    Status status() {
        return status;
    }
}
