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
 * Browser check of the Suggest prompt view of the right pane, its "Suggest for the last message" entry. When the assistant's
 * last message offers choices, the view first asks for one; then it lists four to six long follow-ups, and choosing
 * one puts it in the input box with "Put in box", led by "Go with B. ", without sending. A question with no choices skips the first
 * stage. A conversation with no assistant message yet, and an unreadable reply at either stage, show an error in the
 * view and leave the input box as it was. Run against a throwaway
 * instance that has only chat-01 and talks to e2e/ScriptedModelServer.java. Java Playwright, outside the Maven build.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ScriptedModelServer.java 28978 &amp;
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/SuggestPromptCheck.java http://localhost:28979
 * </pre>
 *
 * Exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class SuggestPromptCheck {

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

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            ctx.addInitScript("localStorage.setItem('chat-ui-last-project','project1');"
                    + "if (!localStorage.getItem('chat-ui-last-chat')) localStorage.setItem('chat-ui-last-chat','01');");
            Page page = ctx.newPage();
            List<String> errors = new ArrayList<>();
            page.onPageError(e -> errors.add(String.valueOf(e)));
            // The 409 and 502 answers are what the error cases below provoke, so the browser's note of them is expected.
            page.onConsoleMessage(m -> {
                if ("error".equals(m.type()) && !m.text().startsWith("Failed to load resource")) errors.add("console: " + m.text());
            });
            List<String> sent = new ArrayList<>();
            page.onRequest(r -> { if (r.method().equals("POST") && r.url().endsWith("/chat")) sent.add(r.postData()); });
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#actors-tree .actor-name");
            page.waitForFunction("() => document.querySelectorAll('#model-select option').length > 0");

            // The page has no button beside Send and no dialog; the view is an entry of the right-pane menu.
            List<String> row = (List<String>) page.evaluate(
                    "() => Array.from(document.querySelectorAll('#send-cancel-slot button')).map(b => b.textContent.trim())");
            check(row.equals(List.of("Cancel", "Send")), "the buttons beside the input: " + row);
            check(page.locator("#suggest-btn").count() == 0 && page.locator("#suggest-overlay").count() == 0,
                    "no suggest button and no dialog are in the page");
            page.selectOption("#right-tab-select", "suggest");
            page.waitForSelector("#tab-suggest.active");

            // A conversation with no assistant message: the view says so, and the input box is untouched.
            page.fill("#prompt-input", "KEEP-ME");
            page.click("#suggest-last-btn");
            page.waitForSelector("#suggest-body .pp-error");
            String empty = page.textContent("#suggest-body");
            check(empty.contains("no assistant message"), "no message yet: the view gives the reason: " + empty);
            check("KEEP-ME".equals(page.inputValue("#prompt-input")), "the input box is unchanged after the error");

            // Choices offered: stage 1 asks for one, stage 2 lists the follow-ups on that decision.
            page.fill("#prompt-input", "ASK-CHOICES which design?");
            page.click("#send-btn");
            waitIdle();
            int sentBefore = sent.size();
            page.fill("#prompt-input", "TYPED-BEFORE");
            page.click("#suggest-last-btn");
            page.waitForSelector("#suggest-body .pp-choice");
            check(page.locator("#suggest-body .pp-choice").count() == 3, "stage 1 lists the three choices");
            check(page.isDisabled("#suggest-body .pp-next"), "Next waits for a choice");
            check(page.locator("#suggest-body .pp-text").count() == 0, "no follow-up is listed before a choice");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/suggest-1-choices.png")));
            page.locator("#suggest-body .pp-choice input").nth(1).check();
            page.click("#suggest-body .pp-next");
            page.waitForSelector("#suggest-body .pp-text");
            List<String> items = (List<String>) page.evaluate(
                    "() => Array.from(document.querySelectorAll('#suggest-body .pp-text')).map(b => b.textContent)");
            check(items.size() == 4, "four usable follow-ups are listed (the bare \"Yes.\" is dropped): " + items);
            check(items.stream().allMatch(t -> t.startsWith("Go with B. ")), "each starts with the chosen option");
            check(items.stream().allMatch(t -> t.length() > 60), "each is a fairly long sentence");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/suggest-2-followups.png")));
            page.locator("#suggest-body .pp-use").nth(2).click();
            check(page.isVisible("#tab-suggest"), "Put in box leaves the view open");
            String got = page.inputValue("#prompt-input");
            check(got.startsWith("Go with B. On design B, PREDICT-THREE"),
                    "the chosen prompt, led by the option, replaces what was typed: " + got);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/suggest-3-chosen.png")));
            Thread.sleep(800);
            check(sent.size() == sentBefore, "nothing was sent by Put in box");

            // No choices: stage 1 is skipped and the follow-ups come straight, with no head.
            page.fill("#prompt-input", "ASK-OPEN anything left?");
            page.click("#send-btn");
            waitIdle();
            page.click("#suggest-last-btn");
            page.waitForSelector("#suggest-body .pp-text");
            check(page.locator("#suggest-body .pp-choice").count() == 0, "no choice stage when the question has no choices");
            String first = page.locator("#suggest-body .pp-text").first().textContent();
            check(first.startsWith("PREDICT-ONE") && !first.startsWith("Go with"), "follow-ups have no head: " + first);

            // An unreadable reply at stage 1: an error in the view, and the box is unchanged.
            page.fill("#prompt-input", "ASK-BAD hello");
            page.click("#send-btn");
            waitIdle();
            page.fill("#prompt-input", "KEEP-ME-TOO");
            page.click("#suggest-last-btn");
            page.waitForSelector("#suggest-body .pp-error");
            String bad = page.textContent("#suggest-body");
            check(bad.contains("did not say what the assistant asked"), "unreadable stage 1: the view gives the reason: " + bad);
            check("KEEP-ME-TOO".equals(page.inputValue("#prompt-input")), "the input box is unchanged after the error");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/suggest-4-error.png")));

            // An unreadable reply at stage 2.
            page.fill("#prompt-input", "ASK-BADFOLLOW hello");
            page.click("#send-btn");
            waitIdle();
            page.fill("#prompt-input", "KEEP-ME-THREE");
            page.click("#suggest-last-btn");
            page.waitForSelector("#suggest-body .pp-error");
            String bad2 = page.textContent("#suggest-body");
            check(bad2.contains("usable prompts") || bad2.contains("no answer"), "unreadable stage 2: the view gives the reason: " + bad2);
            check("KEEP-ME-THREE".equals(page.inputValue("#prompt-input")), "the input box is unchanged after the error");

            check(errors.isEmpty(), "no page error: " + errors);
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
