import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

/**
 * Browser check of the chat pane's display toggle (ChatDisplayToggle_261110_oo01), run against a throwaway instance
 * that has only chat-01 (it makes chat-02 and chat-03, runs a job that asks both, and sends turns, one of which has
 * chat-02 ask chat-03 so that the reply starts a turn of chat-02). The model is e2e/ScriptedModelServer.java, started
 * first. Java Playwright,
 * outside the Maven build: a main() against a running instance, as the testing standard has it.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ViewToggleCheck.java http://localhost:28975
 * </pre>
 *
 * It exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first). Start the
 * instance with {@code -Dchat-ui.timekeeper.child-reply-warn-minutes=1}: the last part waits for a timekeeper notice.
 */
public class ViewToggleCheck {

    static final Page.WaitForSelectorOptions ATTACHED = new Page.WaitForSelectorOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED);

    static String base = "http://localhost:28975";
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
        for (int i = 0; i < 240; i++) {
            String s = rest("GET", "/api/projects/project1/chats/" + chat + "/status", null);
            if (s.contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("chat " + chat + " stayed busy");
    }

    /** What the filter hides: the inputs and steps of the turns the person did not start. */
    static final String HIDDEN = "#chat-area .message.turn-other:not(.turn-answer)";
    /** What it keeps of those turns: each one's final answer. */
    static final String OTHER_ANSWERS = "#chat-area .message.turn-other.turn-answer";

    /**
     * Counts the final answers of the turns other actors started, by the origin of the user bubble that opened each
     * turn (its origin badge): "asker" for a turn that answers another conversation's or a plan's request
     * ({@code chat <name>}, {@code workflow <name>}), whose answer goes back to the asker as the reply; "notice" for
     * a timekeeper notice; "kept" for every other such answer. "...Shown" counts the visible ones, and
     * "askerFooterShown" the asker answers whose "Request: ..." footer is visible.
     */
    static final String ANSWER_KINDS = "() => { const r = {asker: 0, askerShown: 0, askerFooterShown: 0, notice: 0,"
            + " noticeShown: 0, kept: 0, keptShown: 0}; let origin = '';"
            + " for (const e of document.querySelectorAll('#chat-area > .message')) {"
            + "  if (e.classList.contains('user')) { const b = e.querySelector('.origin-badge'); origin = b ? b.textContent : ''; continue; }"
            + "  if (!e.classList.contains('turn-answer') || !e.classList.contains('turn-other')) continue;"
            + "  const shown = getComputedStyle(e).display !== 'none';"
            + "  if (/^(chat|workflow) \\S/.test(origin)) { r.asker++; if (shown) r.askerShown++;"
            + "    const f = e.querySelector('.footer-ids'); if (f && f.getClientRects().length > 0) r.askerFooterShown++; }"
            + "  else if (origin === 'timekeeper notice') { r.notice++; if (shown) r.noticeShown++; }"
            + "  else { r.kept++; if (shown) r.keptShown++; } }"
            + " return r; }";

    static int kind(Page page, String key) {
        return ((Number) ((java.util.Map<?, ?>) page.evaluate(ANSWER_KINDS)).get(key)).intValue();
    }

    static String kinds(Page page) {
        return String.valueOf(page.evaluate(ANSWER_KINDS));
    }

    /** @return {@code all} or {@code person}: the button of the View control that is pressed */
    static String viewMode(Page page) {
        return "true".equals(page.getAttribute("#view-person-btn", "aria-pressed")) ? "person" : "all";
    }

    static int visible(Page page, String selector) {
        int n = 0;
        for (ElementHandle e : page.querySelectorAll(selector)) if (e.isVisible()) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        // Two ConversationSquad actors; a job asks both (the ask_chat path: a turn another actor starts).
        String existing = rest("GET", "/api/projects/project1/chats", null);
        if (!existing.contains("03")) {
            rest("POST", "/api/projects/project1/chats", null);
            rest("POST", "/api/projects/project1/chats", null);
            String job = rest("POST", "/api/projects/project1/jobs", "{\"workflow\":\"parallel-workers-plan\"}");
            System.out.println("job: " + job);
            for (int i = 0; i < 240; i++) {
                String j = rest("GET", "/api/projects/project1/jobs/job-01", null);
                if (j.contains("FINISHED") || j.contains("STOPPED") || j.contains("FAILED")) { System.out.println("job end: " + j.substring(0, Math.min(200, j.length()))); break; }
                Thread.sleep(500);
            }
            waitIdle("02");
            // A turn from the REST API (not the screen), on chat-02.
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"API turn: reply with the single word OK.\"}");
            Thread.sleep(500);
            waitIdle("02");
        }

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            ctx.addInitScript("localStorage.setItem('chat-ui-last-project','project1');"
                    + "if (!localStorage.getItem('chat-ui-last-chat')) localStorage.setItem('chat-ui-last-chat','02');");
            Page page = ctx.newPage();
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#chat-area .message.user", ATTACHED);

            // The person types a message in the pane of chat-02.
            check(page.evaluate("window.chatUiCurrentChatId()").equals("02"), "the pane shows chat-02");
            int personBefore = page.querySelectorAll("#chat-area .message.user.turn-person").size();
            int otherBefore = page.querySelectorAll("#chat-area .message.user.turn-other").size();
            System.out.println("before: personUsers=" + personBefore + " otherUsers=" + otherBefore);
            check(personBefore == 0 && otherBefore >= 2, "before the person types, every user bubble is another actor's (job, API)");
            page.fill("#prompt-input", "Person turn: what is 2+2? Answer in one short sentence.");
            page.click("#send-btn");
            page.waitForSelector("#chat-area .message.user.turn-person", ATTACHED);
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-person').length >= 1");
            Thread.sleep(500);

            int allBubbles = page.querySelectorAll("#chat-area .message").size();
            int personUsers = page.querySelectorAll("#chat-area .message.user.turn-person").size();
            int otherUsers = page.querySelectorAll("#chat-area .message.user.turn-other").size();
            System.out.println("bubbles=" + allBubbles + " personUsers=" + personUsers + " otherUsers=" + otherUsers);
            check(personUsers == 1, "one user bubble opened by the person (turn-person)");
            check(otherUsers == otherBefore, "the others' user bubbles are the same as before: " + otherUsers);
            check(page.querySelectorAll("#chat-area .message.assistant.turn-other").size() >= 2,
                    "their answers carry turn-other too");
            check(page.querySelectorAll(OTHER_ANSWERS).size() == page.querySelectorAll("#chat-area .message.assistant.turn-other").size(),
                    "and each of those answers is marked as its turn's final answer (turn-answer)");
            check("View:".equals(page.textContent("#view-mode .view-mode-label").strip()), "the control is labelled 'View:'");
            check("All messages".equals(page.textContent("#view-all-btn").strip())
                            && "Person-facing only".equals(page.textContent("#view-person-btn").strip()),
                    "the two buttons read 'All messages' and 'Person-facing only'");
            check("true".equals(page.getAttribute("#view-all-btn", "aria-pressed"))
                            && "false".equals(page.getAttribute("#view-person-btn", "aria-pressed")),
                    "'All messages' is pressed and 'Person-facing only' is not");
            check(visible(page, "#chat-area .message") == allBubbles, "with 'All messages' pressed every bubble is visible");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/1-all.png")));

            // Press "Person-facing only".
            page.click("#view-person-btn");
            check("person".equals(viewMode(page)), "the 'Person-facing only' button is now pressed");
            check("true".equals(page.getAttribute("#view-person-btn", "aria-pressed"))
                            && "false".equals(page.getAttribute("#view-all-btn", "aria-pressed")), "aria-pressed is true on it only");
            // Clicking the pressed button again changes nothing.
            page.click("#view-person-btn");
            check("person".equals(viewMode(page)), "clicking the pressed button keeps the choice");
            check(page.querySelectorAll(HIDDEN).size() >= 2 && visible(page, HIDDEN) == 0,
                    "no input or step of another actor's turn is visible");
            int otherAnswers = page.querySelectorAll(OTHER_ANSWERS).size();
            int keptAnswers = kind(page, "kept");
            System.out.println("answers of other turns: " + kinds(page));
            check(kind(page, "asker") >= 1 && kind(page, "askerShown") == 0,
                    "the final answers of the job's requests (workflow <plan>), which go back to the job, are hidden: " + kinds(page));
            check(keptAnswers >= 1 && kind(page, "keptShown") == keptAnswers,
                    "every other final answer of another actor's turn (the API turn) stays visible: " + kinds(page));
            int personVisible = visible(page, "#chat-area .message.turn-person");
            check(personVisible >= 2 && personVisible == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "the person's turn (input and answer) is visible: " + personVisible);
            check(page.querySelectorAll("#chat-area .message").size() == allBubbles, "nothing was deleted: still " + allBubbles + " bubbles");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/2-person-only.png")));

            // A live turn that arrives with "Person-facing only" pressed follows the same rule.
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"Second API turn while hidden: reply OK.\"}");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.user.turn-other').length >= " + (otherUsers + 1));
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-other').length >= " + (otherBefore + 1));
            check(visible(page, HIDDEN) == 0, "a live turn from the API: its input arrives hidden");
            check(page.querySelectorAll(OTHER_ANSWERS).size() == otherAnswers + 1
                            && kind(page, "kept") == keptAnswers + 1 && kind(page, "keptShown") == keptAnswers + 1,
                    "and its final answer arrives visible: " + kinds(page));
            page.fill("#prompt-input", "Second person turn: name one prime number.");
            page.click("#send-btn");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.user.turn-person').length >= 2");
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-person').length >= 2");
            check(visible(page, "#chat-area .message.turn-person") == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "a live turn the person starts is visible");
            check(visible(page, HIDDEN) == 0, "and the others' inputs and steps are still hidden");

            // A turn that a reply starts, as the liaison's are: chat-02 asks chat-03 (an API turn), and chat-03's
            // reply starts a turn of chat-02 whose final answer is written to the person. Its input (the reply)
            // and its steps (the accept_reply call) are hidden; its final answer is shown. Every bubble of those
            // turns that is not a final answer is recorded as it arrives, to see that none was shown even while
            // the turns ran.
            page.evaluate("() => { window.__stepsSeen = 0; window.__stepsShown = 0;"
                    + " new MutationObserver(ms => ms.forEach(m => m.addedNodes.forEach(n => {"
                    + "   if (!n.classList || !n.classList.contains('turn-other') || n.classList.contains('turn-answer')) return;"
                    + "   window.__stepsSeen++; if (getComputedStyle(n).display !== 'none') window.__stepsShown++; })))"
                    + " .observe(document.getElementById('chat-area'), { childList: true }); }");
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"SCENARIO-R ask chat-03 for the result.\"}");
            page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-area .message.assistant'))"
                    + ".some(e => e.textContent.includes('FINAL-FOR-PERSON'))", null, new Page.WaitForFunctionOptions().setTimeout(120_000));
            waitIdle("02");
            Thread.sleep(500);
            ElementHandle replyInput = null, finalAnswer = null;
            for (ElementHandle e : page.querySelectorAll("#chat-area .message.user")) {
                if (e.textContent().startsWith("[reply to request ")) replyInput = e;
            }
            for (ElementHandle e : page.querySelectorAll("#chat-area .message.assistant")) {
                if (e.textContent().contains("FINAL-FOR-PERSON")) finalAnswer = e;
            }
            check(replyInput != null && "true".equals(String.valueOf(replyInput.evaluate("e => e.classList.contains('turn-other')")))
                            && !replyInput.isVisible(),
                    "the reply that started chat-02's turn is hidden");
            check(finalAnswer != null && "true".equals(String.valueOf(finalAnswer.evaluate("e => e.classList.contains('turn-answer') && e.classList.contains('turn-other')")))
                            && finalAnswer.isVisible(),
                    "that turn's final answer, addressed to the person, is visible");
            Number seen = (Number) page.evaluate("() => window.__stepsSeen");
            Number shown = (Number) page.evaluate("() => window.__stepsShown");
            check(seen.intValue() >= 2 && shown.intValue() == 0,
                    "no input or step of those turns was shown while they ran: " + seen + " arrived, " + shown + " shown");
            check(visible(page, HIDDEN) == 0, "after those turns, no input or step of another actor's turn is visible");
            if (finalAnswer != null) finalAnswer.scrollIntoViewIfNeeded();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/2b-reply-answer.png")));
            int otherUsersBeforeReload = page.querySelectorAll("#chat-area .message.user.turn-other").size();

            // Reload: the choice and the marks come back from storage and from the restored history.
            int total = page.querySelectorAll("#chat-area .message").size();
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("person".equals(viewMode(page)), "after reload 'Person-facing only' is still pressed");
            check(page.querySelectorAll("#chat-area .message.user.turn-person").size() == 2
                            && page.querySelectorAll("#chat-area .message.user.turn-other").size() == otherUsersBeforeReload,
                    "restored history marks the turns the same way");
            check(visible(page, HIDDEN) == 0, "after reload the other turns' inputs are hidden");
            check(kind(page, "kept") >= 3 && kind(page, "keptShown") == kind(page, "kept"),
                    "after reload the final answers of the API and reply turns are visible: " + kinds(page));
            check(kind(page, "asker") >= 1 && kind(page, "askerShown") == 0,
                    "after reload the final answers of the job's requests are still hidden: " + kinds(page));
            check(page.querySelectorAll(OTHER_ANSWERS).stream().anyMatch(e -> e.textContent().contains("FINAL-FOR-PERSON") && e.isVisible()),
                    "after reload the reply turn's final answer is still visible");
            check(visible(page, "#chat-area .message.turn-person") == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "after reload the person's turns are visible");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/3-reloaded.png")));

            // Another chat has its own choice.
            page.evaluate("window.chatUiSwitchChat('project1','03')");
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("all".equals(viewMode(page)), "chat-03 has its own choice: 'All messages' is pressed");
            check(visible(page, "#chat-area .message.turn-other") >= 2, "chat-03 shows its job turn");
            page.evaluate("window.chatUiSwitchChat('project1','02')");
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("person".equals(viewMode(page)), "back on chat-02 'Person-facing only' is pressed");
            check(visible(page, HIDDEN) == 0, "and its other turns' inputs are hidden");

            // Back to All.
            page.click("#view-all-btn");
            check("all".equals(viewMode(page)), "'All messages' pressed again");
            check(visible(page, "#chat-area .message") == page.querySelectorAll("#chat-area .message").size(), "everything is visible again");
            check(kind(page, "asker") >= 1 && kind(page, "askerShown") == kind(page, "asker"),
                    "with the filter off, the final answers of the job's requests are visible again: " + kinds(page));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/4-all-again.png")));

            // chat-03: a turn that answers another conversation's request (SCENARIO-R had chat-02 ask chat-03), whose
            // answer goes back to chat-02 as the reply, and a timekeeper-notice turn, whose answer stays.
            page.evaluate("window.chatUiSwitchChat('project1','03')");
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-area .message.assistant'))"
                    + ".some(e => e.textContent.includes('R42 is the result.'))");
            System.out.println("chat-03, all messages: " + kinds(page));
            check(kind(page, "asker") >= 2 && kind(page, "askerShown") == kind(page, "asker")
                            && kind(page, "askerFooterShown") == kind(page, "asker"),
                    "chat-03 with the filter off: the answers to the job's and chat-02's requests are visible, with their Request footer");
            page.click("#view-person-btn");
            check("person".equals(viewMode(page)), "chat-03 now has 'Person-facing only' pressed");
            check(kind(page, "asker") >= 2 && kind(page, "askerShown") == 0 && kind(page, "askerFooterShown") == 0,
                    "the answers to the requests are hidden from history, their Request footer with them: " + kinds(page));
            int askerBefore = kind(page, "asker");

            // Live: chat-02 asks chat-03 again while chat-03's pane is open and filtered.
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"SCENARIO-R ask chat-03 for the result.\"}");
            page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-area .message.assistant.turn-answer'))"
                    + ".filter(e => e.textContent.includes('R42 is the result.')).length >= 2", null,
                    new Page.WaitForFunctionOptions().setTimeout(120_000));
            waitIdle("03");
            Thread.sleep(500);
            check(kind(page, "asker") == askerBefore + 1 && kind(page, "askerShown") == 0,
                    "a live turn answering chat-02's request: its final answer arrives hidden: " + kinds(page));
            check(visible(page, HIDDEN) == 0, "and its input and steps are hidden");

            // A timekeeper notice: chat-03 sends chat-02 a request that is not answered in time (the instance warns
            // after one minute).
            rest("POST", "/api/projects/project1/chats/03/chat", "{\"text\":\"SCENARIO-A send chat-02 two requests, one urgent.\"}");
            page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-area .message.user .origin-badge'))"
                    + ".some(b => b.textContent === 'timekeeper notice')", null, new Page.WaitForFunctionOptions().setTimeout(240_000));
            waitIdle("03");
            page.waitForFunction("() => { let o = ''; let n = 0; for (const e of document.querySelectorAll('#chat-area > .message')) {"
                    + " if (e.classList.contains('user')) { const b = e.querySelector('.origin-badge'); o = b ? b.textContent : ''; }"
                    + " else if (o === 'timekeeper notice' && e.classList.contains('turn-answer')) n++; } return n >= 1; }", null,
                    new Page.WaitForFunctionOptions().setTimeout(60_000));
            Thread.sleep(500);
            check(kind(page, "notice") >= 1 && kind(page, "noticeShown") == kind(page, "notice"),
                    "a live timekeeper-notice turn: its final answer is visible: " + kinds(page));
            check(kind(page, "askerShown") == 0, "the answers to the requests stay hidden");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/5-chat03-person-only.png")));

            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            page.waitForFunction("window.chatUiCurrentChatId() === '03'");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant').length >= 4");
            System.out.println("chat-03 after reload: " + kinds(page));
            check(kind(page, "asker") == askerBefore + 1 && kind(page, "askerShown") == 0,
                    "after reload, chat-03's answers to requests are hidden: " + kinds(page));
            check(kind(page, "notice") >= 1 && kind(page, "noticeShown") == kind(page, "notice"),
                    "after reload, the timekeeper-notice answer is visible");
            check(kind(page, "kept") >= 1 && kind(page, "keptShown") == kind(page, "kept"),
                    "after reload, the other answers (the API turn) are visible");

            page.click("#view-all-btn");
            check("all".equals(viewMode(page)), "chat-03 'All messages' pressed again");
            check(visible(page, "#chat-area .message") == page.querySelectorAll("#chat-area .message").size()
                            && kind(page, "askerFooterShown") == kind(page, "asker"),
                    "with the filter off everything on chat-03 is visible again, the answers to requests and their footers too");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/6-chat03-all.png")));
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
