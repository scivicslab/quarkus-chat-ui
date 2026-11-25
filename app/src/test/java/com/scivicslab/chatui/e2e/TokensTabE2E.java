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
            return text == null ? -1 : Long.parseLong(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The cells and the legend swatches of a style share its class, so they have its colour, in every theme. */
    private void checkColours(Page page, String label) {
        String original = page.inputValue("#theme-select");
        List<String> themes = (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#theme-select option')).map(o => o.value)");
        for (String theme : themes) {
            page.selectOption("#theme-select", theme);
            page.waitForTimeout(150);
            Object result = page.evaluate("() => {"
                    + " const px = (c) => { const g = document.createElement('canvas').getContext('2d', { willReadFrequently: true });"
                    + "   g.fillStyle = '#000'; g.fillRect(0, 0, 1, 1); g.fillStyle = c; g.fillRect(0, 0, 1, 1); const d = g.getImageData(0, 0, 1, 1).data;"
                    + "   return 'rgb(' + d[0] + ', ' + d[1] + ', ' + d[2] + ')'; };"
                    + " const css = (el) => { const c = getComputedStyle(el); return { color: px(c.color), style: c.fontStyle }; };"
                    + " const cell = (label, cls) => { const row = Array.from(document.querySelectorAll('#tokens-breakdown tr'))"
                    + "   .find(r => r.children[0] && r.children[0].textContent.trim().startsWith(label)); return row ? row.children[1] : null; };"
                    + " const bgOf = (el) => { for (; el; el = el.parentElement) { const b = getComputedStyle(el).backgroundColor;"
                    + "   if (b && b !== 'rgba(0, 0, 0, 0)' && b !== 'transparent') return px(b); } return 'rgb(255, 255, 255)'; };"
                    + " const sw = (cls) => document.querySelector('#tokens-breakdown .tk-howto .' + cls);"
                    + " const m = cell('Used = input the provider reported'), e = cell('A1  System prompt'), k = cell('Attributed (A1..F1)');"
                    + " return { measured: [css(sw('tk-measured')), css(m)], estimated: [css(sw('tk-estimated')), css(e)],"
                    + "   calculated: [css(sw('tk-calculated')), css(k)], bg: bgOf(m),"
                    + "   classes: [m.className, e.className, k.className] }; }");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> r = (java.util.Map<String, Object>) result;
            @SuppressWarnings("unchecked") List<java.util.Map<String, String>> m = (List<java.util.Map<String, String>>) r.get("measured");
            @SuppressWarnings("unchecked") List<java.util.Map<String, String>> e = (List<java.util.Map<String, String>>) r.get("estimated");
            @SuppressWarnings("unchecked") List<java.util.Map<String, String>> k = (List<java.util.Map<String, String>>) r.get("calculated");
            String bg = (String) r.get("bg");
            check(m.get(0).get("color").equals(m.get(1).get("color")) && e.get(0).get("color").equals(e.get(1).get("color"))
                            && k.get(0).get("color").equals(k.get(1).get("color")) && k.get(0).get("style").equals(k.get(1).get("style")),
                    label + " [" + theme + "]: the legend swatches have the colours of the cells they stand for");
            check(!m.get(1).get("color").equals(e.get(1).get("color")) && !m.get(1).get("color").equals(k.get(1).get("color"))
                            && !e.get(1).get("color").equals(k.get(1).get("color")) && "italic".equals(k.get(1).get("style"))
                            && !"italic".equals(m.get(1).get("style")) && !"italic".equals(e.get(1).get("style")),
                    label + " [" + theme + "]: measured, estimated and calculated differ: " + m.get(1).get("color") + " / "
                            + e.get(1).get("color") + " / " + k.get(1).get("color") + " (calculated is italic)");
            double worst = Math.min(contrast(m.get(1).get("color"), bg), Math.min(contrast(e.get(1).get("color"), bg), contrast(k.get(1).get("color"), bg)));
            check(worst >= 3.0, label + " [" + theme + "]: every style reads against the background (contrast " + String.format("%.1f", worst)
                    + "; measured " + m.get(1).get("color") + ", estimated " + e.get(1).get("color") + ", calculated " + k.get(1).get("color") + " on " + bg + ")");
        }
        page.selectOption("#theme-select", original);
    }

    private static double[] rgb(String css) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(css);
        double[] out = new double[3];
        for (int i = 0; i < 3 && m.find(); i++) out[i] = Double.parseDouble(m.group(1));
        return out;
    }

    private static double luminance(String css) {
        double[] c = rgb(css);
        double[] l = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = c[i] / 255.0;
            l[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
    }

    /** The WCAG contrast ratio of two colours. */
    private static double contrast(String a, String b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
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
        check(page.locator("#tokens-breakdown th").count() == 3 && !breakdown.contains("Basis"),
                label + ": the Basis column is gone: " + page.locator("#tokens-breakdown th").allTextContents());
        // The how-to-read note comes first, then the table: unit, colour legend, the rule, what the two columns mean.
        check((Boolean) page.evaluate("() => { const box = document.getElementById('tokens-breakdown');"
                        + " const note = box.querySelector('.tk-howto'); const table = box.querySelector('table');"
                        + " return !!note && !!table && !!(note.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING); }"),
                label + ": a how-to-read note sits above the table");
        String howto = page.locator("#tokens-breakdown .tk-howto").textContent();
        check(howto.contains("How to read this table") && howto.contains("Unit: tokens") && howto.contains("black: measured")
                        && howto.contains("blue: estimated") && howto.contains("grey italic: calculated"),
                label + ": the note gives the unit and the colour legend");
        check(howto.contains("Every part of a request's input is counted in exactly one row")
                        && howto.contains("Last request:") && howto.contains("All requests:") && howto.contains("not a fill level"),
                label + ": the note gives the rule and what the two columns mean");
        checkColours(page, label);
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
            int first = Integer.parseInt(page.locator("#tokens-turns tr").nth(1).locator("td").nth(1).textContent().strip().replace(",", ""));
            check(first >= minFirstRequest, label + ": turn 1's first request is at least " + minFirstRequest + ": " + first);
            String growth = page.locator("#tokens-turns tr").nth(2).locator("td").nth(2).textContent().strip();
            check(growth.startsWith("+"), label + ": turn 2 shows its growth: " + growth);
        }
        checkNumbers(page, label);
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/tokens-" + label + ".png")));
    }

    /**
     * Every number of the tab's four tables is monospace, tabular, right-aligned and grouped by thousands: the cells of the
     * numeric columns have the computed style, and no cell of the tab holds a run of four digits or more without separators.
     */
    private void checkNumbers(Page page, String label) {
        for (String box : List.of("tokens-breakdown", "tokens-turns", "tokens-parts", "tokens-topics")) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> r = (java.util.Map<String, Object>) page.evaluate("(id) => {"
                    + " const cells = Array.from(document.querySelectorAll('#' + id + ' td.num'));"
                    + " const bad = cells.filter(td => { const c = getComputedStyle(td);"
                    + "   return !/monospace|Mono|Consolas|Menlo/.test(c.fontFamily) || c.textAlign !== 'right'"
                    + "     || !c.fontVariantNumeric.includes('tabular-nums'); }).map(td => td.textContent + ' | ' + getComputedStyle(td).fontFamily"
                    + "     + ' | ' + getComputedStyle(td).textAlign);"
                    + " const ungrouped = Array.from(document.querySelectorAll('#' + id + ' td, #' + id + ' th'))"
                    + "   .map(e => e.textContent).filter(t => /(^|[^\\d,.])\\d{4,}/.test(t));"
                    + " return { cells: cells.length, bad: bad, ungrouped: ungrouped }; }", box);
            check(((Number) r.get("cells")).intValue() > 0, label + ": " + box + " has numeric cells");
            check(((List<?>) r.get("bad")).isEmpty(), label + ": " + box + ": every numeric cell is monospace, tabular and right-aligned: " + r.get("bad"));
            check(((List<?>) r.get("ungrouped")).isEmpty(), label + ": " + box + ": no number of 1,000 or more lacks separators: " + r.get("ungrouped"));
        }
        if (page.locator("#tokens-turns tr").count() > 1) {
            String first = page.locator("#tokens-turns tr").nth(1).locator("td").nth(1).textContent().strip();
            check(first.matches("\\d{1,3}(,\\d{3})*"), label + ": the first request's total is grouped: " + first);
        }
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
