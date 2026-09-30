package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.openaicompat.ToolDefinition;

import java.util.List;

/**
 * The tools the model may call. The agent loop sees them only through this, so a test answers from a
 * script and the application asks MCP servers over HTTP ({@link McpToolCaller}).
 */
public interface ToolCaller {

    /** The tools, with the description and parameter schema the system prompt is built from. */
    List<ToolDefinition> listTools();

    /**
     * @param name          the tool name
     * @param argumentsJson the arguments as a JSON object
     * @return the tool's result as text; an error is returned as text starting with {@code Error:}
     */
    String call(String name, String argumentsJson);
}
