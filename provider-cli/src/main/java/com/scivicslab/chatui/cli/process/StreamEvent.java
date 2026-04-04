package com.scivicslab.chatui.cli.process;

import java.util.List;

/**
 * Represents a parsed event from CLI stream-json output (claude, codex).
 */
public record StreamEvent(
    String type,
    String content,
    String sessionId,
    double costUsd,
    long durationMs,
    boolean isError,
    String rawJson,
    String promptId,
    String promptType,
    List<String> options
) {
    /**
     * Convenience constructor for events without prompt-related fields.
     *
     * @param type       the event type (e.g. "assistant", "result", "error")
     * @param content    the text content of the event, may be {@code null}
     * @param sessionId  the session identifier, may be {@code null}
     * @param costUsd    the accumulated cost in USD, or {@code -1} if unavailable
     * @param durationMs the elapsed time in milliseconds, or {@code -1} if unavailable
     * @param isError    whether this event represents an error
     * @param rawJson    the original JSON line from the CLI output
     */
    public StreamEvent(String type, String content, String sessionId,
                       double costUsd, long durationMs, boolean isError, String rawJson) {
        this(type, content, sessionId, costUsd, durationMs, isError, rawJson, null, null, null);
    }

    /**
     * Creates a text-bearing event with no session or cost information.
     *
     * @param type    the event type
     * @param content the text content
     * @return a new {@code StreamEvent}
     */
    public static StreamEvent text(String type, String content) {
        return new StreamEvent(type, content, null, -1, -1, false, null);
    }

    /**
     * Creates a result event signalling that the turn has completed.
     *
     * @param sessionId  the session identifier
     * @param costUsd    the total cost in USD
     * @param durationMs the total duration in milliseconds
     * @return a new result {@code StreamEvent}
     */
    public static StreamEvent result(String sessionId, double costUsd, long durationMs) {
        return new StreamEvent("result", null, sessionId, costUsd, durationMs, false, null);
    }

    /**
     * Creates an error event with the given message.
     *
     * @param message the error description
     * @return a new error {@code StreamEvent}
     */
    public static StreamEvent error(String message) {
        return new StreamEvent("error", message, null, -1, -1, true, null);
    }

    /**
     * Creates a prompt event representing an interactive question from the LLM.
     *
     * @param promptId   the unique identifier for this prompt
     * @param content    the question text
     * @param promptType the prompt category (e.g. "ask_user")
     * @param options    selectable options presented to the user, may be empty
     * @param rawJson    the original JSON line
     * @return a new prompt {@code StreamEvent}
     */
    public static StreamEvent prompt(String promptId, String content,
                                      String promptType, List<String> options, String rawJson) {
        return new StreamEvent("prompt", content, null, -1, -1, false, rawJson,
                               promptId, promptType, options);
    }

    /**
     * Returns whether this event carries non-empty text content.
     *
     * @return {@code true} if {@code content} is non-null and non-empty
     */
    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    /**
     * Returns whether this event is an interactive prompt requiring user input.
     *
     * @return {@code true} if the event type is "prompt"
     */
    public boolean isPrompt() {
        return "prompt".equals(type);
    }
}
