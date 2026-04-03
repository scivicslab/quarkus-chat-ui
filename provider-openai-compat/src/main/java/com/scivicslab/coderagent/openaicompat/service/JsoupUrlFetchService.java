package com.scivicslab.coderagent.openaicompat.service;

import com.scivicslab.coderagent.core.service.UrlFetchService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * jsoup-based implementation of {@link UrlFetchService}.
 */
@ApplicationScoped
public class JsoupUrlFetchService implements UrlFetchService {
    @Override
    public String fetchAndExtract(String url) {
        return UrlFetcher.fetchAndExtract(url);
    }
}
