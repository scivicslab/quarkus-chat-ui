package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E: with the openai-compat provider, the Agent Loop tab lists the bundled inner-loop workflow,
 * marks the one in effect, and draws its steps as boxes. No LLM server is needed for this.
 */
class AgentLoopTabE2E extends E2eTestBase {

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
    }

    @Test
    @DisplayName("Agent Loop tab: the loop in effect is listed and its steps are drawn as boxes")
    void agentLoopTab_showsTheLoopInEffect() {
        waitForReady();
        page.locator("#right-tab-bar .rtab-btn[data-tab='agentloop']").click();

        page.waitForFunction(
                "() => document.querySelectorAll('#al-list .wf-box').length >= 5",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));

        Locator select = page.locator("#al-select");
        assertTrue(select.inputValue().equals("agent-loop-react"), "the loop in effect is selected");
        String titles = page.locator("#al-list .wf-box-title").allTextContents().toString();
        assertTrue(titles.contains("think"), "the think step is drawn: " + titles);
        assertTrue(page.locator("#al-status").textContent().contains("step(s)"));
    }
}
