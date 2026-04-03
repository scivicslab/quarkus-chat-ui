package com.scivicslab.coderagent.cli.process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parses stream-json output lines from CLI LLM tools (claude, codex) into {@link StreamEvent} objects.
 */
public class StreamEventParser {

    public StreamEvent parse(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) return null;
        try {
            JSONObject json = new JSONObject(jsonLine);
            String type = json.optString("type", "unknown");
            return switch (type) {
                case "system" -> parseSystem(json, jsonLine);
                case "assistant" -> parseAssistant(json, jsonLine);
                case "user" -> parseUser(json, jsonLine);
                case "result" -> parseResult(json, jsonLine);
                case "error" -> parseError(json, jsonLine);
                case "rate_limit_event" -> new StreamEvent("rate_limit_event", null, null, -1, -1, false, jsonLine);
                default -> new StreamEvent(type, null, null, -1, -1, false, jsonLine);
            };
        } catch (Exception e) {
            return StreamEvent.error("Failed to parse JSON: " + e.getMessage());
        }
    }

    private StreamEvent parseSystem(JSONObject json, String rawJson) {
        String subtype = json.optString("subtype", "");
        String sessionId = json.optString("session_id", null);
        String model = json.optString("model", "");
        String content = "init".equals(subtype)
                ? "Session initialized" + (model.isEmpty() ? "" : " (model: " + model + ")")
                : json.optString("message", json.optString("content", subtype));
        return new StreamEvent("system", content, sessionId, -1, -1, false, rawJson);
    }

    private StreamEvent parseAssistant(JSONObject json, String rawJson) {
        JSONObject message = json.optJSONObject("message");
        if (message == null) {
            return new StreamEvent("assistant", json.optString("content", ""), null, -1, -1, false, rawJson);
        }
        JSONArray contentArray = message.optJSONArray("content");
        if (contentArray == null || contentArray.length() == 0) {
            return new StreamEvent("assistant", "", null, -1, -1, false, rawJson);
        }

        StringBuilder textBuilder = new StringBuilder();
        boolean hasThinking = false;
        List<String> toolNames = new ArrayList<>();

        for (int i = 0; i < contentArray.length(); i++) {
            JSONObject block = contentArray.optJSONObject(i);
            if (block == null) continue;
            switch (block.optString("type", "")) {
                case "text" -> textBuilder.append(block.optString("text", ""));
                case "thinking" -> hasThinking = true;
                case "tool_use" -> {
                    String toolName = block.optString("name", "");
                    if ("AskUserQuestion".equals(toolName)) return parseEmbeddedAskUser(block, rawJson);
                    toolNames.add(toolName);
                }
            }
        }

        String text = textBuilder.toString();
        if (!text.isEmpty()) return new StreamEvent("assistant", text, null, -1, -1, false, rawJson);
        if (hasThinking) return new StreamEvent("thinking", null, null, -1, -1, false, rawJson);
        if (!toolNames.isEmpty()) return new StreamEvent("tool_activity", String.join(", ", toolNames), null, -1, -1, false, rawJson);
        return new StreamEvent("assistant", "", null, -1, -1, false, rawJson);
    }

    private StreamEvent parseUser(JSONObject json, String rawJson) {
        JSONObject toolResult = json.optJSONObject("tool_use_result");
        String summary = null;
        if (toolResult != null) {
            summary = toolResult.toString();
            if (summary.length() > 200) summary = summary.substring(0, 200) + "...";
        }
        return new StreamEvent("tool_result", summary, null, -1, -1, false, rawJson);
    }

    private StreamEvent parseResult(JSONObject json, String rawJson) {
        String sessionId = json.optString("session_id", null);
        double costUsd = json.optDouble("total_cost_usd", -1);
        long durationMs = json.optLong("duration_ms", -1);
        boolean isError = json.optBoolean("is_error", false);
        if (isError) {
            return new StreamEvent("error", json.optString("result", "Unknown error"),
                    sessionId, costUsd, durationMs, true, rawJson);
        }
        return new StreamEvent("result", null, sessionId, costUsd, durationMs, false, rawJson);
    }

    private StreamEvent parseError(JSONObject json, String rawJson) {
        JSONObject errorObj = json.optJSONObject("error");
        if (errorObj != null) {
            return new StreamEvent("error", errorObj.optString("message", "Unknown error"), null, -1, -1, true, rawJson);
        }
        return new StreamEvent("error", json.optString("error", json.optString("message", "Unknown error")),
                null, -1, -1, true, rawJson);
    }

    private StreamEvent parseEmbeddedAskUser(JSONObject toolUseBlock, String rawJson) {
        String id = toolUseBlock.optString("id", UUID.randomUUID().toString());
        JSONObject input = toolUseBlock.optJSONObject("input");
        if (input == null) return StreamEvent.prompt(id, "Question from LLM", "ask_user", new ArrayList<>(), rawJson);
        return parseAskUserQuestion(id, input, rawJson);
    }

    private StreamEvent parseAskUserQuestion(String id, JSONObject input, String rawJson) {
        JSONArray questionsArray = input.optJSONArray("questions");
        if (questionsArray != null && questionsArray.length() > 0) {
            JSONObject firstQ = questionsArray.optJSONObject(0);
            if (firstQ != null) return parseSingleQuestion(id, firstQ, rawJson);
        }
        return parseSingleQuestion(id, input, rawJson);
    }

    private StreamEvent parseSingleQuestion(String id, JSONObject qObj, String rawJson) {
        String question = qObj.optString("question", qObj.optString("message", "Question from LLM"));
        List<String> options = new ArrayList<>();
        JSONArray optionsArray = qObj.optJSONArray("options");
        if (optionsArray != null) {
            for (int i = 0; i < optionsArray.length(); i++) {
                Object opt = optionsArray.opt(i);
                if (opt instanceof JSONObject optObj) options.add(optObj.optString("label", optObj.toString()));
                else options.add(String.valueOf(opt));
            }
        }
        return StreamEvent.prompt(id, question, "ask_user", options, rawJson);
    }
}
