package com.scivicslab.chatui.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the documented uber-jar startup workflow.
 *
 * <p>Verifies that the uber-jar starts correctly with the documented command:
 * {@code java -Dchat-ui.provider=claude -Dquarkus.http.port=<port> -jar chat-ui.jar}
 * and that the chat UI is reachable without a login screen.</p>
 *
 * <p>Run with the {@code e2e-uber-jar-smoke} Maven profile, which builds the
 * uber-jar and starts it on port 28015 before the tests execute.</p>
 */
class UberJarSmokeIT extends E2eTestBase {

    @BeforeEach
    void navigate() {
        page.navigate(baseUrl());
        page.waitForFunction("() => document.readyState === 'complete'");
        page.waitForTimeout(500);
    }

    @Test
    @DisplayName("Root page returns HTTP 200 — uber-jar starts successfully")
    void rootPage_uberJar_returns200() {
        var response = page.waitForResponse(r -> r.url().equals(baseUrl() + "/") || r.url().equals(baseUrl()),
                () -> page.navigate(baseUrl()));
        assertTrue(response.status() < 400,
                "Expected HTTP 2xx/3xx from root, got: " + response.status());
    }

    @Test
    @DisplayName("/api/config reports provider=claude and multiUser=false")
    void config_uberJar_reportsClaudeProviderSingleUser() {
        String configJson = (String) page.evaluate(
                "async () => { const r = await fetch('/api/config'); return await r.text(); }");
        assertTrue(configJson.contains("\"providerId\":\"claude\""),
                "Expected providerId=claude in config. Got: " + configJson);
        assertTrue(configJson.contains("\"multiUser\":false"),
                "Expected multiUser=false in config. Got: " + configJson);
    }

    @Test
    @DisplayName("Chat UI is visible without login screen")
    void chatUi_uberJar_visibleWithoutLogin() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        assertThat(page.locator("#app")).isVisible();
        assertThat(page.locator("#login-screen")).not().isVisible();
    }

    @Test
    @DisplayName("Prompt input is accessible — uber-jar fully functional")
    void promptInput_uberJar_isAccessible() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        assertThat(page.locator("#prompt-input")).isVisible();
        assertThat(page.locator("#prompt-input")).isEnabled();
    }
}
