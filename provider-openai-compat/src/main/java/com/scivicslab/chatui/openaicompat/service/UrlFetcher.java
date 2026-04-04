package com.scivicslab.chatui.openaicompat.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fetches a web page and extracts its text content.
 */
public class UrlFetcher {

    private static final Logger logger = Logger.getLogger(UrlFetcher.class.getName());
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BODY_SIZE = 2 * 1024 * 1024;
    private static final int MAX_TEXT_LENGTH = 30_000;

    /**
     * Fetches the web page at the given URL and returns its extracted text content.
     * Navigation, script, and style elements are stripped before extraction.
     * The result is truncated to {@value #MAX_TEXT_LENGTH} characters if necessary.
     *
     * @param url the URL to fetch
     * @return the page title and body text, or an error message if the fetch fails
     */
    public static String fetchAndExtract(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; ChatUi/1.0)")
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(MAX_BODY_SIZE)
                    .followRedirects(true)
                    .get();

            doc.select("script, style, nav, footer, header, aside, .sidebar, .menu, .nav").remove();

            String title = doc.title();
            String text = doc.body() != null ? doc.body().text() : "";
            if (text.length() > MAX_TEXT_LENGTH) text = text.substring(0, MAX_TEXT_LENGTH) + "\n... (truncated)";

            StringBuilder result = new StringBuilder();
            if (!title.isEmpty()) result.append("Title: ").append(title).append("\n\n");
            result.append(text);

            logger.info("Fetched URL: " + url + " -> " + text.length() + " chars");
            return result.toString();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to fetch URL: " + url, e);
            return "[Error] Failed to fetch " + url + ": " + e.getMessage();
        }
    }
}
