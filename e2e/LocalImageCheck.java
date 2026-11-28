import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Browser check of the pictures an answer refers to by path or address (LocalImageInAnswer_260904_oo01). Run against a
 * throwaway instance whose user home is /tmp/imgcheck/home (the read and write root is its works directory) and that
 * talks to e2e/ScriptedModelServer.java:
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ScriptedModelServer.java 28978 &amp;
 * java -Duser.home=/tmp/imgcheck/home -Dquarkus.http.port=28979 -Dchat-ui.servers=http://127.0.0.1:28978 \
 *      -Dchat-ui.default-model=fake -jar app/target/quarkus-chat-ui-3.0.0-SNAPSHOT.jar &amp;
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/LocalImageCheck.java http://localhost:28979
 * </pre>
 *
 * Exits 0 when every check passes; writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class LocalImageCheck {

    static String base = "http://localhost:28979";
    static int failures = 0;
    static final HttpClient http = HttpClient.newHttpClient();

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static void png(Path file, int w, int h) throws Exception {
        Files.createDirectories(file.getParent());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, (x * 255 / w) << 16 | (y * 255 / h) << 8 | 90);
        ImageIO.write(img, "png", file.toFile());
    }

    static HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        Path works = Path.of("/tmp/imgcheck/home/works");
        png(works.resolve("in.png"), 1860, 960);   // wider than any bubble
        png(works.resolve("sub/rel.png"), 300, 100);
        png(Path.of("/tmp/imgcheck/outside/o.png"), 50, 50);
        Files.writeString(works.resolve("in.svg"),
                "<svg xmlns='http://www.w3.org/2000/svg' width='400' height='200'><rect width='400' height='200' fill='teal'/>"
                + "<script>window.__svgpwned=1</script></svg>");
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            Page page = ctx.newPage();
            List<String> errors = new ArrayList<>();
            page.onPageError(e -> errors.add(String.valueOf(e)));
            List<String> external = new ArrayList<>();
            page.onRequest(r -> { if (r.url().contains("127.0.0.1:1/")) external.add(r.url()); });
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#actors-tree .actor-name");
            page.waitForFunction("() => document.querySelectorAll('#model-select option').length > 0");
            page.fill("#prompt-input", "IMG-SHOW show the pictures");
            page.click("#send-btn");
            page.waitForFunction("() => document.querySelector('.message.assistant') && document.querySelector('.message.assistant').textContent.includes('END-IMG')",
                    null, new Page.WaitForFunctionOptions().setTimeout(30000));
            Thread.sleep(2500);
            verify(page, "live");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/localimage-live.png")).setFullPage(true));

            // The history: the same answer drawn again after a reload.
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForFunction("() => document.querySelector('.message.assistant') && document.querySelector('.message.assistant').textContent.includes('END-IMG')");
            Thread.sleep(2500);
            verify(page, "after reload");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/localimage-reload.png")).setFullPage(true));

            // A click opens the picture in a tab of its own, at the address the server answers.
            Page popup = ctx.waitForPage(() -> page.locator("img[data-local-path$='in.png']").first().click());
            popup.waitForLoadState();
            check(popup.url().contains("api/local-image?path="), "a click opens the picture in a new tab: " + popup.url());
            check(popup.url().endsWith("in.png"), "the new tab shows the png itself");
            check(errors.isEmpty(), "no page errors: " + errors);
            check(external.isEmpty(), "nothing was requested from the http address: " + external);

            // The server's own answers.
            HttpResponse<byte[]> svg = get("/api/local-image?path=" + java.net.URLEncoder.encode(works.resolve("in.svg").toString(), "UTF-8"));
            check(svg.statusCode() == 200 && svg.headers().firstValue("Content-Security-Policy").orElse("").startsWith("sandbox"),
                    "the svg answer carries the sandbox policy");
            popup.navigate(base + "/api/local-image?path=" + java.net.URLEncoder.encode(works.resolve("in.svg").toString(), "UTF-8"));
            Thread.sleep(500);
            check(Boolean.FALSE.equals(popup.evaluate("() => window.__svgpwned === 1")), "the script inside an svg does not run when it is opened in a tab");
            check(get("/api/local-image?path=" + java.net.URLEncoder.encode("/tmp/imgcheck/outside/o.png", "UTF-8")).statusCode() == 403, "a file outside the read root is 403");
            check(get("/api/local-image?path=%7E%2Fworks%2Fin.png").statusCode() == 400, "a path starting with ~ is 400");
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    static void verify(Page page, String when) {
        String t = when + ": ";
        // Each picture, in the order the answer wrote it.
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) page.evaluate("""
            () => {
              const box = document.querySelector('.message.assistant');
              const imgs = Array.from(box.querySelectorAll('img')).map(i => ({path: i.getAttribute('data-local-path'), src: (i.getAttribute('src')||'').substring(0, 30), nw: i.naturalWidth, w: Math.round(i.getBoundingClientRect().width)}));
              return {imgs: imgs, notices: Array.from(box.querySelectorAll('.image-notice')).map(n => n.textContent),
                      svgs: box.querySelectorAll('svg').length, bubble: Math.round(box.getBoundingClientRect().width), overflow: box.scrollWidth - box.clientWidth};
            }""");
        List<java.util.Map<String, Object>> imgs = (List<java.util.Map<String, Object>>) m.get("imgs");
        List<String> notices = (List<String>) m.get("notices");
        long bubble = ((Number) m.get("bubble")).longValue();
        check(imgs.stream().anyMatch(i -> "/tmp/imgcheck/home/works/in.png".equals(i.get("path")) && ((Number) i.get("nw")).intValue() == 1860),
                t + "an absolute path inside the read root is drawn");
        check(imgs.stream().filter(i -> "/tmp/imgcheck/home/works/in.png".equals(i.get("path"))).allMatch(i -> ((Number) i.get("w")).longValue() <= bubble),
                t + "no picture is wider than the bubble (" + bubble + " px)");
        check(((Number) m.get("overflow")).intValue() <= 1, t + "the bubble does not overflow sideways: " + m.get("overflow"));
        check(imgs.stream().anyMatch(i -> "sub/rel.png".equals(i.get("path")) && ((Number) i.get("nw")).intValue() == 300), t + "a relative path is drawn from the write directory");
        check(imgs.stream().filter(i -> "/tmp/imgcheck/home/works/in.png".equals(i.get("path"))).count() == 2, t + "a file:// address is drawn too");
        check(imgs.stream().anyMatch(i -> "/tmp/imgcheck/home/works/in.svg".equals(i.get("path")) && ((Number) i.get("nw")).intValue() > 0), t + "an svg file is drawn");
        check(imgs.stream().anyMatch(i -> i.get("path") == null && String.valueOf(i.get("src")).startsWith("data:image/png") && ((Number) i.get("nw")).intValue() == 1), t + "a data: png is drawn");
        check(notices.stream().anyMatch(n -> n.contains("403") && n.contains("outside the readable range") && n.contains("/tmp/imgcheck/outside/o.png")),
                t + "a file outside the read root shows the reason: " + notices);
        check(notices.stream().anyMatch(n -> n.contains("400") && n.contains("~/works/in.png")), t + "a ~ path shows the reason");
        check(notices.stream().anyMatch(n -> n.contains("http://127.0.0.1:1/x.png")), t + "an http address is not shown");
        check(notices.stream().anyMatch(n -> n.contains("data:image/svg+xml")), t + "a data: svg is not shown");
        check(imgs.stream().noneMatch(i -> String.valueOf(i.get("src")).startsWith("http")), t + "no <img> points at an http address");
    }
}
