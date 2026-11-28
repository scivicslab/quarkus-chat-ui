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
 * Browser check of the Suggest prompt view of the right pane (SuggestPromptGuidedByWorkFrame_261128_oo01): the entry that
 * answers the assistant's last message, the entry that shows the conversation's work frame, and the progress of an
 * analysis. There is no button beside Send and no dialog. Two conversations: chat-01 is the superior of chat-02. Run
 * against a throwaway instance that talks to e2e/ScriptedModelServer.java. Java Playwright, outside the Maven build.
 *
 * <ol>
 * <li>The right-pane menu has "Suggest prompt"; the old button and dialog are gone from the page.</li>
 * <li>Opening the view calls no LLM; "Suggest for the last message" shows the default entry.</li>
 * <li>On a conversation whose work frame is empty "Show the big picture" cannot be pressed, and says why.</li>
 * <li>On the superior, after "Analyze conversation", the big picture lists the topics; topic, step and fixed question
 * lead to long instructions, and "Put in box" fills the input box, led by the preamble, without sending, with the view
 * still open.</li>
 * <li>An update that is running when the person leaves the view shows its progress ("Analyzing... 0 of 2 turns read",
 * button disabled) when they come back, also after a page reload, and ends with "Analyzed." No request is made while
 * another view is shown.</li>
 * <li>The view follows the conversation shown in the centre pane.</li>
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

    static void pane(Page page, String entry) {
        page.selectOption("#right-tab-select", entry);
        page.waitForSelector("#tab-" + entry + ".active");
    }

    static String status(Page page) {
        return page.textContent("#suggest-analyze-status").strip();
    }

    static void waitStatus(Page page, String startsWith, int ms) {
        page.waitForFunction("p => document.getElementById('suggest-analyze-status').textContent.startsWith(p)", startsWith,
                new Page.WaitForFunctionOptions().setTimeout(ms));
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
            check(sub.locator("#right-tab-select option[value=suggest]").count() == 1
                    && sub.textContent("#right-tab-select option[value=suggest]").strip().equals("Suggest prompt"),
                    "1. the right-pane menu has the entry Suggest prompt");
            check(sub.locator("#suggest-btn").count() == 0 && sub.locator("#suggest-overlay").count() == 0
                    && sub.locator("#suggest-dialog").count() == 0, "1. no button and no dialog are left in the page");
            sub.fill("#prompt-input", "ASK-OPEN anything left?");
            sub.click("#send-btn");
            waitIdle("02");
            int[] llmCalls = {0};
            sub.onRequest(r -> { if (r.method().equals("POST") && (r.url().endsWith("/suggest-question")
                    || r.url().endsWith("/suggest-followups"))) llmCalls[0]++; });
            pane(sub, "suggest");
            Thread.sleep(1500);
            check(llmCalls[0] == 0 && sub.locator("#suggest-body .pp-row").count() == 0,
                    "2. opening the view calls no LLM and lists nothing");
            check(sub.textContent("#right-tab-desc").contains("nothing is sent"), "the menu line describes the view");
            sub.click("#suggest-last-btn");
            sub.waitForSelector("#suggest-body .pp-row");
            check(sub.locator("#suggest-body .pp-text").first().textContent().startsWith("PREDICT-ONE"),
                    "2. the default entry appears on the subordinate");
            check("Suggestions for the last message".equals(sub.textContent("#suggest-heading").strip()),
                    "the heading names the default entry: " + sub.textContent("#suggest-heading"));
            check(sub.isDisabled("#suggest-picture-btn"), "3. the big-picture button cannot be pressed: the work frame is empty");
            String reason = sub.textContent("#suggest-picture-reason");
            check(reason.contains("No overall picture is recorded"), "3. and it says why: " + reason);
            sub.click("#suggest-picture-btn", new Page.ClickOptions().setForce(true).setTimeout(1500));
            check(sub.locator("#suggest-body .pp-topic").count() == 0
                    && sub.textContent("#suggest-heading").contains("Suggestions for the last message"),
                    "pressing it anyway changes nothing");
            shot(sub, "1-subordinate-default-entry-button-disabled.png");

            // ---- the superior ----
            List<String> sent1 = new ArrayList<>();
            BrowserContext ctx1 = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            Page sup = open(ctx1, "01", errors, sent1);
            sup.fill("#prompt-input", "ASK-OPEN anything left?");
            sup.click("#send-btn");
            waitIdle("01");
            pane(sup, "suggest");
            check(sup.isDisabled("#suggest-picture-btn"), "the superior, before any analysis: the button is disabled too");
            check(sup.textContent("#suggest-frame-line").contains("No work frame has been recorded"),
                    "the frame line says none is recorded: " + sup.textContent("#suggest-frame-line"));
            sup.click("#suggest-analyze-btn");
            waitStatus(sup, "Analyzed", 60000);
            check(status(sup).contains("Analyzed"), "Analyze conversation updated the work frame: " + status(sup));
            sup.waitForFunction("() => !document.getElementById('suggest-picture-btn').disabled");
            shot(sup, "2-superior-after-analyze-button-enabled.png");

            sup.click("#suggest-picture-btn");
            sup.waitForSelector("#suggest-body .pp-topic");
            List<String> topics = (List<String>) sup.evaluate(
                    "() => Array.from(document.querySelectorAll('#suggest-body .pp-topic')).map(b => b.textContent)");
            check(topics.size() == 2 && topics.get(0).contains("Rebuild the connector"),
                    "4. the topic list shows in the superior's pane: " + topics);
            check(sup.textContent("#suggest-body").contains("Which topic do you want to talk about next?"), "stage heading: topic");
            shot(sup, "3-superior-topic-list.png");

            sup.locator("#suggest-body .pp-topic").first().click();
            sup.waitForSelector("#suggest-body .pp-step");
            int steps = sup.locator("#suggest-body .pp-step").count();
            check(steps == 3, "the step stage lists two steps and \"No particular step\": " + steps);
            check(sup.textContent("#suggest-body").contains("Which step are you concerned with?"), "stage heading: step");
            shot(sup, "4-superior-step-list.png");

            sup.locator("#suggest-body .pp-step").nth(1).click();
            sup.waitForSelector("#suggest-body .pp-fixed");
            int fixed = sup.locator("#suggest-body .pp-fixed").count();
            check(fixed >= 1, "the fixed-question stage lists " + fixed + " questions");
            check(sup.locator("#suggest-body .pp-use").count() == fixed, "every fixed question has Put in box");
            check(sup.textContent("#suggest-body").contains("What do you want to ask about it?"), "stage heading: question");
            shot(sup, "5-superior-fixed-questions.png");

            int sentBefore = sent1.size();
            sup.locator("#suggest-body .pp-fixed").first().click();
            sup.waitForSelector("#suggest-body .pp-preamble");
            String pre = sup.textContent("#suggest-body .pp-preamble");
            check(pre.contains("So far we have been working on:") && pre.contains("Rebuild the connector"),
                    "the preamble shows the work frame: " + pre.replace('\n', '|'));
            check(sup.locator("#suggest-body .pp-text").count() >= 3, "long instructions are listed");
            check(sup.locator("#suggest-body .pp-use").count() == sup.locator("#suggest-body .pp-text").count(),
                    "each instruction has its own Put in box");
            shot(sup, "6-superior-instructions-with-preamble.png");
            sup.fill("#prompt-input", "TYPED-BEFORE");
            sup.locator("#suggest-body .pp-use").nth(1).click();
            check(sup.isVisible("#tab-suggest") && sup.locator("#suggest-body .pp-mark").nth(1).textContent().contains("Put in the input box"),
                    "Put in box leaves the view open and marks the instruction");
            String got = sup.inputValue("#prompt-input");
            check(got.startsWith("So far we have been working on:") && got.contains("PREDICT-TWO") && !got.contains("TYPED-BEFORE"),
                    "the input box holds the preamble and the chosen instruction: " + got.replace('\n', '|'));
            shot(sup, "7-superior-input-box-filled.png");
            Thread.sleep(800);
            check(sent1.size() == sentBefore, "nothing was sent by Put in box");

            // The default entry on the superior still leads with a one-line preamble (the first line only).
            sup.fill("#prompt-input", "");
            sup.click("#suggest-last-btn");
            sup.waitForSelector("#suggest-body .pp-use");
            sup.locator("#suggest-body .pp-use").first().click();
            String def = sup.inputValue("#prompt-input");
            check(def.startsWith("So far we have been working on:") && def.contains("PREDICT-ONE") && def.indexOf('\n') > 0,
                    "the default entry on the superior also carries the one-line preamble: " + def.replace('\n', '|'));

            // Nothing new to analyze a second time.
            sup.click("#suggest-analyze-btn");
            sup.waitForFunction("() => document.getElementById('suggest-analyze-status').textContent.startsWith('Nothing')");
            check(status(sup).contains("Nothing new to analyze"), "a second Analyze finds nothing new: " + status(sup));
            shot(sup, "8-superior-nothing-new.png");

            // An update that is still running when the person leaves the view shows its progress when they return.
            for (int i = 0; i < 2; i++) {
                sup.fill("#prompt-input", "SLOW-ANALYZE ASK-OPEN more " + i + "?");
                sup.click("#send-btn");
                waitIdle("01");
            }
            pane(sup, "tokens");
            pane(sup, "suggest");
            check(!sup.isDisabled("#suggest-analyze-btn") && status(sup).isEmpty(),
                    "5. before the run: the button is enabled and the status is empty");
            sup.click("#suggest-analyze-btn");
            waitStatus(sup, "Analyzing\u2026 0 of 2", 10000);
            check(sup.isDisabled("#suggest-analyze-btn"), "5. while it runs the button is disabled: " + status(sup));
            shot(sup, "9-superior-analyzing-progress.png");

            pane(sup, "settings");
            check(!sup.isVisible("#tab-suggest"), "5. another view is shown while the update runs on");
            int[] polls = {0};
            sup.onRequest(r -> { if (r.url().endsWith("/suggest-frame")) polls[0]++; });
            Thread.sleep(2500);
            check(polls[0] == 0, "5. no request is made for the view while another view is shown: " + polls[0]);
            pane(sup, "suggest");
            waitStatus(sup, "Analyzing", 5000);
            String back = status(sup);
            check(back.equals("Analyzing\u2026 0 of 2 turns read") && sup.isDisabled("#suggest-analyze-btn"),
                    "5. back mid-run: the progress shows and the button is disabled: " + back);
            shot(sup, "10-superior-back-mid-run.png");

            // Even a page that never pressed the button (a reload) reads the run from the server.
            sup.reload();
            sup.waitForSelector("#actors-tree .actor-name");
            pane(sup, "suggest");
            waitStatus(sup, "Analyzing", 5000);
            check(sup.isDisabled("#suggest-analyze-btn"), "5. after a page reload the running update shows too: " + status(sup));

            waitStatus(sup, "Analyzed", 40000);
            check(status(sup).equals("Analyzed. 0 turns still unread.") && !sup.isDisabled("#suggest-analyze-btn"),
                    "5. when it ends the line says Analyzed and the button is enabled: " + status(sup));
            shot(sup, "11-superior-analyzed-after-run.png");

            // The view follows the conversation shown in the centre pane.
            check(sup.textContent("#suggest-frame-line").contains("Work frame updated"), "6. chat-01 has a work frame");
            sup.locator("#actors-tree .actor-name", new Page.LocatorOptions().setHasText("chat-02")).first().click();
            sup.waitForFunction("() => document.getElementById('suggest-frame-line').textContent.includes('No work frame')",
                    null, new Page.WaitForFunctionOptions().setTimeout(8000));
            check(sup.isVisible("#tab-suggest") && sup.isDisabled("#suggest-picture-btn")
                    && sup.locator("#suggest-body .pp-row").count() == 0,
                    "6. switching to chat-02 shows chat-02's frame and clears the lists");
            shot(sup, "12-view-follows-the-centre-pane.png");

            check(errors.isEmpty(), "no page error: " + errors);
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
