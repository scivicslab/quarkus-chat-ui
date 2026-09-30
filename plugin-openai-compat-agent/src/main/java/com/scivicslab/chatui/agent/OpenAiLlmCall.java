package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.OpenAiCompatClient;

import java.util.List;
import java.util.function.Consumer;

/** {@link LlmCall} over the openai-compat provider's HTTP clients: one streaming chat completion. */
public class OpenAiLlmCall implements LlmCall {

    private final List<OpenAiCompatClient> clients;

    public OpenAiLlmCall(List<OpenAiCompatClient> clients) {
        this.clients = clients == null ? List.of() : clients;
    }

    @Override
    public String complete(String model, List<ChatMessage> messages, boolean noThink, Consumer<String> onDelta) {
        OpenAiCompatClient client = select(model);
        if (client == null) throw new IllegalStateException("No server available for model: " + model);
        StringBuilder text = new StringBuilder();
        String[] error = new String[1];
        client.sendPrompt(model, messages, noThink, 0, new OpenAiCompatClient.StreamCallback() {
            @Override public void onDelta(String content) {
                text.append(content);
                onDelta.accept(content);
            }
            @Override public void onComplete(long durationMs) { }
            @Override public void onError(String message) { error[0] = message; }
        });
        if (error[0] != null) throw new IllegalStateException(error[0]);
        return text.toString();
    }

    private OpenAiCompatClient select(String model) {
        for (OpenAiCompatClient c : clients) {
            if (c.servesModel(model)) return c;
        }
        return clients.isEmpty() ? null : clients.get(0);
    }
}
