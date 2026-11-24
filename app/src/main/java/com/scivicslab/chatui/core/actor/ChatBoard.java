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
     */
    record Status(boolean busy, long turnStartedAt, List<ChatSession.HistoryEntry> history,
                  List<OpenRequests.Outstanding> outstanding, Set<String> promised, Map<String, String> unanswered,
                  List<String> replyWatchLines, String lastReminderDecision,
                  LlmProvider provider, SessionEntries entries, com.scivicslab.chatui.agent.ToolSet toolSet,
                  String promptWorkflowFile, long ioSession, int ioTurnNo, int stepCount) {}

    private Status status;

    void set(Status status) {
        this.status = status;
    }

    Status status() {
        return status;
    }
}
