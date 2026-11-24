package com.scivicslab.chatui.core.actor;

/**
 * The step and time limits of one conversation, as the one place other threads read them from.
 *
 * <p>The conversation's turn owns the limits and changes them only inside its own messages. A page that shows them, or
 * a tool that reports them, must not wait for a running turn to end, so the turn tells every change to this board, which
 * is an actor of its own that answers {@code ask} at any time. The values here are what the turn last told, in the order
 * it told them.</p>
 */
final class ChatLimits {

    private int stepLimit = ChatSession.DEFAULT_CONVERSATION_STEP_LIMIT;
    private int timeLimitMinutes = ChatSession.DEFAULT_TIME_LIMIT_MINUTES;

    void set(int stepLimit, int timeLimitMinutes) {
        this.stepLimit = stepLimit;
        this.timeLimitMinutes = timeLimitMinutes;
    }

    int stepLimit() { return stepLimit; }

    int timeLimitMinutes() { return timeLimitMinutes; }
}
