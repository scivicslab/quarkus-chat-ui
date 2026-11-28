package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.core.iolog.IoLogHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The finished turns of one conversation for {@link SuggestPromptKeeper}, read through {@link IoLogHistory}, the one
 * component that reads a conversation's rows. The finished-turn number comes from the conversation itself, read without
 * waiting for a running turn.
 */
public final class IoLogTurns implements SuggestPromptKeeper.Turns {

    /** The question an autonomous continuation records; nobody asked it, so it is not a message of the person. */
    static final String AUTONOMOUS_CONTINUATION = "(autonomous continuation)";

    private final IoLogHistory history;
    private final String squad;
    private final IntSupplier finishedTurn;

    /**
     * @param history      the reader of the I/O log
     * @param squad        the conversation's name
     * @param finishedTurn reads the last finished turn without waiting
     */
    public IoLogTurns(IoLogHistory history, String squad, IntSupplier finishedTurn) {
        this.history = history;
        this.squad = squad;
        this.finishedTurn = finishedTurn;
    }

    @Override
    public int finishedTurn() {
        return finishedTurn.getAsInt();
    }

    @Override
    public List<SuggestPatchPrompt.Message> read(int afterTurn, int upToTurn) {
        return messagesOf(history.readTurns(squad, afterTurn, upToTurn));
    }

    /**
     * The messages a list of turn rows holds, oldest first: the question, then the answer when there is one. A failed
     * turn has no answer; a blank question or answer is left out.
     */
    static List<SuggestPatchPrompt.Message> messagesOf(List<IoLogHistory.TurnRow> rows) {
        List<SuggestPatchPrompt.Message> out = new ArrayList<>();
        for (IoLogHistory.TurnRow r : rows) {
            if (r.question() != null && !r.question().isBlank() && !AUTONOMOUS_CONTINUATION.equals(r.question().strip())) {
                out.add(new SuggestPatchPrompt.Message(r.turn(), true, r.question()));
            }
            if (r.answer() != null && !r.answer().isBlank()) {
                out.add(new SuggestPatchPrompt.Message(r.turn(), false, r.answer()));
            }
        }
        return out;
    }
}
