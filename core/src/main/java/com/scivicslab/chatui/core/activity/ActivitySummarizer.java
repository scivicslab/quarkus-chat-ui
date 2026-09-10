package com.scivicslab.chatui.core.activity;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
     * The model asked first, when the broker serves it.
     *
     * <p>Named rather than "the first one the broker lists", because the first one it lists may be
     * a reasoning model: asked for one sentence within a 200-token limit it spends the whole limit
     * thinking and returns an empty answer. This one replies with the sentence.</p>
     */
    private static final String PREFERRED_MODEL = "google/gemma-4-26B-A4B-it";

    /**
     * How many of the broker's models are asked before giving up for this round.
     *
     * <p>More than one because a model that answers nothing is indistinguishable, from here, from
     * one that is not suited to the question; the next one on the list is asked instead of
     * reporting failure. Bounded because each attempt costs a call.</p>
     */
    private static final int MODELS_TRIED = 3;

    /** Enough for one sentence, and short enough that a model that starts explaining is cut off. */
    private static final int MAX_TOKENS = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // uvicorn/FastAPI upstreams behind the broker reject h2c requests with 422.
            .version(HttpClient.Version.HTTP_1_1)
            .build();

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
        List<String> candidates = candidates(base);
        if (candidates.isEmpty()) return null;

        String prompt = """
                Say in one English sentence what is being done in this conversation right now.

                Write it as a piece of work being done to a named thing, for example
                "Reworking the UI design of quarkus-AI-workspace" or
                "Refactoring the parallel execution in Turing-workflow".

                Constraints:
                - Start with the work itself: fixing, refactoring, designing, measuring, writing,
                  deploying, investigating, and so on.
                - Name the program, project or document the work is being done to, using the name
                  the conversation calls it by, for example quarkus-AI-workspace or doc_SCIVICS002.
                - Do not name a field of work or an industry. "software development", "the AI
                  workspace domain", "knowledge management" and the like tell a reader nothing that
                  separates this conversation from any other, and must not appear.
                - Take the work from the most recent exchanges. A subject the conversation has
                  already finished with is not what is being done now.
                - One sentence, at most 15 words. No preamble, no quotation marks.
                - Do not write hostnames, IP addresses, file paths, credentials, or commands.
                - Do not copy the conversation text verbatim.

                Conversation:
                """ + material;

        for (String model : candidates) {
            String body = "{\"model\":" + jsonString(model)
                    + ",\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(prompt) + "}]"
                    + ",\"max_tokens\":" + MAX_TOKENS + "}";
            String reply = post(base + "/v1/chat/completions", body);
            if (reply == null) continue;
            String content = firstChoiceContent(reply);
            if (content != null && !content.isBlank()) return content.strip();
            LOG.fine("Model " + model + " answered nothing; trying the next one");
        }
        return null;
    }

    /** The broker's address, without a trailing slash, or {@code ""} when none is configured. */
    private static String brokerUrl() {
        String url = System.getProperty("gpu.broker.url");
        if (url == null || url.isBlank()) url = System.getenv("GPU_BROKER_URL");
        return url == null || url.isBlank() ? "" : url.replaceAll("/+$", "");
    }

    /**
     * The models to ask, in the order they are asked, resolved afresh on every call.
     *
     * <p>{@link #PREFERRED_MODEL} first when the broker serves it, then the rest as the broker
     * lists them, at most {@link #MODELS_TRIED}.</p>
     *
     * <p>Deliberately not cached. A broker's model set changes while this process runs — nodes are
     * restarted, the broker rediscovers them — and a name resolved once and kept became a name
     * that could never be corrected. On 2026-09-11 two instances held {@code Qwen/Qwen3.8-27B},
     * resolved while the preferred model was absent, and answered nothing for as long as they ran
     * even after the preferred model came back. The list costs one local GET per summary, and a
     * summary is worked out at most once a minute.</p>
     *
     * @param base the broker's address, without a trailing slash
     * @return the model ids to try, in order; empty when the broker listed none
     */
    private List<String> candidates(String base) {
        String listed = post(base + "/v1/models", null);
        if (listed == null) return List.of();
        List<String> ids = modelIds(listed);
        List<String> ordered = new ArrayList<>();
        if (ids.contains(PREFERRED_MODEL)) ordered.add(PREFERRED_MODEL);
        for (String id : ids) {
            if (ordered.size() >= MODELS_TRIED) break;
            if (!ordered.contains(id)) ordered.add(id);
        }
        return List.copyOf(ordered);
    }

    /**
     * @param modelsJson a {@code /v1/models} answer
     * @return every model id in it, in the order listed; empty when there are none or it is
     *         unreadable
     */
    static List<String> modelIds(String modelsJson) {
        try {
            org.json.JSONArray data = new org.json.JSONObject(modelsJson).optJSONArray("data");
            if (data == null) return List.of();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                String id = data.getJSONObject(i).optString("id", "");
                if (!id.isBlank()) out.add(id);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
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
        List<String> ids = modelIds(modelsJson);
        return ids.isEmpty() ? null : ids.get(0);
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
