package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E tests that verify SSE connection establishment and
 * status indicator behavior.
 */
class ConnectionIT extends E2eTestBase {

    @Test
    @DisplayName("SSE connection establishes and status shows ready")
    void connection_sseConnects_statusShowsReady() {
        page.navigate(baseUrl());
        // Wait for SSE to connect — status text changes to "ready"
        Locator status = page.locator("#connection-status");
        status.waitFor(new Locator.WaitForOptions().setTimeout(10000));
        // The connection-status element should eventually have class "connected"
        // or its text content should indicate ready state
        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
        String statusText = status.textContent();
        assertTrue(
                statusText.contains("ready") || statusText.contains("connected"),
                "Connection status should show ready/connected, got: " + statusText);
    }

    @Test
    @DisplayName("API config endpoint returns valid JSON")
    void connection_configEndpoint_returnsJson() {
        page.navigate(baseUrl());
        String configJson = (String) page.evaluate(
                "async () => {"
                        + "  const resp = await fetch('/api/config');"
                        + "  return await resp.text();"
                        + "}");
        assertTrue(configJson.contains("\"title\""), "Config should contain title field: " + configJson);
        assertTrue(configJson.contains("\"providerId\""), "Config should contain providerId field: " + configJson);
    }

    @Test
    @DisplayName("API status endpoint returns valid JSON")
    void connection_statusEndpoint_returnsJson() {
        page.navigate(baseUrl());
        String statusJson = (String) page.evaluate(
                "async () => {"
                        + "  const resp = await fetch('/api/status');"
                        + "  return await resp.text();"
                        + "}");
        assertTrue(statusJson.contains("\"type\""), "Status should contain type field: " + statusJson);
    }

    @Test
    @DisplayName("API models endpoint returns array")
    void connection_modelsEndpoint_returnsArray() {
        page.navigate(baseUrl());
        String modelsJson = (String) page.evaluate(
                "async () => {"
                        + "  const resp = await fetch('/api/models');"
                        + "  return await resp.text();"
                        + "}");
        assertTrue(modelsJson.startsWith("["), "Models response should be a JSON array: " + modelsJson);
    }
}
