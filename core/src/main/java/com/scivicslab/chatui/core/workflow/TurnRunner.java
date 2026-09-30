package com.scivicslab.chatui.core.workflow;

/**
 * Runs one turn of the conversation on behalf of an outer workflow: sends one instruction, waits
 * for the reply, and returns its text. Where the turn actually goes is this interface's
 * implementation: in quarkus-chat-ui it is {@code LlmProvider.sendPrompt}; in chat-ui-with-audit-trail
 * it is the conversation's own prompt queue ({@code AgentLoopTab_260930_oo01}).
 */
public interface TurnRunner {

    /**
     * @param instruction the text sent as this turn's prompt
     * @return the reply text
     * @throws IllegalStateException when the turn could not be run
     */
    String run(String instruction);
}
