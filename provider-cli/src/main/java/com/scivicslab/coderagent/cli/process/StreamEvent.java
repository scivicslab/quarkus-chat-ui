package com.scivicslab.coderagent.cli.process;

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
    public StreamEvent(String type, String content, String sessionId,
                       double costUsd, long durationMs, boolean isError, String rawJson) {
        this(type, content, sessionId, costUsd, durationMs, isError, rawJson, null, null, null);
    }

    public static StreamEvent text(String type, String content) {
        return new StreamEvent(type, content, null, -1, -1, false, null);
    }

    public static StreamEvent result(String sessionId, double costUsd, long durationMs) {
        return new StreamEvent("result", null, sessionId, costUsd, durationMs, false, null);
    }

    public static StreamEvent error(String message) {
        return new StreamEvent("error", message, null, -1, -1, true, null);
    }

    public static StreamEvent prompt(String promptId, String content,
                                      String promptType, List<String> options, String rawJson) {
        return new StreamEvent("prompt", content, null, -1, -1, false, rawJson,
                               promptId, promptType, options);
    }

    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    public boolean isPrompt() {
        return "prompt".equals(type);
    }
}
