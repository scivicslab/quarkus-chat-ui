package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;

/**
 * The Actions tab: naming an actor lists its actions, and naming an action shows what it does and what
 * it takes. Both answers come from the action catalog, so nothing has to be running in the chat.
 *
 * <p>Not a JUnit test: it drives a browser against an already running instance. Start one on the port
 * given by {@code chat-ui.e2e.port} (28020 by default) and run
 * {@code java -cp app/target/classes:app/target/test-classes:… com.scivicslab.chatui.e2e.ActionsTabE2E}.</p>
 */
public final class ActionsTabE2E {

    private ActionsTabE2E() {
    }

    /**
     * Drives the tab and prints what it found.
     * @param args unused
     */
    public static void main(String[] args) {
        String port = System.getProperty("chat-ui.e2e.port", "28020");
        String base = "http://localhost:" + port + "/";
        boolean ok = true;
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(base);
            page.waitForSelector("#right-tab-bar");

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Actions")).click();
            page.waitForSelector("#tab-actions.active");
            System.out.println("the Actions tab opens");

            // An actor name alone lists that actor's actions.
            page.fill("#act-actor", "harness");
            page.fill("#act-action", "");
            page.click("#act-show");
            page.waitForSelector("#act-list .act-item");
            Locator names = page.locator("#act-list button.act-head");
            int listed = names.count();
            System.out.println("harness lists " + listed + " actions");
            ok &= listed > 0;
            ok &= names.allInnerTexts().contains("send");
            System.out.println("the list holds send: " + names.allInnerTexts().contains("send"));

            // Clicking one shows its description and its fields.
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("send")).click();
            page.waitForSelector("#act-list .act-desc");
            String description = page.locator("#act-list .act-desc").first().innerText();
            System.out.println("send is described as: " + description);
            ok &= !description.isBlank();
            ok &= page.locator("#act-list .act-fields code").count() > 0;
            System.out.println("send shows " + page.locator("#act-list .act-fields code").count() + " fields");

            // An action no actor has is reported as a problem, not as an empty panel.
            page.fill("#act-action", "noSuchAction");
            page.click("#act-show");
            page.waitForSelector("#act-list .act-problem");
            System.out.println("an unknown action is reported: "
                    + page.locator("#act-list .act-problem").first().innerText());

            // The Workflow tab no longer carries the actions panel.
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Workflow")).click();
            page.waitForSelector("#tab-workflow.active");
            int panels = page.locator("#wf-actions").count();
            System.out.println("the Workflow tab holds " + panels + " actions panels");
            ok &= panels == 0;

            browser.close();
        }
        System.out.println(ok ? "E2E OK" : "E2E FAILED");
        System.exit(ok ? 0 : 1);
    }
}
