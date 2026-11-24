package com.scivicslab.chatui.core.provider;

import java.util.List;
import java.util.function.Consumer;

/**
 * Per-request context passed to {@link LlmProvider#sendPrompt}.
 *
 * <p>Carries request-scoped data without polluting the provider interface with many parameters.
 * Providers use only the fields they need and ignore the rest.</p>
 */
public record ProviderContext(
    /** API key (null for CLI-based providers that use system auth). */
    String apiKey,
    /** Image data URLs for multimodal prompts (empty list if not used). */
    List<String> imageDataUrls,
    /** Whether to disable thinking mode (Qwen3-specific). */
    boolean noThink,
    /** Called on each stream event so the watchdog can reset its stall timer. */
    Runnable onActivity,
    /**
     * Called with the exact request body just before it is sent, by providers that build one
     * (null when the caller does not want it). The I/O log records this string, so the record
     * shows the {@code messages} array the model actually read.
     */
    Consumer<String> onRequestBody,
    /**
     * Whether the caller handles a reply cut off at a token limit itself. When {@code true}, a provider
     * that sees {@code finish_reason=length} keeps the partial reply in its history and sends a
     * {@code cut_off} event instead of an {@code error}; when {@code false}, it reports an error and
     * keeps nothing, as before.
     */
    boolean handlesCutOff,
    /**
     * Whether this request must be answered in text: a provider that sends tool definitions sends them with
     * {@code tool_choice: "none"}, so the model cannot call a tool. The agent loop sets it for the one request it makes
     * after a call repeated itself ({@code ending: repeated-call}).
     */
    boolean noToolCalls,
    /**
     * What the turn can cancel while this request runs: a provider that blocks on a connection registers what closes it,
     * so a cancel ends the request at once. {@link CancelScope#NEVER} when the caller has no turn to cancel.
     */
    CancelScope cancel
) {
    /** Constructor for callers that have no turn to cancel. */
    public ProviderContext(String apiKey, List<String> imageDataUrls, boolean noThink, Runnable onActivity,
                           Consumer<String> onRequestBody, boolean handlesCutOff, boolean noToolCalls) {
        this(apiKey, imageDataUrls, noThink, onActivity, onRequestBody, handlesCutOff, noToolCalls, CancelScope.NEVER);
    }

    /** Constructor for callers that do not ask for a text-only answer. */
    public ProviderContext(String apiKey, List<String> imageDataUrls, boolean noThink, Runnable onActivity,
                           Consumer<String> onRequestBody, boolean handlesCutOff) {
        this(apiKey, imageDataUrls, noThink, onActivity, onRequestBody, handlesCutOff, false, CancelScope.NEVER);
    }

    /** Constructor for callers that want the request body but not cut-off handling. */
    public ProviderContext(String apiKey, List<String> imageDataUrls, boolean noThink, Runnable onActivity,
                           Consumer<String> onRequestBody) {
        this(apiKey, imageDataUrls, noThink, onActivity, onRequestBody, false);
    }

    /** Constructor for callers that do not need the request body. */
    public ProviderContext(String apiKey, List<String> imageDataUrls, boolean noThink, Runnable onActivity) {
        this(apiKey, imageDataUrls, noThink, onActivity, null);
    }

    /** The same request, ended by {@code scope} when the turn is cancelled. */
    public ProviderContext withCancel(CancelScope scope) {
        return new ProviderContext(apiKey, imageDataUrls, noThink, onActivity, onRequestBody, handlesCutOff,
                noToolCalls, scope);
    }

    /** Convenience constructor for simple single-turn requests with no images. */
    public static ProviderContext simple(String apiKey) {
        return new ProviderContext(apiKey, List.of(), false, () -> {});
    }
}
