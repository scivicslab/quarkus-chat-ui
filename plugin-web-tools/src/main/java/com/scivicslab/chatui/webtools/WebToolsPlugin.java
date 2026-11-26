package com.scivicslab.chatui.webtools;

import com.scivicslab.chatui.agent.FetchTool;
import com.scivicslab.chatui.agent.ScholarSearchTool;
import com.scivicslab.chatui.agent.WebSearchTool;
import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.plugin.ToolParameter;

import org.json.JSONObject;

import java.util.List;

/**
 * The plugin that lets a conversation reach the web ({@code ProviderAndToolPlugins_260912_oo01}):
 * {@code web_search} (DuckDuckGo, then each result's page), {@code fetch} (one URL, PDFs
 * extracted to text) and {@code scholar_search} (OpenAlex; {@code ScholarSearchAndPdfFetch_260913_oo01}).
 * An instance started without this jar has no tool that leaves the local network; one started with
 * it records every call and its whole result in the I/O log.
 */
public class WebToolsPlugin implements ChatUiPlugin {

    @Override public String id() { return "web-tools"; }

    @Override
    public List<ConversationTool> tools() {
        return List.of(new WebSearch(), new Fetch(), new ScholarSearch());
    }

    static String input(String argumentsJson, String field) {
        try {
            return new JSONObject(argumentsJson == null ? "{}" : argumentsJson).optString(field, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code web_search(query)}: search, fetch the top results' pages, trimmed per result. */
    static final class WebSearch implements ConversationTool {
        @Override public String name() { return "web_search"; }

        @Override
        public String description() {
            return """
                    - web_search(query): search the web and fetch the top results' page content.
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            return WebSearchTool.searchAndFetch(input(argumentsJson, "query"));
        }

        @Override public boolean returnsPages() { return true; }

        /** Every page whole: the conversation's page-summarising transition fits them itself. */
        @Override
        public String executeUntrimmed(String argumentsJson) {
            return WebSearchTool.searchAndFetch(input(argumentsJson, "query"), WebSearchTool.FETCH_TOP_N, page -> page);
        }
    }

    /** {@code fetch(url)}: one page's readable text, cut at a fixed length and saying so. */
    static final class Fetch implements ConversationTool {
        @Override public String name() { return "fetch"; }

        @Override
        public String description() {
            return """
                    - fetch(url): fetch one URL you already have and return its readable text. A PDF is
                      extracted to text (first 60 pages). Stops at 5,000 characters with "[truncated N chars
                      total]" giving the real length. Use it to read a page web_search only summarised.
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            return FetchTool.fetch(input(argumentsJson, "url"));
        }
    }

    /** {@code scholar_search(query, sort, year_from, limit)}: works from OpenAlex with abstracts. */
    static final class ScholarSearch implements ConversationTool {
        @Override public String name() { return "scholar_search"; }

        @Override
        public String description() {
            return """
                    - scholar_search(query, sort, year_from, limit): search the scholarly literature through
                      OpenAlex (articles, papers, books, preprints). Returns per work the title, authors,
                      year, venue, citation count, DOI, open-access URL if any, and abstract. Use it rather
                      than web_search for a research question. "sort" is "relevance" (default) or "cited"
                      (for a survey run both); "year_from" keeps works from that year on; "limit" is 1–25
                      (default 10). To read a paper, call fetch on its open-access URL. Query in English.
                    """;
        }

        /** Only "query" is required; "year_from" and "limit" are numbers ({@link #input} reads them as text). */
        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.requiredString("query"),
                    new ToolParameter("sort", "string", false, "\"relevance\" (default) or \"cited\""),
                    new ToolParameter("year_from", "integer", false, "keep works published in or after this year"),
                    new ToolParameter("limit", "integer", false, "1-25, default 10"));
        }

        @Override
        public String execute(String argumentsJson) {
            return ScholarSearchTool.search(input(argumentsJson, "query"), input(argumentsJson, "sort"),
                    input(argumentsJson, "year_from"), input(argumentsJson, "limit"));
        }
    }
}
