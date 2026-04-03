package com.scivicslab.coderagent.openaicompat;

import com.scivicslab.coderagent.openaicompat.client.ChatMessage;
import com.scivicslab.coderagent.openaicompat.client.ContextLengthExceededException;
import com.scivicslab.coderagent.openaicompat.client.OpenAiCompatClient;
import com.scivicslab.coderagent.core.provider.LlmProvider;
import com.scivicslab.coderagent.core.provider.ProviderCapabilities;
import com.scivicslab.coderagent.core.provider.ProviderContext;
import com.scivicslab.coderagent.core.rest.ChatEvent;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * LlmProvider implementation for OpenAI-compatible HTTP APIs (vLLM, Ollama, NemoClaw, etc.).
 *
 * <p>Maintains per-request conversation history for context, with automatic trimming
 * on context-length overflow.</p>
 */
public class OpenAiCompatProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(OpenAiCompatProvider.class.getName());
    private static final int MAX_TRIM_RETRIES = 5;

    private final List<OpenAiCompatClient> clients;
    private String currentModel;
    private volatile boolean cancelled;

    // Conversation history for context (not managed by actor — provider owns it)
    private final LinkedList<ChatMessage> history = new LinkedList<>();
    private static final int MAX_HISTORY_MESSAGES = 20;

    public OpenAiCompatProvider(List<String> serverUrls, String defaultModel) {
        this.clients = serverUrls.stream()
                .map(OpenAiCompatClient::new)
                .toList();
        this.currentModel = defaultModel;
    }

    @Override public String id() { return "openai-compat"; }
    @Override public String displayName() { return "OpenAI-compatible LLM"; }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.OPENAI_COMPAT; }
    @Override public String getCurrentModel() { return currentModel; }
    @Override public void setModel(String model) { this.currentModel = model; }
    @Override public String getSessionId() { return null; }

    @Override
    public String detectEnvApiKey() {
        // Most local deployments don't need a key, but check a common var
        return System.getenv("LLM_API_KEY");
    }

    @Override
    public List<ModelEntry> getAvailableModels() {
        List<ModelEntry> models = new ArrayList<>();
        for (OpenAiCompatClient client : clients) {
            String server = extractHost(client.getBaseUrl());
            for (String name : client.fetchModels()) {
                models.add(new ModelEntry(name, "openai-compat", server));
            }
        }
        return models;
    }

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        cancelled = false;
        if (model != null && !model.isBlank()) currentModel = model;

        List<String> imageDataUrls = ctx.imageDataUrls() != null ? ctx.imageDataUrls() : List.of();
        history.addLast(new ChatMessage.User(prompt, imageDataUrls));
        if (history.size() > MAX_HISTORY_MESSAGES) history.removeFirst();

        OpenAiCompatClient client = selectClient(currentModel);
        if (client == null) {
            emitter.accept(ChatEvent.error("No server available for model: " + currentModel));
            history.pollLast();
            return;
        }

        int retries = 0;
        while (retries <= MAX_TRIM_RETRIES) {
            if (cancelled) return;
            List<ChatMessage> snapshot = List.copyOf(history);
            try {
                StringBuilder assistantBuf = new StringBuilder();
                client.sendPrompt(currentModel, snapshot, ctx.noThink(), 0,
                    new OpenAiCompatClient.StreamCallback() {
                        @Override public void onDelta(String content) {
                            assistantBuf.append(content);
                            emitter.accept(ChatEvent.delta(content));
                        }
                        @Override public void onComplete(long durationMs) {
                            String response = assistantBuf.toString();
                            history.addLast(new ChatMessage.Assistant(response));
                            if (history.size() > MAX_HISTORY_MESSAGES) history.removeFirst();
                            emitter.accept(ChatEvent.result(null, 0.0, durationMs, currentModel, false));
                        }
                        @Override public void onError(String message) {
                            emitter.accept(ChatEvent.error(message));
                        }
                    });
                return;
            } catch (ContextLengthExceededException e) {
                retries++;
                if (retries > MAX_TRIM_RETRIES || history.size() <= 2) {
                    emitter.accept(ChatEvent.error("Context too long even after trimming."));
                    return;
                }
                logger.warning("Context length exceeded, trimming history (attempt " + retries + ")");
                emitter.accept(ChatEvent.thinking("Context too long, trimming history..."));
                trimHistory();
            }
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    private void trimHistory() {
        // Remove oldest non-system messages (keep at least the last user message)
        if (history.size() > 2) history.removeFirst();
        if (history.size() > 2) history.removeFirst();
    }

    private OpenAiCompatClient selectClient(String model) {
        // Try to find a client that serves this model (using cached model list)
        for (OpenAiCompatClient client : clients) {
            if (client.servesModel(model)) return client;
        }
        // Fall back to first available client
        return clients.isEmpty() ? null : clients.get(0);
    }

    private static String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return url;
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        } catch (Exception e) {
            return url;
        }
    }
}
