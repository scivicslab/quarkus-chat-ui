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
 * menu in the left pane, the centre header that holds only which conversation this is and three buttons, the
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

            // (1) Extensions is in the left pane, not in the navbar.
            check(!(Boolean) page.evaluate("() => !!document.querySelector('#app-header #extensions-btn')"),
                    "the navbar has no Extensions button");
            check((Boolean) page.evaluate("() => !!document.querySelector('#left-dock #extensions-btn')"),
                    "the left pane has the Extensions button");
            check((Boolean) page.evaluate("() => !!document.querySelector('#app-header #theme-select') && !!document.querySelector('#app-header #connection-status')"),
                    "the navbar keeps Theme and the connection status");
            page.click("#left-dock #extensions-btn");
            page.waitForSelector("#extensions-panel .ext-tab");
            check(page.isVisible("#extensions-panel"), "the Extensions panel opens from the left pane");
            check(page.locator("#extensions-panel").boundingBox().x >= -1, "the panel is on the screen, not cut off");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/shell-2-extensions.png")));
            page.click("#left-dock #extensions-btn");

            // (3) The centre header: which conversation, and three buttons.
            String head = page.locator("#app > header").innerText();
            check(head.contains("project1/chat-01"), "the header names the conversation: " + head.replace('\n', '|'));
            check((Boolean) page.evaluate("() => document.querySelectorAll('#app > header select, #app > header input').length === 0"),
                    "the header has no selector or input");
            List<String> buttons = (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#app > header button')).map(b => b.textContent.trim())");
            check(buttons.equals(List.of("Save Chat", "Clear", "Show all messages")), "the header's buttons: " + buttons);
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

            // (4) Plain Send never thinks; Send (Think mode) does, even when the old setting said not to think.
            check(page.isVisible("#send-think-btn") && page.textContent("#send-think-btn").trim().equals("Send (Think mode)"),
                    "a third button, Send (Think mode), is next to Cancel and Send");
            rest("POST", "/api/projects/project1/chats/01/no-think", "{\"noThink\":true}");
            page.fill("#prompt-input", "PLAIN-SEND hello");
            page.click("#send-btn");
            waitIdle();
            rest("POST", "/api/projects/project1/chats/01/no-think", "{\"noThink\":true}");
            page.fill("#prompt-input", "THINK-SEND hello");
            page.click("#send-think-btn");
            waitIdle();
            rest("POST", "/api/projects/project1/chats/01/no-think", "{\"noThink\":false}");
            page.fill("#prompt-input", "PLAIN-AFTER-THINK-SETTING hello");
            page.click("#send-btn");
            waitIdle();
            check(sent.size() == 3, "three prompts were sent: " + sent);
            if (sent.size() == 3) {
                check(sent.get(0).contains("\"noThink\":true"), "plain Send says noThink: " + sent.get(0));
                check(sent.get(1).contains("\"noThink\":false"), "Send (Think mode) says think: " + sent.get(1));
                check(sent.get(2).contains("\"noThink\":true"), "plain Send never thinks, whatever the old setting is: " + sent.get(2));
            }
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
