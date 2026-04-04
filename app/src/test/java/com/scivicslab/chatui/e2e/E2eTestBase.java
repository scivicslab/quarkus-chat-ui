package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for E2E integration tests using Java Playwright.
 *
 * <p>Tests connect to an externally running quarkus-chat-ui instance.
 * The base URL defaults to {@code http://localhost:28010} and can be
 * overridden via the {@code chat-ui.e2e.base-url} system property.</p>
 *
 * <p>Each test method gets a fresh {@link BrowserContext} (isolated
 * cookies, localStorage, session) so tests do not interfere with
 * each other.</p>
 */
abstract class E2eTestBase {

    private static final String DEFAULT_BASE_URL = "http://localhost:28010";

    static Playwright playwright;
    static Browser browser;

    BrowserContext context;
    Page page;

    /**
     * Returns the base URL of the running chat-ui instance.
     *
     * @return base URL without trailing slash
     */
    static String baseUrl() {
        String url = System.getProperty("chat-ui.e2e.base-url", DEFAULT_BASE_URL);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void createContext() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }
}
