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
 * Browser check of the Predict prompt button: it sits beside Cancel and Send and opens a dialog. When the assistant's
 * last message offers choices, the dialog first asks for one; then it lists four to six long follow-ups, and choosing
 * one puts it in the input box, led by "Go with B. ", without sending. A question with no choices skips the first
 * stage. A conversation with no assistant message yet, and an unreadable reply at either stage, show an error in the
 * dialog and leave the input box as it was. Run against a throwaway
 * instance that has only chat-01 and talks to e2e/ScriptedModelServer.java. Java Playwright, outside the Maven build.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ScriptedModelServer.java 28978 &amp;
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/PredictPromptCheck.java http://localhost:28979
 * </pre>
 *
 * Exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class PredictPromptCheck {

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

            // The button is in the row of Cancel and Send, after them.
            List<String> row = (List<String>) page.evaluate(
                    "() => Array.from(document.querySelectorAll('#send-cancel-slot button')).map(b => b.textContent.trim())");
            check(row.equals(List.of("Cancel", "Send", "Predict prompt")), "the buttons beside the input: " + row);
            check(page.isVisible("#predict-btn") && !page.isVisible("#predict-overlay"), "the button shows; the dialog does not");

            // A conversation with no assistant message: the dialog says so, and the input box is untouched.
            page.fill("#prompt-input", "KEEP-ME");
            page.click("#predict-btn");
            page.waitForSelector("#predict-dialog-body .pp-error");
            String empty = page.textContent("#predict-dialog-body");
            check(empty.contains("no assistant message"), "no message yet: the dialog gives the reason: " + empty);
            check("KEEP-ME".equals(page.inputValue("#prompt-input")), "the input box is unchanged after the error");
            page.keyboard().press("Escape");
            check(!page.isVisible("#predict-overlay"), "Escape closes the dialog");

            // Choices offered: stage 1 asks for one, stage 2 lists the follow-ups on that decision.
            page.fill("#prompt-input", "ASK-CHOICES which design?");
            page.click("#send-btn");
            waitIdle();
            int sentBefore = sent.size();
            page.fill("#prompt-input", "TYPED-BEFORE");
            page.click("#predict-btn");
            page.waitForSelector("#predict-dialog-body .pp-choice");
            check(page.locator("#predict-dialog-body .pp-choice").count() == 3, "stage 1 lists the three choices");
            check(page.isDisabled("#predict-dialog-body .pp-next"), "Next waits for a choice");
            check(page.locator("#predict-dialog-body .pp-item").count() == 0, "no follow-up is listed before a choice");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/predict-1-choices.png")));
            page.locator("#predict-dialog-body .pp-choice input").nth(1).check();
            page.click("#predict-dialog-body .pp-next");
            page.waitForSelector("#predict-dialog-body .pp-item");
            List<String> items = (List<String>) page.evaluate(
                    "() => Array.from(document.querySelectorAll('#predict-dialog-body .pp-item')).map(b => b.textContent)");
            check(items.size() == 4, "four usable follow-ups are listed (the bare \"Yes.\" is dropped): " + items);
            check(items.stream().allMatch(t -> t.startsWith("Go with B. ")), "each starts with the chosen option");
            check(items.stream().allMatch(t -> t.length() > 60), "each is a fairly long sentence");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/predict-2-followups.png")));
            page.locator("#predict-dialog-body .pp-item").nth(2).click();
            check(!page.isVisible("#predict-overlay"), "choosing one closes the dialog");
            String got = page.inputValue("#prompt-input");
            check(got.startsWith("Go with B. On design B, PREDICT-THREE"),
                    "the chosen prompt, led by the option, replaces what was typed: " + got);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/predict-3-chosen.png")));
            Thread.sleep(800);
            check(sent.size() == sentBefore, "nothing was sent by choosing it");

            // No choices: stage 1 is skipped and the follow-ups come straight, with no head.
            page.fill("#prompt-input", "ASK-OPEN anything left?");
            page.click("#send-btn");
            waitIdle();
            page.click("#predict-btn");
            page.waitForSelector("#predict-dialog-body .pp-item");
            check(page.locator("#predict-dialog-body .pp-choice").count() == 0, "no choice stage when the question has no choices");
            String first = page.locator("#predict-dialog-body .pp-item").first().textContent();
            check(first.startsWith("PREDICT-ONE") && !first.startsWith("Go with"), "follow-ups have no head: " + first);
            page.keyboard().press("Escape");

            // An unreadable reply at stage 1: an error in the dialog, and the box is unchanged.
            page.fill("#prompt-input", "ASK-BAD hello");
            page.click("#send-btn");
            waitIdle();
            page.fill("#prompt-input", "KEEP-ME-TOO");
            page.click("#predict-btn");
            page.waitForSelector("#predict-dialog-body .pp-error");
            String bad = page.textContent("#predict-dialog-body");
            check(bad.contains("did not say what the assistant asked"), "unreadable stage 1: the dialog gives the reason: " + bad);
            check("KEEP-ME-TOO".equals(page.inputValue("#prompt-input")), "the input box is unchanged after the error");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/predict-4-error.png")));
            page.click("#predict-dialog-close");
            check(!page.isVisible("#predict-overlay"), "the close button closes the dialog");

            // An unreadable reply at stage 2.
            page.fill("#prompt-input", "ASK-BADFOLLOW hello");
            page.click("#send-btn");
            waitIdle();
            page.fill("#prompt-input", "KEEP-ME-THREE");
            page.click("#predict-btn");
            page.waitForSelector("#predict-dialog-body .pp-error");
            String bad2 = page.textContent("#predict-dialog-body");
            check(bad2.contains("usable prompts") || bad2.contains("no answer"), "unreadable stage 2: the dialog gives the reason: " + bad2);
            check("KEEP-ME-THREE".equals(page.inputValue("#prompt-input")), "the input box is unchanged after the error");
            page.keyboard().press("Escape");

            check(errors.isEmpty(), "no page error: " + errors);
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
