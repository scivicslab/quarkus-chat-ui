package com.scivicslab.chatui.core.mcp;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.rest.ChatResource;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/**
 * MCP client tools for quarkus-chat-ui.
 *
 * <p>Provides tools for calling external MCP servers asynchronously.</p>
 */
@ApplicationScoped
public class McpClientTools {

    private static final Logger logger = Logger.getLogger(McpClientTools.class.getName());

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    ChatResource chatResource;

    @Tool(description = "Send a message to another chat-ui instance via MCP. "
            + "Use this to reply to messages you received via MCP. "
            + "Example: callMcpServer(serverUrl='http://localhost:28010', toolName='submitPrompt', "
            + "arguments='{\"prompt\":\"your message\",\"model\":\"sonnet\",\"_caller\":\"http://localhost:28011\"}')")
    String callMcpServer(
            @ToolArg(description = "Target instance URL (e.g., http://localhost:28010)") String serverUrl,
            @ToolArg(description = "Tool name (use 'submitPrompt' for messages)") String toolName,
            @ToolArg(description = "JSON arguments for the tool") String arguments,
            @ToolArg(description = "Your instance URL (e.g., http://localhost:28011)") String _caller
    ) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return "Error: serverUrl is required";
        }
        if (toolName == null || toolName.isBlank()) {
            return "Error: toolName is required";
        }

        // Use McpClientActor for asynchronous sending
        var mcpClientActor = actorSystem.getMcpClientActor();
        mcpClientActor.tell(actor -> actor.sendMessage(
                serverUrl, toolName, arguments, _caller, chatResource::emitSse));

        logger.info("MCP message queued for delivery to " + serverUrl);
        return "Message queued for delivery to " + serverUrl;
    }
}
