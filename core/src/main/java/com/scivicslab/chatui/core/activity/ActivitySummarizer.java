package com.scivicslab.chatui.core.activity;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks the GPU broker for one line about a conversation ({@code ActivitySummary_260905_oo01}).
 *
 * <p>The broker, not the conversation's own provider: a conversation here is usually driven by the
 * Claude CLI, and asking it to describe itself would spend an interactive quota on a line drawn in
 * someone else's dashboard. The broker serves a local model that costs nothing to ask
 * ({@code ServiceDirectory_260905_oo01}).</p>
 *
 * <p>Answers {@code null} whenever it cannot say anything — no broker configured, no model, an
 * error, an empty reply. The caller distinguishes that from "there is no conversation".</p>
 */
@ApplicationScoped
public class ActivitySummarizer {

    private static final Logger LOG = Logger.getLogger(ActivitySummarizer.class.getName());

    /**
     * How long the broker gets to answer.
     *
     * <p>Nobody is waiting on this call: the answer is worked out once and then stands for half an
     * hour. The limit is here so that a broker that has stopped answering does not hold a request
     * thread indefinitely.</p>
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * The model asked, when the broker serves it.
     *
     * <p>Named rather than "the first one the broker lists", because the first one it lists is a
     * reasoning model: asked for one sentence within a 200-token limit it spends the whole limit
     * thinking and returns an empty answer. This one replies with the sentence.</p>
     */
    private static final String PREFERRED_MODEL = "google/gemma-4-26B-A4B-it";

    /** Enough for one sentence, and short enough that a model that starts explaining is cut off. */
    private static final int MAX_TOKENS = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // uvicorn/FastAPI upstreams behind the broker reject h2c requests with 422.
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private volatile String resolvedModel;

    /**
     * Whether there is a broker to ask.
     *
     * @return {@code true} when a broker address is configured
     */
    public boolean isAvailable() {
        return !brokerUrl().isEmpty();
    }

    /**
     * Asks for one line describing the given material.
     *
     * @param material the conversation, as {@code Q:} / {@code A:} pairs
     * @return the line, or {@code null} when the broker could not be asked or said nothing
     */
    public String summarise(String material) {
        String base = brokerUrl();
        if (base.isEmpty()) return null;
        String model = model(base);
        if (model == null) return null;

        String prompt = """
                次の会話が何についてのものかを、日本語1文で述べてください。

                制約:
                - 何の作業をしているかを述べる。話題の分野ではなく、その会話で進めている作業。
                - 1文。40字以内。前置きも引用符も付けない。
                - 計算機名・IPアドレス・ファイルパス・資格情報・コマンドは書かない。
                - 会話の本文をそのまま写さない。

                会話:
                """ + material;

        String body = "{\"model\":" + jsonString(model)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(prompt) + "}]"
                + ",\"max_tokens\":" + MAX_TOKENS + "}";
        String reply = post(base + "/v1/chat/completions", body);
        if (reply == null) return null;
        String content = firstChoiceContent(reply);
        return content == null || content.isBlank() ? null : content.strip();
    }

    /** The broker's address, without a trailing slash, or {@code ""} when none is configured. */
    private static String brokerUrl() {
        String url = System.getProperty("gpu.broker.url");
        if (url == null || url.isBlank()) url = System.getenv("GPU_BROKER_URL");
        return url == null || url.isBlank() ? "" : url.replaceAll("/+$", "");
    }

    /**
     * The model to ask, resolved once.
     *
     * <p>{@link #PREFERRED_MODEL} when the broker serves it, otherwise the first model it lists —
     * a broker whose model set changed still gets asked something rather than nothing.</p>
     */
    private String model(String base) {
        String known = resolvedModel;
        if (known != null) return known;
        String listed = post(base + "/v1/models", null);
        if (listed == null) return null;
        String chosen = listed.contains("\"" + PREFERRED_MODEL + "\"") ? PREFERRED_MODEL
                                                                      : firstModelId(listed);
        resolvedModel = chosen;
        return chosen;
    }

    /** Sends one request, GET when {@code body} is {@code null}. Answers {@code null} on anything but 200. */
    private String post(String url, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
            if (body == null) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json")
                 .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                LOG.fine("Broker answered " + r.statusCode() + " for " + url);
                return null;
            }
            return r.body();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not reach the broker at " + url, e);
            return null;
        }
    }

    /** @return the {@code id} of the first model in a {@code /v1/models} answer, or {@code null} */
    static String firstModelId(String modelsJson) {
        try {
            org.json.JSONArray data = new org.json.JSONObject(modelsJson).optJSONArray("data");
            if (data == null || data.isEmpty()) return null;
            String id = data.getJSONObject(0).optString("id", "");
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return the assistant text of the first choice, or {@code null} */
    static String firstChoiceContent(String completionJson) {
        try {
            org.json.JSONArray choices = new org.json.JSONObject(completionJson).optJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            org.json.JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            return message == null ? null : message.optString("content", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** @return the given text as a JSON string literal */
    private static String jsonString(String s) {
        return org.json.JSONObject.quote(s);
    }
}
