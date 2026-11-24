package com.scivicslab.chatui.core.actor;

import java.util.Map;

/**
 * The settings of one conversation that another thread may change while its turn runs, and the one place the turn and the
 * pages read them from: the step limit, the time limit, whether the conversation always asks its model to skip thinking,
 * and its external MCP servers.
 *
 * <p>The values belong to this board, an actor of its own that answers at any time. A change from outside (a PUT during a
 * ten-minute turn) is a message to it and returns at once; the turn reads the values from it at each step, so it picks a
 * change up at its next step. Nothing here waits for the conversation's turn.</p>
 */
final class ChatSettings {

    int stepLimit = ChatSession.DEFAULT_CONVERSATION_STEP_LIMIT;
    int timeLimitMinutes = ChatSession.DEFAULT_TIME_LIMIT_MINUTES;
    boolean noThink;
    Map<String, String> mcpServers = Map.of();
}
