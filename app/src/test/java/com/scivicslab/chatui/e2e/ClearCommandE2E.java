package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * E2E test: /clear command resets the session ID.
 *
 * Verifies three things in order:
 *   1. A session ID appears in #session-label after a successful LLM turn.
 *   2. The session label is empty immediately after /clear.
 *   3. The next LLM turn establishes a new session ID distinct from the original.
 *
 * Run against a live instance:
 *   mvn exec:java -pl app -Dexec.mainClass=com.scivicslab.chatui.e2e.ClearCommandE2E
 *   mvn exec:java -pl app -Dexec.mainClass=com.scivicslab.chatui.e2e.ClearCommandE2E \
 *       -Dchat-ui.e2e.base-url=http://localhost:28900
 */
public class ClearCommandE2E {

    private static final String BASE_URL =
            System.getProperty("chat-ui.e2e.base-url", "http://localhost:28900");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SESSION_TIMEOUT_MS  = 30_000;

    public static void main(String[] args) {
        System.out.println("ClearCommandE2E: start");
        new ClearCommandE2E().run();
        System.out.println("ClearCommandE2E: PASS");
    }

    public void run() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                runTest(browser);
            } finally {
                browser.close();
            }
        }
    }

    private void runTest(Browser browser) {
        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        // ── Step 1: Navigate and wait for SSE connection ──────────────────
        page.navigate(BASE_URL);
        page.waitForFunction(
                "() => document.querySelector('#connection-status')"
                        + "?.classList.contains('connected')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(CONNECT_TIMEOUT_MS));

        // Dismiss API-key overlay when the provider requires manual key entry.
        page.evaluate("() => {"
                + "var el = document.querySelector('#auth-overlay');"
                + "if (el && el.style.display !== 'none') el.style.display = 'none';"
                + "}");

        // ── Step 2: Send a prompt to establish a session ──────────────────
        page.locator("#prompt-input").fill("hello");
        page.locator("#send-btn").click();

        // Wait until #session-label is populated by the LLM result event.
        page.waitForFunction(
                "() => document.getElementById('session-label').textContent.length > 0",
                null,
                new Page.WaitForFunctionOptions().setTimeout(SESSION_TIMEOUT_MS));

        String sessionBefore = (String) page.evaluate(
                "() => document.getElementById('session-label').textContent");
        System.out.println("  session before /clear : " + sessionBefore);

        // ── Step 3: Execute /clear ────────────────────────────────────────
        page.locator("#prompt-input").fill("/clear");
        page.locator("#send-btn").click();

        // Wait for the "Session cleared" info message in the chat area.
        page.waitForFunction(
                "() => Array.from(document.querySelectorAll('#chat-area .message'))"
                        + ".some(m => m.textContent.includes('Session cleared'))",
                null,
                new Page.WaitForFunctionOptions().setTimeout(5_000));

        // Assert: session label must be empty right after /clear.
        String sessionAfterClear = (String) page.evaluate(
                "() => document.getElementById('session-label').textContent");
        System.out.println("  session after /clear  : \"" + sessionAfterClear + "\"");
        if (sessionAfterClear != null && !sessionAfterClear.isEmpty()) {
            throw new AssertionError(
                    "clearCommand_withActiveSession_sessionLabelIsEmpty FAILED: "
                            + "expected empty session label after /clear, got: "
                            + sessionAfterClear);
        }

        // ── Step 4: Send another prompt to start a new session ────────────
        page.locator("#prompt-input").fill("hello again");
        page.locator("#send-btn").click();

        // Wait until a new session ID appears.
        page.waitForFunction(
                "() => document.getElementById('session-label').textContent.length > 0",
                null,
                new Page.WaitForFunctionOptions().setTimeout(SESSION_TIMEOUT_MS));

        String sessionAfterNew = (String) page.evaluate(
                "() => document.getElementById('session-label').textContent");
        System.out.println("  session after new turn: " + sessionAfterNew);

        // Assert: new session ID must differ from the original.
        if (sessionBefore.equals(sessionAfterNew)) {
            throw new AssertionError(
                    "clearCommand_withActiveSession_newSessionIdDiffersFromOriginal FAILED: "
                            + "session ID did not change after /clear, still: "
                            + sessionAfterNew);
        }

        context.close();
    }
}
