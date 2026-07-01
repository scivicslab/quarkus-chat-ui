package com.scivicslab.chatui.tmux;

/**
 * The single write side of a session: injects input into the tmux pane through
 * {@code send-keys}. Wrapped by one {@code ActorRef} so that input from multiple
 * browser clients is serialised on the actor mailbox and never interleaves.
 *
 * <p>Plain POJO (no actor framework types) so it can be unit-tested directly.
 */
public final class PtyWriter {

    private final TmuxSession tmux;

    public PtyWriter(TmuxSession tmux) {
        this.tmux = tmux;
    }

    /**
     * Types {@code text} and submits it with Enter.
     *
     * @param text the line to send to the TUI
     */
    public void sendLine(String text) {
        tmux.sendLine(text);
    }

    /**
     * Types {@code text} without submitting.
     *
     * @param text the literal characters to type
     */
    public void sendText(String text) {
        tmux.sendText(text);
    }

    /** Submits the current input line with Enter. */
    public void sendEnter() {
        tmux.sendEnter();
    }

    /**
     * Answers a numbered approval dialog by typing the option number and Enter.
     *
     * @param optionNumber the 1-based option to select
     */
    public void sendChoice(int optionNumber) {
        tmux.sendText(Integer.toString(optionNumber));
        tmux.sendEnter();
    }
}
