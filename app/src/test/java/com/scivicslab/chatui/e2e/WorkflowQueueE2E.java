package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E: a Turing Workflow YAML written in the Workflow tab is queued as a workflow item, runs when
 * its turn comes (POST /api/workflows/run-yaml), and its {@code queue.enqueue} action reaches the
 * browser's queue through the {@code queue_add} SSE event. No LLM is involved: the workflow only
 * uses the {@code queue} actor, and the run ends with the runner's terminal result event.
 */
class WorkflowQueueE2E extends E2eTestBase {

    private static final String YAML = String.join("\n",
            "name: e2e-enqueue",
            "steps:",
            "  - states: [\"0\", \"end\"]",
            "    actions:",
            "      - actor: queue",
            "        method: enqueue",
            "        arguments: \"E2E prompt enqueued by workflow\"",
            "");

    private void waitForReady() {
        page.navigate(baseUrl());
        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
        page.evaluate(
                "() => { var el = document.querySelector('#auth-overlay');"
                        + " if (el && el.style.display !== 'none') el.style.display = 'none'; }");
        // Start from an empty queue (localStorage may hold items from an earlier run).
        page.evaluate("() => { Object.keys(localStorage).forEach(function (k) {"
                + " if (k.indexOf('chat-ui-queue') === 0 || k.indexOf('chat-ui-wf-') === 0) localStorage.removeItem(k); }); }");
        page.reload();
        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
    }

    @Test
    @DisplayName("Workflow tab: Add to queue -> runs as a workflow -> queue.enqueue appends a prompt to the queue")
    void workflowFromEditor_isQueuedRunAndEnqueuesPrompt() {
        waitForReady();

        page.locator("#right-tab-bar .rtab-btn[data-tab='workflow']").click();
        Locator editor = page.locator("#wf-yaml");
        editor.waitFor(new Locator.WaitForOptions().setTimeout(5000));
        editor.fill(YAML);
        page.locator("#wf-queue").click();

        // The item shows as a workflow in the queue list.
        page.waitForFunction(
                "() => Array.from(document.querySelectorAll('#queue-area .queue-item .queue-text'))"
                        + ".some(function (el) { return el.textContent.indexOf('Workflow: e2e-enqueue') >= 0; })",
                null,
                new Page.WaitForFunctionOptions().setTimeout(5000));

        // It is an auto item and nothing is running, so it starts at once; the server runs the YAML
        // and its queue.enqueue action arrives as a queue_add event that appends a prompt item.
        page.waitForFunction(
                "() => Array.from(document.querySelectorAll('#queue-area .queue-item .queue-text'))"
                        + ".some(function (el) { return el.textContent.indexOf('E2E prompt enqueued by workflow') >= 0; })",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        // The workflow turn appears in the chat as a user message.
        Locator userMessages = page.locator("#chat-area .message.user");
        assertTrue(userMessages.count() > 0, "the workflow launch should appear in the chat");
        assertTrue(userMessages.first().textContent().contains("Workflow: e2e-enqueue"));

        // The run's terminal result event ended the busy state (the Cancel button is disabled again).
        page.waitForFunction(
                "() => document.querySelector('#cancel-btn').disabled === true",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
    }
}
