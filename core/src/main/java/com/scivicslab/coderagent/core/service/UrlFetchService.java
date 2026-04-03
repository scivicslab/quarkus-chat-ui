package com.scivicslab.coderagent.core.service;

/**
 * SPI for URL fetching. Implemented by provider-openai-compat using jsoup.
 * Injected into ChatResource via CDI to avoid a direct dependency from core
 * to provider-openai-compat.
 */
public interface UrlFetchService {
    /**
     * Fetches the URL and returns extracted text content,
     * or a string prefixed with "[Error]" on failure.
     */
    String fetchAndExtract(String url);
}
