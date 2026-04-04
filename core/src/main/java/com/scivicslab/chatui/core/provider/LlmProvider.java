package com.scivicslab.chatui.core.provider;

import com.scivicslab.chatui.core.rest.ChatEvent;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * SPI for LLM backend providers.
 *
 * <p>Each provider handles prompt dispatch, model listing, and lifecycle management.
 * The {@link #sendPrompt} method is always called from a virtual thread so blocking is allowed.</p>
 */
public interface LlmProvider {

    /** Unique provider identifier used in config (e.g., "claude", "codex", "openai-compat"). */
    String id();

    /** Human-readable display name (e.g., "Claude", "Codex", "Local LLM"). */
    String displayName();

    /** Returns the list of available models for this provider. */
    List<ModelEntry> getAvailableModels();

    /** Returns the currently selected model name. */
    String getCurrentModel();

    /** Sets the current model. */
    void setModel(String model);

    /**
     * Sends a prompt and streams events to the emitter.
     * Called from a virtual thread — blocking is allowed.
     *
     * @param prompt  user prompt text
     * @param model   model name to use
     * @param emitter callback for streaming ChatEvents (delta, result, error, thinking, etc.)
     * @param ctx     per-request context (api key, images, activity callback)
     */
    void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx);

    /** Cancels the currently running request. */
    void cancel();

    // ---- Optional features with default no-op implementations ----

    /** Returns the current session ID, or null if not applicable (e.g., HTTP-based providers). */
    default String getSessionId() { return null; }

    /** True if this provider supports interactive user prompts (tool permission dialogs, etc.). */
    default boolean supportsInteractivePrompts() { return false; }

    /** Sends a response to an interactive prompt (tool permission, yes/no, free text). */
    default void respond(String promptId, String response) throws IOException {
        throw new UnsupportedOperationException("Interactive prompts not supported by " + id());
    }

    /** True if the given input is a slash command handled by this provider. */
    default boolean isCommand(String input) { return false; }

    /** Handles a slash command and returns response events. */
    default List<ChatEvent> handleCommand(String input) {
        return List.of(ChatEvent.error("Slash commands not supported by provider: " + id()));
    }

    /** Provider capabilities for conditional feature activation. */
    default ProviderCapabilities capabilities() { return ProviderCapabilities.DEFAULT; }

    /**
     * Detects an API key from environment variables specific to this provider.
     * Returns null if no relevant env var is set.
     * Override in each provider (e.g., ANTHROPIC_API_KEY, OPENAI_API_KEY).
     */
    default String detectEnvApiKey() { return null; }

    /** A model entry returned by {@link #getAvailableModels()}. */
    record ModelEntry(String name, String type, String server) {}
}
