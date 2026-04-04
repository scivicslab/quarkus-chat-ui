package com.scivicslab.chatui.openaicompat.service;

import com.scivicslab.chatui.core.service.UrlFetchService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * jsoup-based implementation of {@link UrlFetchService}.
 */
@ApplicationScoped
public class JsoupUrlFetchService implements UrlFetchService {
    /**
     * Fetches the given URL using jsoup and extracts its text content.
     *
     * @param url the URL to fetch
     * @return the extracted text, or an error message if the fetch fails
     */
    @Override
    public String fetchAndExtract(String url) {
        return UrlFetcher.fetchAndExtract(url);
    }
}
