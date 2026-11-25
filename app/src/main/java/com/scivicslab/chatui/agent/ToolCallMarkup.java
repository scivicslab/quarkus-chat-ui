package com.scivicslab.chatui.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds a model's own tool-call markup in the text of a reply. A server that reads tool calls turns a call it understands
 * into {@code tool_calls}; one it does not understand (GLM-5.3's {@code <tool_call>edit<arg_key>...} for a tool name the
 * request did not offer, measured on 2026-11-25) stays in the reply's text. Such a reply is not an answer. Nothing is run
 * from this text: the detector only says that the reply is a failed call and not a final answer.
 *
 * <p>The list is small on purpose and independent of any provider: the markers of the formats open models write.</p>
 */
public final class ToolCallMarkup {

    /** The opening markers of the known formats. */
    static final List<String> MARKERS = List.of("<tool_call>", "<tool_calls>", "<function_call>", "<|tool_call", "[TOOL_CALLS]",
            "<|python_tag|>");

    private static final Pattern FENCED = Pattern.compile("(?s)```.*?```");

    private ToolCallMarkup() {}

    /**
     * @param text the text of a reply that carried no tool call
     * @return whether it holds a marker outside a fenced code block, so an answer that quotes the markup in a code block
     *         is still an answer
     */
    public static boolean in(String text) {
        if (text == null || text.isBlank()) return false;
        String outside = FENCED.matcher(text).replaceAll(" ");
        for (String marker : MARKERS) if (outside.contains(marker)) return true;
        return false;
    }
}
