package com.scivicslab.chatui.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool calls an LLM writes as plain text:
 *
 * <pre>{@code
 * <invoke name="list_directory">
 * <parameter name="path">/home/devteam/works</parameter>
 * <reason>need the file names</reason>
 * </invoke>
 * }</pre>
 *
 * <p>Ported from chat-ui-with-audit-trail (parsing logic unchanged). Tolerant on purpose: it accepts a
 * missing {@code </parameter>}, a bare {@code <reason>} without a close tag, and strips the stray
 * {@code <|"|>} special-token junk some models interleave.</p>
 */
public final class TextToolCallParser {

    private TextToolCallParser() {}

    private static final Pattern INVOKE =
            Pattern.compile("<invoke\\s+name=\"([^\"]+)\"\\s*>(.*?)</invoke>", Pattern.DOTALL);
    private static final Pattern PARAM = Pattern.compile(
            "<parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)(?:</parameter>|(?=<parameter)|(?=<reason)|(?=</invoke>)|$)",
            Pattern.DOTALL);
    private static final Pattern REASON = Pattern.compile(
            "<reason\\s*>(.*?)(?:</reason>|(?=</invoke>)|(?=<parameter)|$)", Pattern.DOTALL);
    private static final Pattern JUNK_TOKEN = Pattern.compile("<\\|[^>]*\\|>");

    /** True when {@code content} contains at least one textual {@code <invoke ...>} tool-call block. */
    public static boolean looksLikeTextToolCall(String content) {
        return content != null && content.contains("<invoke ") && content.contains("name=");
    }

    /**
     * Parses every textual tool call in {@code content}.
     *
     * @return the calls, ids {@code text-call-0}, {@code text-call-1}, ...; empty when there are none
     */
    public static List<ToolCall> parse(String content) {
        List<ToolCall> calls = new ArrayList<>();
        if (!looksLikeTextToolCall(content)) return calls;
        Matcher inv = INVOKE.matcher(content);
        int idx = 0;
        while (inv.find()) {
            String name = inv.group(1).trim();
            String body = inv.group(2);
            Map<String, String> args = new LinkedHashMap<>();
            Matcher pm = PARAM.matcher(body);
            while (pm.find()) {
                args.put(pm.group(1).trim(), clean(pm.group(2)));
            }
            Matcher rm = REASON.matcher(body);
            if (rm.find() && !args.containsKey("reason")) {
                args.put("reason", clean(rm.group(1)));
            }
            calls.add(new ToolCall("text-call-" + idx++, name, toJson(args)));
        }
        return calls;
    }

    /** Removes the {@code <invoke>...</invoke>} block(s) and leaked special tokens from prose to keep. */
    public static String stripToolCallBlocks(String content) {
        if (content == null) return "";
        String out = content.replaceAll("(?s)<function_calls>.*?</function_calls>", "");
        out = out.replaceAll("(?s)<invoke\\s+name=\"[^\"]+\"\\s*>.*?</invoke>", "");
        return JUNK_TOKEN.matcher(out).replaceAll("").trim();
    }

    private static String clean(String v) {
        if (v == null) return "";
        return JUNK_TOKEN.matcher(v).replaceAll("").trim();
    }

    private static String toJson(Map<String, String> args) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> out.append(c);
            }
        }
        return out.toString();
    }
}
