import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Browser check of the Suggest prompt button's two entries (SuggestPromptGuidedByWorkFrame_261128_oo01): the default
 * entry that answers the assistant's last message, and the button above it that goes back to the whole picture, the
 * conversation's work frame. Two conversations: chat-01 is the superior of chat-02. Run against a throwaway instance
 * that talks to e2e/ScriptedModelServer.java. Java Playwright, outside the Maven build.
 *
 * <ol>
 * <li>Pressing Suggest prompt shows the default entry.</li>
 * <li>On a conversation whose work frame is empty the whole-picture button cannot be pressed, and says why.</li>
 * <li>On the superior, after "Analyze conversation", the button lists the topics; topic, step and fixed question
 * lead to long instructions, and the one chosen goes into the input box led by the preamble, without being sent.</li>
 * <li>An update that is still running when the dialog is closed shows its progress ("Analyzing… 0 of 2 turns read",
 * button disabled) when the dialog is opened again, also after a page reload, and ends with "Analyzed."</li>
 * </ol>
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ScriptedModelServer.java 28978 &amp;
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/SuggestFrameCheck.java http://localhost:28979 [shotDir]
 * </pre>
 *
 * Exits 0 when every check passes, and writes screenshots to the directory (default /tmp/uitest/).
 */
public class SuggestFrameCheck {

    static String base = "http://localhost:28979";
    static Path shots = Path.of("/tmp/uitest");
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

