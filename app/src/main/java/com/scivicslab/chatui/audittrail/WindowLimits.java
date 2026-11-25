package com.scivicslab.chatui.audittrail;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The context window of a model whose server does not report one (Claude Code, Codex): a fixed table kept in the
 * settings, {@code chat-ui.window-limits}, as {@code name=tokens} entries separated by commas. A model is matched by
 * the longest entry name its own name contains, ignoring case. A local LLM's window is read from its server instead.
 */
public final class WindowLimits {

    /**
     * What the table holds when the setting names nothing: the Claude families and GLM-5.3. Only a fallback: a conversation
     * whose provider reported the window ({@code modelUsage.contextWindow} of Claude Code's {@code result} line) uses that.
     * The Claude values are what CLI 2.1.331 reported for the aliases {@code sonnet}, {@code opus} and {@code haiku} on
     * 2026-11-25 (1,000,000 for each; {@code CLAUDE_CODE_DISABLE_1M_CONTEXT=1} made {@code sonnet} report 200,000). A
     * different CLI version, account or model flag can give another window, so they are unverified beyond that run.
     * The GLM-5.3 value is unverified here.
     */
    public static final String DEFAULTS = "opus=1000000,sonnet=1000000,haiku=1000000,glm-5.3=262144";

    private final Map<String, Integer> table = new LinkedHashMap<>();

    private WindowLimits() {}

    /**
     * @param setting the value of {@code chat-ui.window-limits}, or {@code null}; its entries replace the default ones
     *                of the same name and add to them
     * @return the table
     */
    public static WindowLimits of(String setting) {
        WindowLimits limits = new WindowLimits();
        limits.add(DEFAULTS);
        limits.add(setting);
        return limits;
    }

    private void add(String spec) {
        if (spec == null) return;
        for (String entry : spec.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String name = entry.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            try {
                int tokens = Integer.parseInt(entry.substring(eq + 1).strip());
                if (!name.isEmpty() && tokens > 0) table.put(name, tokens);
            } catch (NumberFormatException e) {
                // An entry that is not a number is left out; the others still apply.
            }
        }
    }

    /** @return the window of {@code model} in tokens, or {@code -1} when no entry matches */
    public int windowOf(String model) {
        if (model == null) return -1;
        String m = model.toLowerCase(Locale.ROOT);
        String best = null;
        for (String name : table.keySet()) {
            if (m.contains(name) && (best == null || name.length() > best.length())) best = name;
        }
        return best == null ? -1 : table.get(best);
    }
}
