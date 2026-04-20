package com.scivicslab.chatui.fstools;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * MCP tools for web access: fetch a URL and search the web via DuckDuckGo.
 */
@ApplicationScoped
public class WebTools {

    private static final Logger logger = Logger.getLogger(WebTools.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 5000;
    private static final int DEFAULT_MAX_RESULTS = 10;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(description = "Fetch the content of a URL and return it as readable text. "
            + "HTML pages are converted to plain text with their main content extracted.")
    String fetch(
            @ToolArg(description = "URL to fetch") String url,
            @ToolArg(description = "Maximum characters to return (default 5000)", required = false) Integer max_length,
            @ToolArg(description = "Return raw HTML instead of extracted text (default false)", required = false) Boolean raw) {

        if (url == null || url.isBlank()) return "Error: 'url' argument is required";
        int limit = (max_length != null && max_length > 0) ? max_length : DEFAULT_MAX_LENGTH;
        boolean rawMode = (raw != null && raw);

        try {
            logger.info("Fetching URL: " + url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "chat-ui/1.0 (fetch tool)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "HTTP " + response.statusCode() + ": " + truncate(response.body(), 500);
            }

            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            String text = (rawMode || !contentType.contains("html"))
                    ? response.body()
                    : extractText(response.body(), url);

            return truncate(text, limit);
        } catch (Exception e) {
            logger.warning("fetch failed for " + url + ": " + e.getMessage());
            return "Error fetching " + url + ": " + e.getMessage();
        }
    }

    @Tool(description = "Search the web using DuckDuckGo. Returns titles, URLs, and snippets. "
            + "No API key required.")
    String web_search(
            @ToolArg(description = "Search query") String query,
            @ToolArg(description = "Maximum number of results (default 10)", required = false) Integer max_results) {

        if (query == null || query.isBlank()) return "Error: 'query' argument is required";
        int limit = (max_results != null && max_results > 0) ? max_results : DEFAULT_MAX_RESULTS;

        try {
            logger.info("Web search: " + query);
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; chat-ui/1.0)")
                    .header("Accept-Language", "ja,en;q=0.9")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) return "Search failed: HTTP " + response.statusCode();

            return parseResults(response.body(), limit);
        } catch (Exception e) {
            logger.warning("web_search failed for '" + query + "': " + e.getMessage());
            return "Error searching for '" + query + "': " + e.getMessage();
        }
    }

    private static String extractText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        doc.select("script, style, nav, footer, header, aside, [role=navigation]").remove();

        Element main = doc.selectFirst("main, article, [role=main], #content, .content, #main");
        Element root = main != null ? main : doc.body();
        if (root == null) return doc.text();

        StringBuilder sb = new StringBuilder();
        for (Element block : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th")) {
            String tag = block.tagName();
            String t = block.text().trim();
            if (t.isEmpty()) continue;
            if (tag.startsWith("h")) {
                sb.append("#".repeat(tag.charAt(1) - '0')).append(" ").append(t).append("\n\n");
            } else if ("pre".equals(tag)) {
                sb.append("```\n").append(block.wholeText().trim()).append("\n```\n\n");
            } else if ("li".equals(tag)) {
                sb.append("- ").append(t).append("\n");
            } else {
                sb.append(t).append("\n\n");
            }
        }
        return sb.isEmpty() ? root.text() : sb.toString().stripTrailing();
    }

    private static String parseResults(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select(".result");
        if (results.isEmpty()) results = doc.select(".web-result");
        if (results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Element result : results) {
            if (count >= maxResults) break;
            String title = text(result, ".result__title, .result__a");
            String href = extractUrl(result, ".result__url, .result__a");
            String snippet = text(result, ".result__snippet");
            if (title.isBlank() && href.isBlank()) continue;
            sb.append(count + 1).append(". ").append(title).append("\n");
            if (!href.isBlank()) sb.append("   URL: ").append(href).append("\n");
            if (!snippet.isBlank()) sb.append("   ").append(snippet).append("\n");
            sb.append("\n");
            count++;
        }
        return count == 0 ? "No results found." : sb.toString().stripTrailing();
    }

    private static String text(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        return el != null ? el.text().strip() : "";
    }

    private static String extractUrl(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        if (el == null) return "";
        String val = el.attr("href");
        if (val.contains("uddg=")) {
            int start = val.indexOf("uddg=") + 5;
            int end = val.indexOf('&', start);
            String enc = end < 0 ? val.substring(start) : val.substring(start, end);
            try { return URLDecoder.decode(enc, StandardCharsets.UTF_8); } catch (Exception ignored) {}
        }
        return val.startsWith("http") ? val : "";
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n[truncated — " + text.length() + " chars total]";
    }
}
