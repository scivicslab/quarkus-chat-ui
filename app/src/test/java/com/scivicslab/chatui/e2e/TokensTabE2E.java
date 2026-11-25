package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * E2E check of the right pane's Tokens tab: for a conversation on the openai-compat provider (answered by
 * {@code e2e/ScriptedModelServer.java}) and for one on Claude Code (answered by {@code e2e/fake-claude/claude}),
 * the tab shows the parts of the head block with who may cut each, the estimate beside the measured first
 * request, and the first request of each turn with its growth. It has no button that cuts anything.
 *
 * <p>A {@code main()} program outside the build (TestingStandard_260404_oo01), against a throwaway instance
 * started for it, never a live one. The instance needs the scripted model, the fake CLI first on its PATH, and the
 * harness plugin; its conversations are made here, so it should have only {@code project1/chat-01}:</p>
 *
 * <pre>
 * java e2e/ScriptedModelServer.java 28978 &amp;
 * PATH=$HOME/works/quarkus-chat-ui/e2e/fake-claude:$PATH java \
 *     -Dquarkus.http.port=28979 -Dchat-ui.servers=http://127.0.0.1:28978 -Dchat-ui.default-model=fake \
 *     -Dchat-ui.plugins=plugin-harness/target/quarkus-chat-ui-plugin-harness-3.0.0-SNAPSHOT.jar -jar app/target/quarkus-chat-ui-3.0.0-SNAPSHOT.jar &amp;
 * CHAT_UI_URL=http://localhost:28979/ java -cp ... com.scivicslab.chatui.e2e.TokensTabE2E
 * </pre>
 *
 * Exits 1 when a check fails; writes screenshots to {@code /tmp/uitest/}.
 */
