package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E tests for queue functionality.
 *
 * <p>Verifies that messages can be added to the queue, displayed in the queue area,
 * and sent to the LLM when processed. Ensures that queued messages appear in the
 * chat area when executed.</p>
 */
class QueueE2E extends E2eTestBase {

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
     * Waits for the chat area to contain at least one message of any type.
     */
    private void waitForAnyMessage() {
        page.waitForFunction(
                "() => document.querySelectorAll('#chat-area .message').length > 0",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
    }

    /**
     * Waits for the queue area to become visible.
     */
    private void waitForQueueVisible() {
        page.waitForFunction(
                "() => { var el = document.querySelector('#queue-area');"
                        + " return el && el.style.display !== 'none' && el.offsetHeight > 0; }",
                null,
                new Page.WaitForFunctionOptions().setTimeout(5000));
    }

    @Test
    @DisplayName("Clicking queue button with text adds message to queue")
    void queue_addMessage_messageAppearsInQueue() {
        waitForReady();

        Locator input = page.locator("#prompt-input");
        input.fill("Queued test message");
        page.locator("#queue-btn").click();

        // Queue area should become visible
        waitForQueueVisible();

        // Check that the message appears in the queue
        Locator queueItems = page.locator("#queue-area .queue-item");
        assertTrue(queueItems.count() > 0, "Queue should contain at least one item");

        String queueText = queueItems.last().textContent();
        assertTrue(queueText.contains("Queued test message"),
                "Queue item should contain the message text, got: " + queueText);
    }

    @Test
    @DisplayName("Clicking queue button without text toggles queue visibility")
    void queue_toggleVisibility_queueShowsAndHides() {
        waitForReady();

        Locator queueBtn = page.locator("#queue-btn");
        Locator queueArea = page.locator("#queue-area");

        // First click - should show queue
        queueBtn.click();
        page.waitForTimeout(500);
        String displayStyle = (String) queueArea.evaluate("el => getComputedStyle(el).display");
        assertTrue(!displayStyle.equals("none"), "Queue should be visible after first click");

        // Second click - should hide queue
        queueBtn.click();
        page.waitForTimeout(500);
        displayStyle = (String) queueArea.evaluate("el => getComputedStyle(el).display");
        assertTrue(displayStyle.equals("none"), "Queue should be hidden after second click");
    }

    // Disabled: requires actual LLM response, not suitable for mvn install
    // @Test
    @DisplayName("Auto mode queue message is sent and appears in chat area")
    void disabled_queue_autoMode_messageAppearsInChatArea() {
        waitForReady();

        // Add a message to queue with auto mode
        Locator input = page.locator("#prompt-input");
        input.fill("Auto queue message");
        page.locator("#queue-btn").click();

        waitForQueueVisible();

        // Find the queue item and enable auto mode by clicking the toggle
        Locator queueItems = page.locator("#queue-area .queue-item");
        assertTrue(queueItems.count() > 0, "Queue should have at least one item");

        // Click the auto toggle (usually a checkbox or button within the queue item)
        // The exact selector depends on your UI structure
        Locator autoToggle = queueItems.last().locator(".auto-toggle");
        if (autoToggle.count() > 0) {
            autoToggle.click();
        }

        // Trigger queue processing by clicking send button with empty input
        // (this processes the queue when not busy)
        page.locator("#prompt-input").fill("");
        page.locator("#send-btn").click();

        // Wait for message to appear in chat area
        waitForAnyMessage();

        // Verify user message appears in chat area
        Locator userMessages = page.locator("#chat-area .message.user");
        if (userMessages.count() > 0) {
            String messageText = userMessages.last().textContent();
            assertTrue(messageText.contains("Auto queue message"),
                    "User message should appear in chat area, got: " + messageText);
        } else {
            // At least some message should appear
            Locator anyMessage = page.locator("#chat-area .message");
            assertTrue(anyMessage.count() > 0,
                    "At least one message should appear after processing queue");
        }
    }

    // Disabled: requires actual LLM response, not suitable for mvn install
    // @Test
    @DisplayName("Multiple messages can be queued and processed in order")
    void disabled_queue_multipleMessages_processedInOrder() {
        waitForReady();

        // Add multiple messages to queue
        String[] messages = {"First message", "Second message", "Third message"};
        Locator input = page.locator("#prompt-input");

        for (String msg : messages) {
            input.fill(msg);
            page.locator("#queue-btn").click();
            page.waitForTimeout(200);
        }

        waitForQueueVisible();

        // Verify all messages are in the queue
        Locator queueItems = page.locator("#queue-area .queue-item");
        assertTrue(queueItems.count() >= messages.length,
                "Queue should contain at least " + messages.length + " items");

        // Enable auto mode for all queue items
        for (int i = 0; i < messages.length; i++) {
            Locator item = queueItems.nth(i);
            Locator autoToggle = item.locator(".auto-toggle");
            if (autoToggle.count() > 0 && !autoToggle.isChecked()) {
                autoToggle.click();
            }
        }

        // Start processing queue
        page.locator("#prompt-input").fill("");
        page.locator("#send-btn").click();

        // Wait for messages to appear
        page.waitForTimeout(1000);

        // Verify at least one message appeared (full processing may take time)
        Locator userMessages = page.locator("#chat-area .message.user");
        assertTrue(userMessages.count() > 0,
                "At least one queued message should appear in chat area");
    }

    @Test
    @DisplayName("Queue persists across page reload")
    void queue_pageReload_queuePersists() {
        waitForReady();

        // Add a message to queue
        Locator input = page.locator("#prompt-input");
        input.fill("Persistent queue message");
        page.locator("#queue-btn").click();

        waitForQueueVisible();

        // Reload the page
        page.reload();
        waitForReady();

        // After reload, restoreQueue() auto-runs and shows queue if non-empty
        // So queue area should already be visible - no need to click queue-btn
        waitForQueueVisible();

        // Verify message is still in queue
        Locator queueItems = page.locator("#queue-area .queue-item");
        assertTrue(queueItems.count() > 0, "Queue should persist after reload");

        String queueText = queueItems.last().textContent();
        assertTrue(queueText.contains("Persistent queue message"),
                "Persisted queue item should contain the original message");
    }

    // NOTE: Cancel functionality (clearMcpMessages) is tested in backend unit tests (QueueActorTest)
    // E2E testing of cancel requires actual LLM processing, which is not reliable in E2E context
}
