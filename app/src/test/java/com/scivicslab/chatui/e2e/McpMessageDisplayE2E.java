package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * E2E tests verifying that MCP-received messages are displayed in the chat area.
 *
 * <p>Bug being tested: When a message is received via MCP (submitPrompt),
 * the message content should be visible in the chat area so the human user
 * can see what was sent. Previously, only "[MCP from xxx] submitPrompt"
 * notification was shown without the actual message content.</p>
 */
class McpMessageDisplayE2E extends E2eTestBase {

    private static final String TEST_MESSAGE = "E2E_MCP_TEST_MESSAGE_" + System.currentTimeMillis();

    /** Waits for SSE connection and ensures the page is ready for interaction. */
    private void waitForReady() {
        page.navigate(baseUrl());
        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
        // Dismiss auth overlay if visible
        page.evaluate(
                "() => { var el = document.querySelector('#auth-overlay');"
                        + " if (el && el.style.display !== 'none') el.style.display = 'none'; }");
    }

    /**
     * Sends a message via MCP protocol to the target chat-ui instance.
     *
     * @param targetUrl the base URL of the target chat-ui (e.g., http://localhost:28010)
     * @param message   the message content to send
     * @param caller    the caller identifier (e.g., http://localhost:28011)
     * @return the HTTP response body
     */
    private String sendMcpMessage(String targetUrl, String message, String caller) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String mcpEndpoint = targetUrl + "/mcp";

        // Step 1: Initialize MCP session
        String initRequest = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "capabilities":{},
                    "clientInfo":{"name":"e2e-test","version":"1.0"}
                }}""";

        HttpRequest initReq = HttpRequest.newBuilder()
                .uri(URI.create(mcpEndpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initRequest))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
        if (initResp.statusCode() != 200) {
            throw new RuntimeException("MCP initialize failed: " + initResp.statusCode() + " " + initResp.body());
        }

        String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sessionId == null) {
            throw new RuntimeException("No Mcp-Session-Id in response headers");
        }

        // Step 2: Call submitPrompt tool
        String escapedMessage = message.replace("\"", "\\\"").replace("\n", "\\n");
        String escapedCaller = caller.replace("\"", "\\\"");
        String toolsCallRequest = String.format("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                    "name":"submitPrompt",
                    "arguments":{
                        "prompt":"%s",
                        "model":"sonnet",
                        "_caller":"%s"
                    }
                }}""", escapedMessage, escapedCaller);

        HttpRequest toolsReq = HttpRequest.newBuilder()
                .uri(URI.create(mcpEndpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(toolsCallRequest))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> toolsResp = client.send(toolsReq, HttpResponse.BodyHandlers.ofString());
        return toolsResp.body();
    }

    @Test
    @DisplayName("MCP received message content should appear in chat area")
    void mcpMessage_contentAppearsInChatArea() throws Exception {
        waitForReady();

        // Record initial message count
        int initialCount = page.locator("#chat-area .message").count();

        // Send a message via MCP to this chat-ui instance
        String caller = "http://localhost:99999";  // fake caller for test
        String response = sendMcpMessage(baseUrl(), TEST_MESSAGE, caller);

        // Wait for the message to appear in chat area
        // The message content should be visible, not just "[MCP from xxx] submitPrompt"
        try {
            page.waitForFunction(
                    "() => document.querySelector('#chat-area').textContent.includes('" + TEST_MESSAGE + "')",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(15000));
        } catch (Exception e) {
            // Dump chat area content for debugging
            String chatContent = page.locator("#chat-area").textContent();
            fail("MCP message content '" + TEST_MESSAGE + "' not found in chat area.\n"
                    + "Chat area content: " + chatContent + "\n"
                    + "MCP response: " + response);
        }

        // Verify the message appears
        String chatContent = page.locator("#chat-area").textContent();
        assertTrue(chatContent.contains(TEST_MESSAGE),
                "Chat area should contain the MCP message content: " + TEST_MESSAGE);

        // Verify it is displayed as a user message (not just an info notification)
        Locator mcpUserMsg = page.locator("#chat-area .message.user.mcp-user");
        assertTrue(mcpUserMsg.count() > 0,
                "MCP message should be displayed as a user message with mcp-user class");
    }

    @Test
    @DisplayName("MCP sender info should appear in chat area")
    void mcpMessage_senderInfoAppearsInChatArea() throws Exception {
        waitForReady();

        String callerHost = "localhost:55555";
        String caller = "http://" + callerHost;
        String uniqueMessage = "SENDER_TEST_" + System.currentTimeMillis();

        sendMcpMessage(baseUrl(), uniqueMessage, caller);

        // Wait for MCP notification to appear (this should already work)
        try {
            page.waitForFunction(
                    "() => document.querySelector('#chat-area').textContent.includes('[MCP from')",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(10000));
        } catch (Exception e) {
            String chatContent = page.locator("#chat-area").textContent();
            fail("MCP sender notification not found in chat area.\n"
                    + "Chat area content: " + chatContent);
        }

        // Verify sender info appears
        String chatContent = page.locator("#chat-area").textContent();
        assertTrue(chatContent.contains("[MCP from"),
                "Chat area should show MCP sender notification");
    }
}
