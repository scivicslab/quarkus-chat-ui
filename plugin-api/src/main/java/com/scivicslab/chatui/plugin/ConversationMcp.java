package com.scivicslab.chatui.plugin;

/**
 * How a conversation reaches chat-ui's own MCP server, which offers it its tools (tasks/todo.md, "Shrinking the
 * composed prompt"). A request names the conversation in {@link #SCOPE_HEADER} and carries its key as
 * {@code Authorization: Bearer <key>}; a request without the right key is refused.
 *
 * @param url   the MCP endpoint, e.g. {@code http://127.0.0.1:28030/mcp}
 * @param scope the conversation's qualified name, e.g. {@code project1/chat-01}
 * @param key   the conversation's key
 */
public record ConversationMcp(String url, String scope, String key) {

    /** The header a request names its conversation in: agent-connector-kernel's scope header. */
    public static final String SCOPE_HEADER = "X-Agent-Connector-Scope";

    /** The name chat-ui's MCP server goes by in a harness's MCP configuration. */
    public static final String SERVER_NAME = "chat-ui";

    @Override
    public String toString() {
        return "ConversationMcp[url=" + url + ", scope=" + scope + ", key=(hidden)]";
    }
}
