package com.scivicslab.chatui.core.plugin;

import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Optional CDI extension point that transforms a user prompt before it is sent to the LLM.
 *
 * <p>If an implementation is present on the classpath, {@code ChatResource.chat()} calls
 * {@link #process} with the raw prompt and the SSE emitter. The returned string replaces
 * the original prompt in the LLM call. Any SSE events emitted inside {@code process} are
 * streamed to the client immediately (e.g., to display a translation bubble).</p>
 *
 * <p>Implementations should be {@code @ApplicationScoped} CDI beans. If no implementation
 * is found, the original prompt is passed through unchanged.</p>
 */
public interface PromptPreprocessor {

    /**
     * Processes the user prompt and returns the (possibly transformed) text to send to the LLM.
     *
     * @param prompt  the raw prompt text submitted by the user
     * @param emitter SSE emitter for sending events to the chat UI during processing
     * @return the prompt text to forward to the LLM (may equal {@code prompt} if unchanged)
     */
    String process(String prompt, Consumer<ChatEvent> emitter);
}
