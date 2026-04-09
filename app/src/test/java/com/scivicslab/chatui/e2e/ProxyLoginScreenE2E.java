package com.scivicslab.chatui.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E regression tests for the bug where chat-ui accessed via the service-portal
 * reverse proxy showed the login screen instead of the chat UI.
 *
 * <p>Root cause: ProxyRoute stripped {@code content-length} from upstream responses
 * but did not set {@code transfer-encoding: chunked} when the upstream had a known
 * content-length. Vert.x sent an empty body, so {@code fetch('api/config')} in
 * app.js received no JSON, preventing {@code startApp('')} from being called.</p>
 *
 * <p>These tests require:
 * <ul>
 *   <li>service-portal running on port 28080 with backend=jvm</li>
 *   <li>quarkus-chat-ui launched via service-portal on port 28100 with provider=claude</li>
 * </ul>
 * Run with: {@code mvn test -pl app -Dtest=ProxyLoginScreenE2E
 * -Dchat-ui.e2e.base-url=http://localhost:28080/proxy/quarkus-chat-ui/28100}</p>
 */
class ProxyLoginScreenE2E extends E2eTestBase {

    private static final String PROXY_BASE_URL =
            System.getProperty("proxy.e2e.base-url",
                    "http://localhost:28080/proxy/quarkus-chat-ui/28100");

    String proxyUrl() {
        String url = PROXY_BASE_URL;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @BeforeEach
    void navigateAndWait() {
        page.navigate(proxyUrl() + "/");
        page.waitForFunction("() => document.readyState === 'complete'");
        // Wait for fetch('api/config') to resolve and JS to update DOM
        page.waitForFunction(
                "() => document.querySelector('#login-screen').style.display === 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(5000));
    }

    @Test
    @DisplayName("api/config returns multiUser=false and providerId=claude via proxy")
    void config_viaPoxy_returnsClaudeSingleUser() {
        String cfg = (String) page.evaluate(
                "async () => { const r = await fetch('api/config'); return await r.text(); }");
        assertTrue(cfg.contains("\"multiUser\":false"),
                "Expected multiUser=false via proxy, got: " + cfg);
        assertTrue(cfg.contains("\"providerId\":\"claude\""),
                "Expected providerId=claude via proxy, got: " + cfg);
    }

    @Test
    @DisplayName("Login screen is hidden when accessing via proxy with provider=claude")
    void loginScreen_viaProxy_isHidden() {
        assertThat(page.locator("#login-screen")).not().isVisible();
    }

    @Test
    @DisplayName("Chat app is visible without login when accessing via proxy")
    void app_viaProxy_isVisible() {
        assertThat(page.locator("#app")).isVisible();
    }

    @Test
    @DisplayName("Prompt input is accessible when accessing via proxy")
    void promptInput_viaProxy_isAccessible() {
        assertThat(page.locator("#prompt-input")).isVisible();
        assertThat(page.locator("#prompt-input")).isEnabled();
    }
}
