package com.scivicslab.chatui.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.audittrail.HeadBlock;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;

import org.junit.jupiter.api.Test;

/**
 * What the Tokens tab counts for chat-ui's MCP server is what its {@code tools/list} answers a conversation with, built
 * from the same registered tools and the same filter, with no request.
 */
class ChatUiMcpScopesOfferedToTest {

    @Test
    void aConversationIsListedWhatItsFilterOffers_andNoConversationNothing() {
        ChatUiActorSystem system = com.scivicslab.chatui.core.actor.InitialisedSystem.start();
        ChatUiMcpScopes scopes = new ChatUiMcpScopes();
        scopes.chats = system;

        List<String> names = scopes.offeredTo("project1/chat-01").stream().map(ToolCatalog.Spec::name).toList();

        assertTrue(names.contains("read") && names.contains("list_chats"), names.toString());
        // A conversation answered by its runner is not offered the tools that drive it (ask_chat, ask_person ...).
        assertFalse(names.contains("ask_chat"), names.toString());
        assertEquals(names.stream().distinct().count(), names.size());
        assertTrue(scopes.offeredTo("project1/chat-99").isEmpty());
        assertTrue(HeadBlock.chatUiMcpPart(scopes.offeredTo("project1/chat-01")).chars() > 0);
    }
}
