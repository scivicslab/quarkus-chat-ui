package com.scivicslab.chatui.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the documented openai-compat uber-jar startup (single-user mode).
 *
 * <p>Verifies the command documented in ChatUiJarDownloadLocal_260408_oo01:
 * <pre>
 *   java -Dchat-ui.provider=openai-compat
 *        -Dchat-ui.servers=http://localhost:19080
 *        -Dquarkus.http.port=28016
 *        -jar chat-ui.jar
 * </pre>
 * A {@link MockVllmServer} is started in-process before the tests run.
 * No external processes or Python scripts are needed.</p>
 *
 * <p>Run with: {@code mvn verify -Pe2e-openai-compat-smoke}</p>
 */
class OpenAiCompatSmokeIT extends E2eTestBase {

    private static MockVllmServer mockVllm;

    @BeforeAll
    static void startMockVllm() throws IOException {
        mockVllm = new MockVllmServer(19080);
        mockVllm.start();
    }

    @AfterAll
    static void stopMockVllm() {
        if (mockVllm != null) {
            mockVllm.stop();
        }
    }

    @BeforeEach
    void navigate() {
        page.navigate(baseUrl());
        page.waitForFunction("() => document.readyState === 'complete'");
        page.waitForTimeout(500);
    }

    @Test
    @DisplayName("Root page returns HTTP 200 — openai-compat uber-jar starts successfully")
    void rootPage_returns200() {
        var response = page.waitForResponse(
                r -> r.url().equals(baseUrl() + "/") || r.url().equals(baseUrl()),
                () -> page.navigate(baseUrl()));
        assertTrue(response.status() < 400,
                "Expected HTTP 2xx/3xx from root, got: " + response.status());
    }

    @Test
    @DisplayName("/api/config reports providerId=openai-compat and multiUser=false")
    void config_reportsOpenAiCompatSingleUser() {
        String configJson = (String) page.evaluate(
                "async () => { const r = await fetch('/api/config'); return await r.text(); }");
        assertTrue(configJson.contains("\"providerId\":\"openai-compat\""),
                "Expected providerId=openai-compat in config. Got: " + configJson);
        assertTrue(configJson.contains("\"multiUser\":false"),
                "Expected multiUser=false in config. Got: " + configJson);
    }

    @Test
    @DisplayName("Chat UI is visible without login screen — single-user openai-compat")
    void chatUi_visibleWithoutLogin() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        assertThat(page.locator("#app")).isVisible();
        assertThat(page.locator("#login-screen")).not().isVisible();
    }

    @Test
    @DisplayName("Prompt input is accessible — openai-compat single-user fully functional")
    void promptInput_isAccessible() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        assertThat(page.locator("#prompt-input")).isVisible();
        assertThat(page.locator("#prompt-input")).isEnabled();
    }
}
