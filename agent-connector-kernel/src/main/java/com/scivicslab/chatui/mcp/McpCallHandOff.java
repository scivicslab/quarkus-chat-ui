package com.scivicslab.chatui.mcp;

/**
 * A scope whose MCP calls are run by someone else (tasks/todo.md, "Shrinking the composed prompt"). The call is
 * handed over and the request waits for the observation. chat-ui uses it for a conversation on a harness CLI: the
 * CLI's call becomes a step of the conversation's own agent loop, which runs it and answers.
 */
public interface McpCallHandOff {

    /** @return whether this scope is offered {@code tool} */
    boolean offers(String tool);

    /**
     * Hands one call over and waits for its observation.
     *
     * @param tool          the tool's name
     * @param argumentsJson the call's arguments as a JSON object text
     * @return the observation, or a text starting with {@code error: }
     */
    String call(String tool, String argumentsJson);
}
