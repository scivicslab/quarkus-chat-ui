package com.scivicslab.coderagent.core.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SSE event DTO for chat communication.
 *
 * <p>Server to Client event types:</p>
 * <ul>
 *   <li>{@code delta} - Partial text content from the LLM</li>
 *   <li>{@code result} - Final result with session ID and cost info</li>
 *   <li>{@code error} - Error message</li>
 *   <li>{@code info} - Informational message (e.g., model changed)</li>
 *   <li>{@code status} - Status update (model, session, busy state)</li>
 *   <li>{@code thinking} - Thinking/activity indicator</li>
 *   <li>{@code prompt} - Interactive prompt from the LLM (tool permission, yes/no, etc.)</li>
 *   <li>{@code heartbeat} - Keep-alive for SSE connection</li>
 *   <li>{@code log} - Server log entry (level, logger, message, timestamp)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(
    String type,
    String content,
    String sessionId,
    Double costUsd,
    Long durationMs,
    String model,
    Boolean busy,
    String promptId,
    String promptType,
    List<String> options,
    String logLevel,
    String loggerName,
    Long timestamp
) {

    public static ChatEvent delta(String content) {
        return new ChatEvent("delta", content, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent result(String sessionId, double costUsd, long durationMs) {
        return new ChatEvent("result", null, sessionId, costUsd, durationMs, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent result(String sessionId, double costUsd, long durationMs, String model, boolean busy) {
        return new ChatEvent("result", null, sessionId, costUsd, durationMs, model, busy, null, null, null, null, null, null);
    }

    public static ChatEvent error(String content) {
        return new ChatEvent("error", content, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent info(String content) {
        return new ChatEvent("info", content, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent status(String model, String sessionId, boolean busy) {
        return new ChatEvent("status", null, sessionId, null, null, model, busy, null, null, null, null, null, null);
    }

    public static ChatEvent thinking(String content) {
        return new ChatEvent("thinking", content, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent heartbeat() {
        return new ChatEvent("heartbeat", null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent prompt(String promptId, String content, String promptType, List<String> options) {
        return new ChatEvent("prompt", content, null, null, null, null, null,
                             promptId, promptType, options, null, null, null);
    }

    public static ChatEvent log(String level, String logger, String message, long ts) {
        return new ChatEvent("log", message, null, null, null, null, null,
                             null, null, null, level, logger, ts);
    }
}