    static void waitIdle(String chat) throws Exception {
        Thread.sleep(800);
        for (int i = 0; i < 120; i++) {
            if (rest("GET", "/api/projects/project1/chats/" + chat + "/status", null).contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
    }

    static Page open(BrowserContext ctx, String chat, List<String> errors, List<String> sent) {
        ctx.addInitScript("localStorage.setItem('chat-ui-last-project','project1');"
                + "localStorage.setItem('chat-ui-last-chat','" + chat + "');");
        Page page = ctx.newPage();
        page.onPageError(e -> errors.add(String.valueOf(e)));
        page.onConsoleMessage(m -> {
            if ("error".equals(m.type()) && !m.text().startsWith("Failed to load resource")) errors.add("console: " + m.text());
        });
        page.onRequest(r -> { if (r.method().equals("POST") && r.url().endsWith("/chat")) sent.add(r.postData()); });
        page.navigate(base + "/");
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForSelector("#actors-tree .actor-name");
        page.waitForFunction("() => document.querySelectorAll('#model-select option').length > 0");
        return page;
    }

    static void shot(Page page, String name) {
        page.screenshot(new Page.ScreenshotOptions().setPath(shots.resolve(name)));
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        if (args.length > 1) shots = Path.of(args[1]);
        Files.createDirectories(shots);
        // chat-02 is chat-01's subordinate; both have one finished turn, and nothing has analyzed them yet.
        rest("POST", "/api/projects/project1/chats/02?under=01", null);
        List<String> errors = new ArrayList<>();
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));

            // ---- the subordinate: an empty work frame ----
            List<String> sent2 = new ArrayList<>();
            BrowserContext ctx2 = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            Page sub = open(ctx2, "02", errors, sent2);
            sub.fill("#prompt-input", "ASK-OPEN anything left?");
            sub.click("#send-btn");
            waitIdle("02");
            sub.click("#suggest-btn");
            sub.waitForSelector("#suggest-dialog-body .pp-item");
            check(sub.locator("#suggest-dialog-body .pp-item").first().textContent().startsWith("PREDICT-ONE"),
                    "1. the default entry appears on the subordinate");
            check("Suggestions for the last message".equals(sub.textContent("#suggest-heading").strip()),
                    "the heading names the default entry: " + sub.textContent("#suggest-heading"));
            check(sub.isDisabled("#suggest-picture-btn"), "3. the big-picture button cannot be pressed: the work frame is empty");
            String reason = sub.textContent("#suggest-picture-reason");
            check(reason.contains("No overall picture is recorded"), "3. and it says why: " + reason);
            sub.click("#suggest-picture-btn", new Page.ClickOptions().setForce(true).setTimeout(1500));
            check(sub.locator("#suggest-dialog-body .pp-topic").count() == 0
                    && sub.textContent("#suggest-heading").contains("Suggestions for the last message"),
                    "pressing it anyway changes nothing");
            shot(sub, "1-subordinate-default-entry-button-disabled.png");
            sub.keyboard().press("Escape");

            // ---- the superior ----
            List<String> sent1 = new ArrayList<>();
            BrowserContext ctx1 = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            Page sup = open(ctx1, "01", errors, sent1);
            sup.fill("#prompt-input", "ASK-OPEN anything left?");
            sup.click("#send-btn");
            waitIdle("01");
            sup.click("#suggest-btn");
            sup.waitForSelector("#suggest-dialog-body .pp-item");
            check(sup.isDisabled("#suggest-picture-btn"), "the superior, before any analysis: the button is disabled too");
            check(sup.textContent("#suggest-frame-line").contains("No work frame has been recorded"),
                    "the frame line says none is recorded: " + sup.textContent("#suggest-frame-line"));
            sup.click("#suggest-analyze-btn");
            sup.waitForFunction("() => document.getElementById('suggest-analyze-status').textContent.startsWith('Analyzed')",
                    null, new Page.WaitForFunctionOptions().setTimeout(60000));
            check(sup.textContent("#suggest-analyze-status").contains("Analyzed"),
                    "Analyze conversation updated the work frame: " + sup.textContent("#suggest-analyze-status"));
            sup.waitForFunction("() => !document.getElementById('suggest-picture-btn').disabled");
            check(sup.textContent("#suggest-picture-btn").contains("Back to the big picture"), "the button is enabled");
            shot(sup, "2-superior-after-analyze-button-enabled.png");

            sup.click("#suggest-picture-btn");
            sup.waitForSelector("#suggest-dialog-body .pp-topic");
            List<String> topics = (List<String>) sup.evaluate(
                    "() => Array.from(document.querySelectorAll('#suggest-dialog-body .pp-topic')).map(b => b.textContent)");
            check(topics.size() == 2 && topics.get(0).contains("Rebuild the connector"),
                    "2. the topic list shows on the superior's screen: " + topics);
            check(sup.textContent("#suggest-dialog-body").contains("Which topic do you want to talk about next?"), "stage heading: topic");
            shot(sup, "3-superior-topic-list.png");

            sup.locator("#suggest-dialog-body .pp-topic").first().click();
            sup.waitForSelector("#suggest-dialog-body .pp-step");
            int steps = sup.locator("#suggest-dialog-body .pp-step").count();
            check(steps == 3, "the step stage lists two steps and \"No particular step\": " + steps);
            check(sup.textContent("#suggest-dialog-body").contains("Which step are you concerned with?"), "stage heading: step");
            shot(sup, "4-superior-step-list.png");

            sup.locator("#suggest-dialog-body .pp-step").nth(1).click();
            sup.waitForSelector("#suggest-dialog-body .pp-fixed");
            int fixed = sup.locator("#suggest-dialog-body .pp-fixed").count();
            check(fixed >= 1, "the fixed-question stage lists " + fixed + " questions");
            check(sup.textContent("#suggest-dialog-body").contains("What do you want to ask about it?"), "stage heading: question");
            shot(sup, "5-superior-fixed-questions.png");

            int sentBefore = sent1.size();
            sup.locator("#suggest-dialog-body .pp-fixed").first().click();
            sup.waitForSelector("#suggest-dialog-body .pp-item.pp-item, #suggest-dialog-body .pp-preamble");
            sup.waitForSelector("#suggest-dialog-body .pp-preamble");
            String pre = sup.textContent("#suggest-dialog-body .pp-preamble");
            check(pre.contains("So far we have been working on:") && pre.contains("Rebuild the connector"),
                    "the preamble shows the work frame: " + pre.replace('\n', '|'));
            check(sup.locator("#suggest-dialog-body button.pp-item").count() >= 3, "long instructions are listed");
            shot(sup, "6-superior-instructions-with-preamble.png");
            sup.locator("#suggest-dialog-body button.pp-item").nth(1).click();
            check(!sup.isVisible("#suggest-overlay"), "choosing one closes the dialog");
            String got = sup.inputValue("#prompt-input");
            check(got.startsWith("So far we have been working on:") && got.contains("PREDICT-TWO"),
                    "the input box holds the preamble and the chosen instruction: " + got.replace('\n', '|'));
            shot(sup, "7-superior-input-box-filled.png");
            Thread.sleep(800);
            check(sent1.size() == sentBefore, "nothing was sent by choosing it");

            // The default entry on the superior still leads with a one-line preamble (the first line only).
            sup.fill("#prompt-input", "");
            sup.click("#suggest-btn");
            sup.waitForSelector("#suggest-dialog-body button.pp-item");
            sup.locator("#suggest-dialog-body button.pp-item").first().click();
            String def = sup.inputValue("#prompt-input");
            check(def.startsWith("So far we have been working on:") && def.contains("PREDICT-ONE") && def.indexOf('\n') > 0,
                    "the default entry on the superior also carries the one-line preamble: " + def.replace('\n', '|'));

            // Back from the big picture to the last message.
            sup.click("#suggest-btn");
            sup.waitForSelector("#suggest-dialog-body button.pp-item");
            sup.click("#suggest-picture-btn");
            sup.waitForSelector("#suggest-dialog-body .pp-topic");
            check(sup.textContent("#suggest-picture-btn").contains("Back to the last message"), "the button now goes back");
            sup.click("#suggest-picture-btn");
            sup.waitForSelector("#suggest-dialog-body button.pp-item");
            check(sup.textContent("#suggest-heading").contains("Suggestions for the last message"), "back on the default entry");
            sup.keyboard().press("Escape");

            // Nothing new to analyze a second time.
            sup.click("#suggest-btn");
            sup.waitForSelector("#suggest-dialog-body button.pp-item");
            sup.click("#suggest-analyze-btn");
            sup.waitForFunction("() => document.getElementById('suggest-analyze-status').textContent.length > 0");
            check(sup.textContent("#suggest-analyze-status").contains("Nothing new to analyze"),
                    "a second Analyze finds nothing new: " + sup.textContent("#suggest-analyze-status"));
            shot(sup, "8-superior-nothing-new.png");
            sup.keyboard().press("Escape");

            // An update that is still running when the dialog is closed shows its progress when the dialog is opened again.
            for (int i = 0; i < 2; i++) {
                sup.fill("#prompt-input", "SLOW-ANALYZE ASK-OPEN more " + i + "?");
                sup.click("#send-btn");
                waitIdle("01");
            }
            sup.click("#suggest-btn");
            sup.waitForSelector("#suggest-dialog-body button.pp-item");
            check(!sup.isDisabled("#suggest-analyze-btn") && sup.textContent("#suggest-analyze-status").isEmpty(),
                    "4. before the run: the button is enabled and the status is empty");
            sup.click("#suggest-analyze-btn");
            sup.waitForFunction("() => document.getElementById('suggest-analyze-status').textContent.startsWith('Analyzing\u2026 0 of 2')",
                    null, new Page.WaitForFunctionOptions().setTimeout(10000));
            check(sup.isDisabled("#suggest-analyze-btn"), "4. while it runs the button is disabled: "
                    + sup.textContent("#suggest-analyze-status"));
            shot(sup, "9-superior-analyzing-progress.png");

            sup.keyboard().press("Escape");
            check(!sup.isVisible("#suggest-overlay"), "4. the dialog is closed while the update runs on");
            Thread.sleep(1500);
            sup.click("#suggest-btn");
            sup.waitForFunction("() => document.getElementById('suggest-analyze-status').textContent.startsWith('Analyzing')",
                    null, new Page.WaitForFunctionOptions().setTimeout(5000));
            String reopened = sup.textContent("#suggest-analyze-status");
            check(reopened.equals("Analyzing\u2026 0 of 2 turns read") && sup.isDisabled("#suggest-analyze-btn"),
                    "4. reopened mid-run: the progress shows and the button is disabled: " + reopened);
            shot(sup, "10-superior-reopened-mid-run.png");
            sup.keyboard().press("Escape");

            // Even a page that never pressed the button (a reload) reads the run from the server.
            sup.reload();
            sup.waitForSelector("#actors-tree .actor-name");
            sup.click("#suggest-btn");
            sup.waitForFunction("() => document.getElementById('suggest-analyze-status').textContent.startsWith('Analyzing')",
                    null, new Page.WaitForFunctionOptions().setTimeout(5000));
            check(sup.isDisabled("#suggest-analyze-btn"), "4. after a page reload the running update shows too: "
                    + sup.textContent("#suggest-analyze-status"));

            sup.waitForFunction("() => document.getElementById('suggest-analyze-status').textContent.startsWith('Analyzed')",
                    null, new Page.WaitForFunctionOptions().setTimeout(40000));
            check(sup.textContent("#suggest-analyze-status").equals("Analyzed. 0 turns still unread.")
                    && !sup.isDisabled("#suggest-analyze-btn"),
                    "4. when it ends the line says Analyzed and the button is enabled: " + sup.textContent("#suggest-analyze-status"));
            shot(sup, "11-superior-analyzed-after-run.png");
            sup.keyboard().press("Escape");

            check(errors.isEmpty(), "no page error: " + errors);
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
