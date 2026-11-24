import com.microsoft.playwright.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Browser check of the timekeeper view (TimekeeperViewInTheActorTree_261115_oo01): clicking a conversation's
 * {@code .timekeeper} actor in the tree shows what that timekeeper is watching in the centre pane, and nothing in
 * the right pane. Run against a fresh throwaway instance that has only chat-01 and talks to e2e/ScriptedModelServer.java
 * (no real model), started with {@code -Dchat-ui.timekeeper.child-reply-warn-minutes=1}. The check makes the state it
 * shows itself: it creates chat-02 and chat-03, sends chat-01 {@code SCENARIO-A} (two requests out to chat-02, one of
 * them urgent, and a promised report on chat-03), and gives chat-03 one plain turn, which promises a report to the
 * person. Chat-01's timekeeper then gives its "no reply yet" notice after the limit and, as every turn re-arms the
 * watch, gives it again: the check waits for both, which takes about three minutes. Java Playwright, outside the
 * Maven build.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/TimekeeperViewCheck.java http://localhost:28977
 * </pre>
 *
 * Exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class TimekeeperViewCheck {

    static int failures = 0;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static String base;

    /** @return the status and the body of a request to the instance, as "200 body" */
    static String rest(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
    }

    static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }

    /** Waits until chat-01's conversation holds at least {@code n} "no reply yet" notices. */
    static boolean waitForNotices(int n, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (count(rest("GET", "/api/projects/project1/chats/01/conversation", null), "[no reply yet to request") >= n) return true;
            Thread.sleep(2_000);
        }
        return false;
    }

    /** Makes the state the check shows, on a fresh instance with only chat-01. */
    static void setUp() throws Exception {
        rest("POST", "/api/projects/project1/chats", null);
        rest("POST", "/api/projects/project1/chats", null);
        rest("POST", "/api/projects/project1/chats/03/chat", "{\"text\":\"a plain turn for the person\"}");
        rest("POST", "/api/projects/project1/chats/01/chat", "{\"text\":\"SCENARIO-A two requests out and a promised report\"}");
        // chat-01 has sent its two requests to chat-02 and finished its own turn. chat-02's status is not asked: its
        // turn is held open, and a status asks the conversation's provider, which answers when the turn ends.
        for (int i = 0; i < 120; i++) {
            String view = rest("GET", "/api/projects/project1/chats/01/timekeeper", null);
            if (count(view, "project1/chat-02") >= 2
                    && rest("GET", "/api/projects/project1/chats/01/status", null).contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static void clickTree(Page page, String titleStart) {
        page.waitForFunction("(t) => Array.from(document.querySelectorAll('#actors-tree .tab-switchable'))"
                + ".some(e => (e.title || '').startsWith(t))", titleStart);
        page.evaluate("(t) => Array.from(document.querySelectorAll('#actors-tree .tab-switchable'))"
                + ".find(e => (e.title || '').startsWith(t)).click()", titleStart);
        page.waitForTimeout(800);
    }

    static boolean visible(Page page, String selector) {
        return (Boolean) page.evaluate("(s) => { const e = document.querySelector(s); return !!e && e.offsetParent !== null; }", selector);
    }

    static String text(Page page, String selector) {
        return page.textContent(selector).trim();
    }

    @SuppressWarnings("unchecked")
    static List<String> shownRight(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('.rtab-content'))"
                + ".filter(c => c.offsetParent !== null).map(c => c.id)");
    }

    @SuppressWarnings("unchecked")
    static List<String> offered(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#right-tab-select option'))"
                + ".filter(o => !o.hidden).map(o => o.textContent.trim())");
    }

    public static void main(String[] args) throws Exception {
        base = args.length > 0 ? args[0] : "http://localhost:28977";
        setUp();
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch();
            Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1500, 900));
            page.onPageError(e -> check(false, "page error: " + e));
            page.navigate(base + "/");
            page.waitForSelector("#right-tab-select");
            page.waitForSelector("#actors-tree .actor-name");

            // chat-01: two requests out, to chat-02.
            clickTree(page, "Show what the timekeeper of project1 / 01");
            check(visible(page, "#timekeeper-panel") && !visible(page, "#main-panel") && !visible(page, "#project-panel"),
                    "chat-01's timekeeper: only the timekeeper pane is in the centre");
            check(shownRight(page).equals(List.of("tab-timekeeper")) && offered(page).equals(List.of("Timekeeper")),
                    "the right pane offers and shows only its one section: " + shownRight(page) + " " + offered(page));
            check(text(page, "#tab-timekeeper").contains("Nothing to show for a timekeeper"),
                    "the right pane says there is nothing to show");
            page.waitForFunction("() => document.querySelectorAll('#tk-sent tr').length >= 3");
            check(text(page, "#timekeeper-panel-title").equals("project1/chat-01.timekeeper"), "the title names the actor");
            check(text(page, "#tk-urgent-wait").equals("10 minutes") && text(page, "#tk-warn").equals("1 minute"),
                    "the limits: " + text(page, "#tk-urgent-wait") + ", " + text(page, "#tk-warn"));
            String sent = text(page, "#tk-sent");
            // The session number in the id depends on how many sessions the log held before: any is right.
            check(java.util.regex.Pattern.compile("project1/chat-01:s\\d+:t\\d+:r\\d+").matcher(sent).find()
                            && sent.contains("project1/chat-02") && sent.contains("not sent"),
                    "two requests out to chat-02, notice not sent yet: " + sent.replaceAll("\\s+", " "));
            String view = rest("GET", "/api/projects/project1/chats/01/timekeeper", null);
            check(view.startsWith("200 ") && view.contains("\"sent\"") && view.contains("project1/chat-02"),
                    "the REST view answers with the requests out: " + view.substring(0, Math.min(200, view.length())));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat01.png")));

            // The notice goes out after the limit (1 minute plus the next check, every 30 s) and is put into the
            // conversation's queue. Its turn re-arms the watch (ReplyWatchRearmedAtTheEndOfEveryTurn_261116_oo01), so
            // the view says "sent" only while the notice waits, and the notice goes out again a minute later: what is
            // true is that notices keep coming, and the view keeps answering meanwhile.
            check(waitForNotices(1, 150_000), "the first \"no reply yet\" notice went out");
            String during = rest("GET", "/api/projects/project1/chats/01/timekeeper", null);
            check(during.startsWith("200 ") && during.contains("\"sent\""), "the REST view answers while notices go out");
            check(waitForNotices(2, 150_000), "the notice went out again: the turn it started re-armed the watch");
            check(text(page, "#tk-sent").contains("project1/chat-02"), "the view still shows the requests out: "
                    + text(page, "#tk-sent").replaceAll("\\s+", " "));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat01-noticed.png")));

            // chat-02: running a turn, an urgent item waiting.
            clickTree(page, "Show what the timekeeper of project1 / 02");
            page.waitForFunction("() => document.getElementById('tk-turn').textContent.startsWith('running')");
            String urgent = text(page, "#tk-urgent");
            check(urgent.startsWith("Waiting") && urgent.contains("would be paused in"),
                    "chat-02: urgent item waiting, pause countdown: " + urgent);
            check(text(page, "#tk-sent").startsWith("None"), "chat-02 sent nothing: " + text(page, "#tk-sent"));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat02.png")));

            // chat-03: promised reports.
            clickTree(page, "Show what the timekeeper of project1 / 03");
            page.waitForFunction("() => document.querySelectorAll('#tk-promised tr').length >= 2");
            String promised = text(page, "#tk-promised");
            check(promised.contains("project1/chat-01:s9:t9:r9") && promised.contains("(to the person)") && promised.contains("#1"),
                    "chat-03: a promise for a request and one to the person: " + promised.replaceAll("\\s+", " "));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat03.png")));

            // The other perspectives still work, and the timekeeper one is left cleanly.
            clickTree(page, "Show project project1");
            check(visible(page, "#project-panel") && !visible(page, "#timekeeper-panel"),
                    "the Project actor still shows the project pane");
            check(offered(page).contains("Project Property") && !offered(page).contains("Timekeeper"),
                    "the project's right-pane menu is back: " + offered(page));
            clickTree(page, "Switch to project1 / 01");
            check(visible(page, "#main-panel") && !visible(page, "#timekeeper-panel") && offered(page).contains("Sessions"),
                    "a conversation still shows the chat pane: " + offered(page));

            // A reload comes back to the same timekeeper.
            clickTree(page, "Show what the timekeeper of project1 / 02");
            page.reload();
            page.waitForSelector("#actors-tree .actor-name");
            page.waitForTimeout(1500);
            check(visible(page, "#timekeeper-panel") && text(page, "#timekeeper-panel-title").equals("project1/chat-02.timekeeper"),
                    "a reload comes back to chat-02's timekeeper");
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
