import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Browser check of the page's frame: the navbar, the hamburger that opens and closes the left pane, the Extensions
 * menu in the left pane, the centre header that holds only which conversation this is and four buttons, the
 * conversation's settings in the right pane's "Settings" entry, and the Think-mode send button. Run against a
 * throwaway instance that has only chat-01 and talks to e2e/ScriptedModelServer.java. Java Playwright, outside the
 * Maven build.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ScriptedModelServer.java 28978 &amp;
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ChatUiShellCheck.java http://localhost:28979
 * </pre>
 *
 * Exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class ChatUiShellCheck {

    static String base = "http://localhost:28979";
    static final HttpClient http = HttpClient.newHttpClient();
    static int failures = 0;

    static String rest(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static void waitIdle() throws Exception {
        Thread.sleep(800);
        for (int i = 0; i < 120; i++) {
            if (rest("GET", "/api/projects/project1/chats/01/status", null).contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
    }

    static double dockWidth(Page page) {
        return page.locator("#left-dock").boundingBox().width;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            // A person who once dragged the left pane's edge has its width kept by the browser; the hamburger must work for them too.
            ctx.addInitScript("localStorage.setItem('chat-ui-left-dock-width','320');"
                    + "localStorage.setItem('chat-ui-last-project','project1');"
                    + "if (!localStorage.getItem('chat-ui-last-chat')) localStorage.setItem('chat-ui-last-chat','01');");
            Page page = ctx.newPage();
            List<String> errors = new ArrayList<>();
            page.onPageError(e -> errors.add(String.valueOf(e)));
            page.onConsoleMessage(m -> { if ("error".equals(m.type())) errors.add("console: " + m.text()); });
            List<String> sent = new ArrayList<>();
            page.onRequest(r -> { if (r.method().equals("POST") && r.url().endsWith("/chat")) sent.add(r.postData()); });
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#actors-tree .actor-name");
            page.waitForFunction("() => document.querySelectorAll('#model-select option').length > 0");

            // (2) The hamburger opens and closes the left pane.
            double open = dockWidth(page);
            check(open > 300, "the left pane is open at the start, at the width the browser kept: " + open + "px");
            page.click("#dock-toggle");
            page.waitForTimeout(400);
            double closed = dockWidth(page);
            check(closed < 5, "the hamburger closes the left pane: " + closed + "px");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/shell-1-closed.png")));
            page.click("#dock-toggle");
            page.waitForTimeout(400);
            double reopened = dockWidth(page);
            check(reopened > 100, "the hamburger opens it again: " + reopened + "px");

            // (1) Extensions is a view of the left pane, chosen in the same menu as Actor Tree and Graph: not in the navbar,
            // not a separate button, and not a floating panel.
            check(!(Boolean) page.evaluate("() => !!document.getElementById('extensions-btn')"),
                    "there is no separate Extensions button anywhere");
            check(!(Boolean) page.evaluate("() => !!document.querySelector('#app-header #extensions-view, #app-header .ext-panel')"),
                    "the navbar has no Extensions");
            check((Boolean) page.evaluate("() => !!document.querySelector('#app-header #theme-select') && !!document.querySelector('#app-header #connection-status')"),
                    "the navbar keeps Theme and the connection status");
            List<String> views = (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#left-view-select option')).map(o => o.textContent.trim())");
            check(views.equals(List.of("Actor Tree", "Graph", "Extensions")), "the left pane's menu offers: " + views);
            check(page.isVisible("#actors-tree") && !page.isVisible("#extensions-view"), "the Actor Tree is shown at the start, not Extensions");
            page.selectOption("#left-view-select", "extensions");
            page.waitForSelector("#extensions-panel .ext-tab");
            page.waitForSelector("#ext-content .ext-item, #ext-content .ext-roots");
            check(page.isVisible("#extensions-view") && !page.isVisible("#actors-tree") && !page.isVisible("#conv-graph"),
                    "choosing Extensions shows it in the left pane's content area, in place of the other views");
            check("static".equals(page.evaluate("() => getComputedStyle(document.getElementById('extensions-panel')).position")),
                    "the panel is part of the pane, not floating");
            double paneWidth = dockWidth(page);
            double panelWidth = page.locator("#extensions-panel").boundingBox().width;
            check(panelWidth <= paneWidth + 1 && page.locator("#extensions-panel").boundingBox().x >= -1,
                    "the panel stays inside the pane: " + panelWidth + " of " + paneWidth + " px");
            check(page.locator("#actors-status").textContent().isEmpty(), "the actor count is not shown under Extensions");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/shell-2-extensions.png")));
            page.click("#extensions-panel .ext-tab[data-tab=project]");
            page.waitForSelector("#ext-content .ext-input");
            check(page.isVisible("#ext-content .ext-action"), "the Project tab shows the working directory field and Apply");
            page.click("#extensions-panel .ext-tab[data-tab=skills]");
            page.waitForSelector("#ext-content .ext-item, #ext-content .ext-roots");
            // The choice is remembered like the other views.
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#extensions-panel .ext-tab");
            check("extensions".equals(page.inputValue("#left-view-select")) && page.isVisible("#extensions-view"),
                    "after a reload the left pane is still on Extensions");
            page.selectOption("#left-view-select", "tree");
            page.waitForSelector("#actors-tree .actor-name");
            check(page.isVisible("#actors-tree") && !page.isVisible("#extensions-view"), "choosing Actor Tree again hides Extensions");

            // (3) The centre header: which conversation, and four buttons.
            String head = page.locator("#app > header").innerText();
            check(head.contains("project1/chat-01"), "the header names the conversation: " + head.replace('\n', '|'));
            check((Boolean) page.evaluate("() => document.querySelectorAll('#app > header select, #app > header input').length === 0"),
                    "the header has no selector or input");
            List<String> buttons = (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#app > header button')).map(b => b.textContent.trim())");
            check(buttons.equals(List.of("Settings", "Save Chat", "Clear", "Show all messages")), "the header's buttons: " + buttons);
            String provider = (String) page.evaluate("() => document.getElementById('provider-select').selectedOptions[0].textContent");
            String model = page.inputValue("#model-select");
            check(!provider.isBlank() && head.contains(provider) && head.contains(model),
                    "the header says the provider and model (" + provider + " / " + model + ")");

            // The settings moved into the right pane's menu.
            check((Boolean) page.evaluate("() => !!document.querySelector('#right-tab-select option[value=settings]')"),
                    "the right pane's menu has a Settings entry");
            page.selectOption("#right-tab-select", "settings");
            page.waitForTimeout(300);
            for (String id : List.of("provider-select", "model-select", "refresh-models-btn", "temperature-input", "effort-input")) {
                check(page.isVisible("#tab-settings #" + id), "Settings shows #" + id);
            }
            check(!(Boolean) page.evaluate("() => !!document.getElementById('think-check')"), "the Think toggle is gone");
            page.fill("#temperature-input", "0.4");
            page.dispatchEvent("#temperature-input", "change");
            page.waitForTimeout(600);
            check(rest("GET", "/api/projects/project1/chats/01/status", null).contains("\"temperature\":0.4"),
                    "the temperature set in the right pane reaches the conversation");
            page.fill("#temperature-input", "");
            page.dispatchEvent("#temperature-input", "change");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/shell-3-settings.png")));
            page.selectOption("#right-tab-select", "logdb");

            // (4) Plain Send and Shift+Enter never think; Ctrl+Shift+Enter does, even when the old setting said not to think.
            check(page.locator("#send-think-btn").count() == 0, "there is no Send (Think mode) button any more");
            check(page.getAttribute("#send-btn", "title").contains("Ctrl+Shift+Enter"),
                    "the Send button's title says how to send with thinking on: " + page.getAttribute("#send-btn", "title"));
            rest("POST", "/api/projects/project1/chats/01/no-think", "{\"noThink\":true}");
            page.fill("#prompt-input", "PLAIN-SEND hello");
            page.press("#prompt-input", "Shift+Enter");
            waitIdle();
            rest("POST", "/api/projects/project1/chats/01/no-think", "{\"noThink\":true}");
            page.fill("#prompt-input", "THINK-SEND hello");
            page.press("#prompt-input", "Control+Shift+Enter");
            waitIdle();
            rest("POST", "/api/projects/project1/chats/01/no-think", "{\"noThink\":false}");
            page.fill("#prompt-input", "PLAIN-AFTER-THINK-SETTING hello");
            page.click("#send-btn");   // the button itself still sends without thinking
            waitIdle();
            check(sent.size() == 3, "three prompts were sent: " + sent);
            if (sent.size() == 3) {
                check(sent.get(0).contains("\"noThink\":true"), "Shift+Enter says noThink: " + sent.get(0));
                check(sent.get(1).contains("\"noThink\":false"), "Ctrl+Shift+Enter says think: " + sent.get(1));
                check(sent.get(2).contains("\"noThink\":true"), "plain Send never thinks, whatever the old setting is: " + sent.get(2));
            }
            // The box inside the Send button: ticked, Send and Shift+Enter think; clicking the box alone sends nothing; sending
            // does not clear it; Ctrl+Shift+Enter thinks whatever it says. The box has no title and no label.
            check(page.getAttribute("#send-think-check", "title") == null
                    && page.locator("label[for=send-think-check]").count() == 0, "the box has no title and no label");
            page.fill("#prompt-input", "BOX-CLICK-ONLY hello");
            page.click("#send-think-check");
            page.waitForTimeout(500);
            check(sent.size() == 3, "clicking the box alone sends nothing: " + sent);
            page.click("#send-btn");
            waitIdle();
            page.fill("#prompt-input", "BOX-ON-SHIFT-ENTER hello");
            page.press("#prompt-input", "Shift+Enter");
            waitIdle();
            check(sent.size() == 5, "two prompts were sent with the box ticked: " + sent);
            if (sent.size() == 5) {
                check(sent.get(3).contains("\"noThink\":false"), "Send with the box ticked says think: " + sent.get(3));
                check(sent.get(4).contains("\"noThink\":false"), "Shift+Enter with the box ticked says think: " + sent.get(4));
            }
            check(page.isChecked("#send-think-check"), "sending leaves the box ticked");
            page.click("#send-think-check");
            String log = rest("GET", "/api/iolog/search?q=enable_thinking&limit=20", null);
            int on = log.split("enable_thinking\\\\\":true", -1).length - 1;
            int off = log.split("enable_thinking\\\\\":false", -1).length - 1;
            check(on >= 1 && off >= 2, "the server asked the model with thinking on once and off twice (on=" + on + ", off=" + off + ")");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/shell-4-send.png")));

            check(errors.isEmpty(), "no page error: " + errors);
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
