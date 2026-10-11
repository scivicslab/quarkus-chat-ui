package com.scivicslab.chatui.translate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.chatui.core.iolog.IoLogStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Translates one message of the conversation into a chosen language
 * ({@code QuarkusChatUiMessageTranslation_261011_oo01}).
 *
 * <p>One request to a Local LLM through the GPU broker, whatever provider the conversation runs
 * on: an instance started on Claude Code or Codex is given no Local LLM endpoint of its own. The
 * translation is not added to the conversation; what was sent and what came back are recorded in
 * the I/O log under the label {@code translation}.</p>
 *
 * <p>Its own settings rather than {@code chat-ui.translate.*}: pointing those at a working server
 * would also wake {@link TranslatePreprocessor}, which asks for an English version of every prompt
 * before it is sent.</p>
 */
@ApplicationScoped
public class MessageTranslator {

    private static final Logger LOG = Logger.getLogger(MessageTranslator.class.getName());

    /** The cluster's one GPU broker, by its NodePort. */
    @ConfigProperty(name = "chat-ui.message-translation.url",
                    defaultValue = "http://192.168.5.21:30805/v1/chat/completions")
    String url;

    @ConfigProperty(name = "chat-ui.message-translation.model", defaultValue = "google/gemma-4-26B-A4B-it")
    String model;

    @ConfigProperty(name = "chat-ui.message-translation.timeout-sec", defaultValue = "300")
    int timeoutSec;

    @Inject
    Instance<IoLogStore> ioLog;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** The translation and the model that wrote it, or why there is none. */
    public record Result(String translation, String model, String error) {
        static Result failed(String error) { return new Result(null, null, error); }
    }

    /**
     * @param text         the message, unchanged
     * @param languageCode a code from {@link TranslationLanguages#ALL}
     */
    public Result translate(String text, String languageCode) {
        var language = TranslationLanguages.find(languageCode);
        if (language.isEmpty()) return Result.failed("unknown language: " + languageCode);
        String prompt = TranslationPrompt.of(text, language.get().name());
        String translation = null;
        String error = null;
        try {
            translation = ask(prompt);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Message translation failed", e);
            error = e.getMessage();
        }
        record(language.get().code(), prompt, translation);
        if (translation == null || translation.isBlank()) {
            return Result.failed(error != null ? error : "the model gave no translation");
        }
        return new Result(translation, model, null);
    }

    /** The request body: one user message, no thinking, no streaming. */
    static String requestBody(String model, String prompt) {
        return new JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false))
                .toString();
    }

    private String ask(String prompt) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, prompt), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("translation server returned HTTP " + resp.statusCode());
        }
        return new JSONObject(resp.body()).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "").strip();
    }

    private void record(String languageCode, String prompt, String translation) {
        if (ioLog == null || !ioLog.isResolvable()) return;
        IoLogStore store = ioLog.get();
        store.record(store.ensureSession(), "translator", "translation",
                "LANGUAGE: " + languageCode + "\nMODEL: " + model
                        + "\n\nINPUT:\n" + prompt + "\n\nOUTPUT:\n" + (translation == null ? "(no answer)" : translation));
    }
}
