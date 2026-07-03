package com.scivicslab.chatui.translate;

import com.scivicslab.chatui.core.plugin.PromptPreprocessor;
import com.scivicslab.chatui.core.rest.ChatEvent;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Prompt preprocessor that translates non-English input to natural English,
 * and naturalizes English input, using an OpenAI-compatible vLLM endpoint.
 *
 * <p>On success, emits a {@code translation} SSE event so the UI displays the
 * English version with the green "EN:" badge before the LLM reply arrives.</p>
 *
 * <p>Configuration properties:</p>
 * <ul>
 *   <li>{@code chat-ui.translate.vllm-url}  — vLLM chat completions URL</li>
 *   <li>{@code chat-ui.translate.model}     — model name served by vLLM</li>
 *   <li>{@code chat-ui.translate.timeout-sec} — HTTP timeout in seconds (default 30)</li>
 * </ul>
 */
@ApplicationScoped
public class TranslatePreprocessor implements PromptPreprocessor {

    private static final Logger log = Logger.getLogger(TranslatePreprocessor.class.getName());

    private static final String SYSTEM_PROMPT =
            "You are a translation assistant. Translate the user's input into English for an English "
          + "learner to study.\n" +
            "Rules:\n" +
            "1. ALWAYS output in English. NEVER output in Chinese, Japanese, or any other language.\n" +
            "2. If the input is in Japanese (or any non-English language), translate it into natural, " +
            "fluent English that stays FAITHFUL to the original nuance, tone, politeness level, and " +
            "emphasis. Convey what the original actually implies — do NOT paraphrase away subtle " +
            "connotations, hedging, or intensity, and do NOT flatten it into a generic reworded version. " +
            "Prefer the phrasing a native speaker would use to express the SAME intent, register, and " +
            "degree of directness/politeness as the original.\n" +
            "3. If the input is already in English, output it unchanged.\n" +
            "4. Output ONLY the English text. No explanations, no labels, no prefixes, no extra lines.\n" +
            "5. Your response MUST be in English regardless of your default language.";

    @ConfigProperty(name = "chat-ui.translate.vllm-url",
                    defaultValue = "http://192.0.2.10:8000/v1/chat/completions")
    String vllmUrl;

    @ConfigProperty(name = "chat-ui.translate.model",
                    defaultValue = "Qwen2.5-14B-Instruct-AWQ")
    String model;

    @ConfigProperty(name = "chat-ui.translate.timeout-sec", defaultValue = "30")
    int timeoutSec;

    @ConfigProperty(name = "chat-ui.translate.max-length", defaultValue = "500")
    int maxLength;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Override
    public String process(String prompt, Consumer<ChatEvent> emitter) {
        if (prompt == null || prompt.isBlank()) return prompt;
        if (prompt.length() > maxLength) {
            log.fine("Prompt length " + prompt.length() + " exceeds max-length " + maxLength + ", skipping translation.");
            return prompt;
        }
        try {
            String translated = callVllm(prompt);
            // Emit the English translation as a UI badge if the text actually changed.
            // Always send the original prompt to the LLM so the user's intent is preserved.
            if (!translated.isBlank() && !translated.strip().equals(prompt.strip())) {
                emitter.accept(ChatEvent.translation(translated));
            }
            return prompt;
        } catch (Exception e) {
            log.warning("Translation failed, passing through original prompt. Reason: " + e.getMessage());
            return prompt;
        }
    }

    private String callVllm(String userText) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_tokens", 1024)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(new JSONObject().put("role", "user").put("content", userText)));

        String bodyStr = body.toString();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("vLLM returned HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JSONObject json = new JSONObject(resp.body());
        return json.getJSONArray("choices")
                   .getJSONObject(0)
                   .getJSONObject("message")
                   .getString("content")
                   .strip();
    }
}