public class TokensTabE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28979/");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        new TokensTabE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    void run() throws Exception {
        System.out.println("--- TokensTabE2E against " + BASE_URL + " ---");
        Files.createDirectories(Path.of("/tmp/uitest"));
        // chat-01 stays on openai-compat; chat-02 is switched to Claude Code. Each gets two turns, so the per-turn table has a growth.
        rest("POST", "/api/projects/project1/chats", null);
        String switched = rest("POST", "/api/projects/project1/chats/02/provider", "{\"provider\":\"claude\"}");
        check(switched.contains("\"accepted\""), "chat-02 was switched to Claude Code: " + switched);
        for (String chat : List.of("01", "02")) {
            for (String text : List.of("HELLO-ONLY first turn", "HELLO-ONLY second turn")) {
                rest("POST", "/api/projects/project1/chats/" + chat + "/chat", "{\"text\":\"" + text + "\"}");
                waitIdle(chat);
            }
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.onPageError(e -> check(false, "page error: " + e));
                page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                checkTab(page, "01", "chat-ui's MCP server (tools/list", "openai-compat", 1000);
                checkTab(page, "02", "CLI built-in prompt and tools (not measurable here)", "claude", 41000);
                checkTab(page, "02", "chat-ui's MCP server (tools/list", "claude (chat-ui's own MCP server)", 41000);
            } finally {
                browser.close();
            }
        }
    }

    /** The text of column {@code col} (1 = last request, 2 = all requests) in the breakdown row whose label starts with {@code label}. */
    private static String cellText(Page page, String label, int col) {
        return (String) page.evaluate("([label, col]) => { const row = Array.from(document.querySelectorAll('#tokens-breakdown tr'))"
                + ".find(r => r.children[0] && r.children[0].textContent.trim().startsWith(label)); return row ? row.children[col].textContent.trim() : null; }",
                java.util.List.of(label, col));
    }

    private static long cell(Page page, String label, int col) {
        String text = cellText(page, label, col);
        try {
            return text == null ? -1 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void checkTab(Page page, String chat, String expectedPart, String label, int minFirstRequest) {
        page.evaluate("() => window.chatUiSwitchChat('project1', '" + chat + "')");
        page.selectOption("#right-tab-select", "tokens");
        // chatUiSwitchChat does not go through the tree's click that reloads the open tab, so the person's Refresh is pressed.
        page.locator("#tokens-refresh").click();
        check(page.locator("#tab-tokens").getAttribute("class").contains("active"), label + ": the Tokens section is shown");
        page.locator("#tokens-parts table").first().waitFor();
        page.locator("#tokens-turns table").first().waitFor();
        String parts = page.locator("#tokens-parts").textContent();
        check(parts.contains(expectedPart), label + ": the parts table lists '" + expectedPart + "'");
        page.locator("#tokens-breakdown table").first().waitFor();
        String breakdown = page.locator("#tokens-breakdown").textContent();
        check(page.locator("#tab-tokens h3").first().textContent().contains("unit: tokens")
                        && breakdown.contains("characters / 3.5"),
                label + ": the unit is said at the table's head");
        check(breakdown.contains("Every part of a request's input is counted in exactly one row"),
                label + ": the rule that makes the rows a partition is said under the title");
        int window = breakdown.indexOf("Window (max)"), used = breakdown.indexOf("Used = input the provider reported"),
                free = breakdown.indexOf("Free = Window - Used"), firstRow = breakdown.indexOf("A1  System prompt");
        check(window >= 0 && window < used && used < free && free < firstRow,
                label + ": Window, Used, Free come first, in that order, before the rows");
        int attributed = breakdown.indexOf("Attributed (A1..F1) = A1 + A2 + ... + F1"),
                notAttributed = breakdown.indexOf("Not attributed (F2) = Used - Attributed"),
                total = breakdown.indexOf("Used = Attributed + Not attributed", notAttributed);
        check(firstRow < attributed && attributed < notAttributed && notAttributed < total,
                label + ": the subtotal, the residual and the total follow the rows, with their formulas");
        check(breakdown.contains("F1  Framing") && !breakdown.contains("Sum of the rows above"),
                label + ": the rows are fixed and the ambiguous sum line is gone");
        check(page.locator("#tokens-breakdown th").nth(2).textContent().matches(".*\\d+ measured of \\d+ sent.*"),
                label + ": the cumulative header says how many requests were measured of how many sent: "
                        + page.locator("#tokens-breakdown th").nth(2).textContent());
        // The figures keep to the labels: Attributed + Not attributed = Used, in the last request and in all requests.
        for (int col = 1; col <= 2; col++) {
            long usedValue = cell(page, "Used = input the provider reported", col);
            long attributedValue = cell(page, "Attributed (A1..F1)", col);
            long notAttributedValue = cell(page, "Not attributed (F2)", col);
            long totalValue = cell(page, "Used = Attributed + Not attributed", col);
            check(usedValue >= 0 && attributedValue + notAttributedValue == usedValue && totalValue == usedValue,
                    label + ": column " + col + ": " + attributedValue + " + " + notAttributedValue + " = " + usedValue + " (total " + totalValue + ")");
        }
        check(cellText(page, "Window (max)", 2).isBlank() && cellText(page, "Free = Window - Used", 2).isBlank(),
                label + ": Window and Free are blank in the cumulative column");
        check(breakdown.contains("not a fill level") || breakdown.contains("It is not a fill level"),
                label + ": the table says why the cumulative column has no Window and no Free");
        check(page.locator("#tokens-topics").textContent().contains("History in the last request"),
                label + ": the special topics table is shown");
        check(parts.contains("Estimated total of the parts above"), label + ": the estimate is shown");
        check(parts.contains("Measured first request") && !parts.contains("not recorded"),
                label + ": the measured first request is shown beside it");
        check(parts.contains("Measured minus estimated"), label + ": the difference is shown");
        check(page.locator("#tab-tokens button").count() == 1,
                label + ": the tab has only its Refresh button, nothing that cuts");
        int rows = page.locator("#tokens-turns tr").count() - 1;
        check(rows >= 2, label + ": the per-turn table has a row for each of two turns (" + rows + ")");
        if (rows >= 2) {
            int first = Integer.parseInt(page.locator("#tokens-turns tr").nth(1).locator("td").nth(1).textContent().strip());
            check(first >= minFirstRequest, label + ": turn 1's first request is at least " + minFirstRequest + ": " + first);
            String growth = page.locator("#tokens-turns tr").nth(2).locator("td").nth(2).textContent().strip();
            check(growth.startsWith("+"), label + ": turn 2 shows its growth: " + growth);
        }
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/tokens-" + label + ".png")));
    }

    private static String rest(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL.replaceAll("/$", "") + path))
                .header("Content-Type", "application/json");
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void waitIdle(String chat) throws Exception {
        Thread.sleep(500);
        for (int i = 0; i < 240; i++) {
            if (rest("GET", "/api/projects/project1/chats/" + chat + "/status", null).contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
        check(false, "chat-" + chat + " did not become idle");
    }

    private static void check(boolean condition, String what) {
        if (condition) { passed++; System.out.println("ok:   " + what); }
        else { failed++; System.out.println("FAIL: " + what); }
    }
}
