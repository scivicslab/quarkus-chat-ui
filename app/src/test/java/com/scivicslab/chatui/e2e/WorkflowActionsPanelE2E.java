package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E: the Workflow tab describes each step's action beside the editor, from the action catalog
 * (ActionCatalogWithJavadoc_260930_oo01): the method's Javadoc sentence, the record's fields with
 * required marks, and a problem line when a required key is missing from arguments.
 */
class WorkflowActionsPanelE2E extends E2eTestBase {

    private void waitForReady() {
        page.navigate(baseUrl());
        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
        page.evaluate("() => { Object.keys(localStorage).forEach(function (k) {"
                + " if (k.indexOf('chat-ui-wf-') === 0) localStorage.removeItem(k); }); }");
    }

    @Test
    @DisplayName("Workflow tab: the actions panel shows what each step's action does and takes")
    void actionsPanel_describesSteps() {
        waitForReady();
        page.locator("#right-tab-bar .rtab-btn[data-tab='workflow']").click();
        page.locator("#wf-yaml").waitFor();
        page.locator("#wf-yaml").fill(String.join("\n",
                "name: e2e",
                "steps:",
                "  - states: [\"0\", \"end\"]",
                "    actions:",
                "      - actor: harness",
                "        method: check",
                "        arguments: {question: \"Is it ready?\"}",
                "      - actor: queue",
                "        method: enqueue",
                "        arguments: {wrong: \"x\"}",
                ""));
        page.waitForFunction(
                "() => document.querySelectorAll('#wf-actions .wf-action').length === 2",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
        String text = page.locator("#wf-actions").textContent();
        assertTrue(text.contains("harness.check"), text);
        assertTrue(text.contains("Sends one question to be answered YES or NO"), text);
        assertTrue(text.contains("question (string, required)"), text);
        assertTrue(text.contains("queue.enqueue"), text);
        assertTrue(text.contains("missing the required key text"), text);
    }
}
