package com.scivicslab.chatui.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E regression tests for the bug where {@code chat-ui.multi-user=true} activated
 * the multi-user login screen even when the provider was {@code claude} or {@code codex}.
 *
 * <p>Multi-user mode is only meaningful for {@code openai-compat}. CLI providers manage
 * sessions inside a single process and cannot share that process across users. When a CLI
 * provider is active, the server must report {@code multiUser=false} from {@code /api/config}
 * and the browser must show the chat UI directly — never the login screen.</p>
 *
 * <p>These tests require the server to be running with {@code chat-ui.provider=claude}
 * and {@code chat-ui.multi-user=true}. Use the {@code e2e-cli-multiuser-flag} Maven
 * profile, which starts such an instance on port 28012.</p>
 */
class CliProviderMultiUserFlagIgnoredE2E extends E2eTestBase {

    @BeforeEach
    void navigateToApp() {
        page.navigate(baseUrl());
        // Wait for /api/config fetch to complete and JS to update the DOM
        page.waitForFunction("() => document.readyState === 'complete'");
        page.waitForTimeout(500);
    }

    // ------------------------------------------------------------------ //
    // /api/config must report multiUser=false for CLI providers            //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Config endpoint returns multiUser=false for claude provider regardless of the multi-user flag")
    void config_claudeWithMultiUserFlag_returnsMultiUserFalse() {
        String configJson = (String) page.evaluate(
                "async () => { const r = await fetch('/api/config'); return await r.text(); }");
        assertTrue(configJson.contains("\"multiUser\":false"),
                "Config must report multiUser=false for CLI providers. Got: " + configJson);
    }

    // ------------------------------------------------------------------ //
    // Login screen must never appear for CLI providers                     //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Login screen is hidden when claude provider is active (even with multi-user flag)")
    void loginScreen_claudeWithMultiUserFlag_isHidden() {
        boolean loginHidden = (Boolean) page.evaluate(
                "() => { var el = document.querySelector('#login-screen');"
                        + " return !el || el.style.display === 'none' || el.style.display === ''; }");
        // The login-screen div starts as display:flex in HTML; JS sets it to 'none' in single-user mode.
        // We check it is not visible as flex.
        assertThat(page.locator("#login-screen")).not().isVisible();
    }

    // ------------------------------------------------------------------ //
    // Chat UI must appear directly without login                           //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Chat app is visible without login when claude provider is active")
    void app_claudeWithMultiUserFlag_isVisibleWithoutLogin() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        assertThat(page.locator("#app")).isVisible();
    }

    @Test
    @DisplayName("Prompt input is accessible without login when claude provider is active")
    void promptInput_claudeWithMultiUserFlag_isAccessible() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        assertThat(page.locator("#prompt-input")).isVisible();
        assertThat(page.locator("#prompt-input")).isEnabled();
    }

    // ------------------------------------------------------------------ //
    // Multi-user UI elements must be absent in single-user mode            //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("User label is hidden in single-user mode")
    void userLabel_claudeWithMultiUserFlag_isHidden() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        boolean labelHidden = (Boolean) page.evaluate(
                "() => { var el = document.querySelector('#user-label');"
                        + " return !el || el.style.display === 'none' || el.style.display === ''; }");
        assertTrue(labelHidden, "#user-label should be hidden in single-user mode");
    }

    @Test
    @DisplayName("Logout button is hidden in single-user mode")
    void logoutButton_claudeWithMultiUserFlag_isHidden() {
        page.waitForFunction(
                "() => document.querySelector('#app').style.display !== 'none'",
                null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(8000));
        boolean logoutHidden = (Boolean) page.evaluate(
                "() => { var el = document.querySelector('#logout-btn');"
                        + " return !el || el.style.display === 'none' || el.style.display === ''; }");
        assertTrue(logoutHidden, "#logout-btn should be hidden in single-user mode");
    }
}
