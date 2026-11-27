package com.scivicslab.chatui.core.provider;

import java.util.Locale;
import java.util.function.IntSupplier;

/**
 * The one place that says how large a conversation's context window is, as a short label ({@code 1M}, {@code 200K}, {@code ?}),
 * so that every screen and tool that shows a model shows the window beside it.
 *
 * <p>The rule, in the order it is applied:</p>
 * <ol>
 *   <li>A model value ending in {@code [200k]} is 200K and one ending in {@code [1m]} is 1M, whatever the provider: the
 *       window is part of the value the person picked ({@code ClaudeModelChoice}).</li>
 *   <li>Any other Claude Code model (or none chosen) runs on the CLI's default window, 1M.</li>
 *   <li>For every other provider the window is what its server reported, written as {@code 131K} or {@code 1.5M}.</li>
 *   <li>A provider that reported nothing gives {@code ?}: the window is not known, and no number is invented.</li>
 * </ol>
 */
public final class ContextWindowLabel {

    /** The provider id of Claude Code ({@code ClaudeCodeProvider.ID}); its plain models run on the 1M window. */
    private static final String CLAUDE = "claude";
    private static final String SUFFIX_200K = "[200k]";
    private static final String SUFFIX_1M = "[1m]";

    private ContextWindowLabel() {}

    /**
     * @param providerId     the provider kind ({@code claude}, {@code openai-compat}, {@code codex}), or {@code null}
     * @param model          the model value as the conversation holds it, or {@code null}
     * @param reportedTokens what the provider's server reports as the window in tokens, read only when the model value
     *                       does not already say it; a value of {@code 0} or less means it reported nothing
     * @return {@code 200K}, {@code 1M}, a size such as {@code 131K}, or {@code ?} when the window is not known
     */
    public static String of(String providerId, String model, IntSupplier reportedTokens) {
        String m = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);
        if (m.endsWith(SUFFIX_200K)) return "200K";
        if (m.endsWith(SUFFIX_1M)) return "1M";
        if (CLAUDE.equals(providerId)) return "1M";
        return ofTokens(reportedTokens.getAsInt());
    }

    /** @return {@code model · label}, the form every screen shows; {@code (no model)} when no model is chosen yet */
    public static String withModel(String model, String label) {
        if (model == null || model.isBlank()) return "(no model)";
        return model + " · " + label;
    }

    /** @return a token count as {@code 8K}, {@code 262K}, {@code 1M} or {@code 1.5M}; {@code ?} for no count */
    static String ofTokens(int tokens) {
        if (tokens <= 0) return "?";
        if (tokens < 1_000_000) return Math.round(tokens / 1000.0) + "K";
        double millions = tokens / 1_000_000.0;
        return millions == Math.rint(millions)
                ? String.format(Locale.ROOT, "%.0fM", millions)
                : String.format(Locale.ROOT, "%.1fM", millions);
    }
}
