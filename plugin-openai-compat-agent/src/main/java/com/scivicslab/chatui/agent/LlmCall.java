package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.openaicompat.client.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * One request to the language model. The agent loop sees the model only through this, so a test
 * answers from a script and the application answers over HTTP ({@link OpenAiLlmCall}).
 */
public interface LlmCall {

    /**
     * @param model    the model name
     * @param messages the whole request, system message first
     * @param noThink  whether to ask the server to skip its thinking phase
     * @param onDelta  receives each streamed text chunk as it arrives
     * @return the complete reply text
     * @throws IllegalStateException when the request failed or was cancelled
     */
    String complete(String model, List<ChatMessage> messages, boolean noThink, Consumer<String> onDelta);
}
