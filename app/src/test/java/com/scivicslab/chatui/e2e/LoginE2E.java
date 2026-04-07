package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the multi-user login screen.
 *
 * <p>These tests require the server to be running in multi-user mode
 * ({@code chat-ui.multi-user=true}). Use the {@code e2e-multi-user} Maven
 * profile which starts a local instance with that flag enabled.</p>
 *
 * <p>Each test gets a fresh browser context (isolated localStorage) so login
 * state does not bleed between test cases.</p>
 */
class LoginE2E extends E2eTestBase {

    @BeforeEach
    void navigateToApp() {
        page.navigate(baseUrl());
    }

    // ------------------------------------------------------------------ //
    // Initial state: login screen visible, app hidden                     //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Login screen is visible on initial load (multi-user mode)")
    void login_initialLoad_loginScreenVisible() {
        Locator loginScreen = page.locator("#login-screen");
        assertThat(loginScreen).isVisible();
    }

    @Test
    @DisplayName("App area is hidden before login")
    void login_initialLoad_appHidden() {
        // Wait a moment for JS to run
        page.waitForFunction("() => document.readyState === 'complete'");
        boolean appHidden = (Boolean) page.evaluate(
                "() => { var el = document.querySelector('#app');"
                        + " return !el || el.style.display === 'none'; }");
        assertTrue(appHidden, "#app should be hidden before login");
    }

    @Test
    @DisplayName("Login form is present with username input and submit button")
    void login_initialLoad_formElementsPresent() {
        assertThat(page.locator("#login-user")).isVisible();
        assertThat(page.locator("#login-form button[type='submit']")).isVisible();
    }

    // ------------------------------------------------------------------ //
    // Login flow                                                           //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Entering username and submitting shows the app")
    void login_submit_showsApp() {
        page.locator("#login-user").fill("testuser");
        page.locator("#login-form").locator("button[type='submit']").click();

        // App should become visible
        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));
        assertThat(page.locator("#app")).isVisible();
    }

    @Test
    @DisplayName("Login screen is hidden after successful login")
    void login_submit_hidesLoginScreen() {
        page.locator("#login-user").fill("alice");
        page.locator("#login-form").locator("button[type='submit']").click();

        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        boolean loginHidden = (Boolean) page.evaluate(
                "() => { var el = document.querySelector('#login-screen');"
                        + " return !el || el.style.display === 'none'; }");
        assertTrue(loginHidden, "#login-screen should be hidden after login");
    }

    @Test
    @DisplayName("Username is displayed in user-label after login")
    void login_submit_userLabelShowsUsername() {
        String username = "alice";
        page.locator("#login-user").fill(username);
        page.locator("#login-form").locator("button[type='submit']").click();

        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        String labelText = page.locator("#user-label").textContent();
        assertEquals(username, labelText.trim(),
                "user-label should show the logged-in username");
    }

    @Test
    @DisplayName("Logout button is visible after login")
    void login_submit_logoutButtonVisible() {
        page.locator("#login-user").fill("bob");
        page.locator("#login-form").locator("button[type='submit']").click();

        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        assertThat(page.locator("#logout-btn")).isVisible();
    }

    @Test
    @DisplayName("Empty username does not trigger login")
    void login_emptyUsername_doesNotLogin() {
        page.locator("#login-user").fill("");
        page.locator("#login-form").locator("button[type='submit']").click();

        // login-screen should remain visible
        assertThat(page.locator("#login-screen")).isVisible();
        boolean appHidden = (Boolean) page.evaluate(
                "() => { var el = document.querySelector('#app');"
                        + " return !el || el.style.display === 'none'; }");
        assertTrue(appHidden, "#app should remain hidden when username is empty");
    }

    // ------------------------------------------------------------------ //
    // SSE connection after login                                           //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("SSE connects with user parameter after login")
    void login_afterLogin_sseConnects() {
        page.locator("#login-user").fill("carol");
        page.locator("#login-form").locator("button[type='submit']").click();

        // Wait for SSE to establish
        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        String statusText = page.locator("#connection-status").textContent();
        assertTrue(statusText.contains("ready") || statusText.contains("connected"),
                "SSE should connect after login, got: " + statusText);
    }

    // ------------------------------------------------------------------ //
    // Logout flow                                                          //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Clicking logout reloads to login screen")
    void login_logout_showsLoginScreen() {
        // Login first
        page.locator("#login-user").fill("dave");
        page.locator("#login-form").locator("button[type='submit']").click();
        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        // Logout
        page.locator("#logout-btn").click();

        // Wait for page reload and login screen
        page.waitForFunction("() => document.querySelector('#login-screen') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));
        assertThat(page.locator("#login-screen")).isVisible();
    }

    @Test
    @DisplayName("Logout clears stored username from localStorage")
    void login_logout_clearsLocalStorage() {
        // Login
        page.locator("#login-user").fill("eve");
        page.locator("#login-form").locator("button[type='submit']").click();
        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        // Verify localStorage has the user
        String stored = (String) page.evaluate("() => localStorage.getItem('chat-ui-user')");
        assertEquals("eve", stored, "localStorage should contain username after login");

        // Logout and wait for reload
        page.locator("#logout-btn").click();
        page.waitForFunction("() => document.querySelector('#login-screen') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        // localStorage should be cleared
        String afterLogout = (String) page.evaluate("() => localStorage.getItem('chat-ui-user')");
        assertNull(afterLogout, "localStorage should be cleared after logout");
    }

    // ------------------------------------------------------------------ //
    // localStorage persistence                                             //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Returning user is auto-logged in from localStorage")
    void login_returningUser_autoLogin() {
        // Pre-seed localStorage with a username (simulates previous login)
        page.evaluate("() => localStorage.setItem('chat-ui-user', 'returning-user')");

        // Reload to trigger config check
        page.reload();

        // Should skip login screen and show app directly
        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(8000));

        assertThat(page.locator("#app")).isVisible();
        boolean loginHidden = (Boolean) page.evaluate(
                "() => { var el = document.querySelector('#login-screen');"
                        + " return !el || el.style.display === 'none'; }");
        assertTrue(loginHidden, "Login screen should be hidden for returning user");
    }

    @Test
    @DisplayName("Username stored in localStorage after login")
    void login_submit_storesUsernameInLocalStorage() {
        page.locator("#login-user").fill("frank");
        page.locator("#login-form").locator("button[type='submit']").click();

        page.waitForFunction("() => document.querySelector('#app').style.display !== 'none'",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        String stored = (String) page.evaluate("() => localStorage.getItem('chat-ui-user')");
        assertEquals("frank", stored, "Username should be persisted in localStorage");
    }

    // ------------------------------------------------------------------ //
    // API config confirms multi-user mode                                  //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Config endpoint returns multiUser=true")
    void login_config_multiUserTrue() {
        String configJson = (String) page.evaluate(
                "async () => { const r = await fetch('/api/config'); return await r.text(); }");
        assertTrue(configJson.contains("\"multiUser\":true"),
                "Config should report multiUser=true, got: " + configJson);
    }
}
