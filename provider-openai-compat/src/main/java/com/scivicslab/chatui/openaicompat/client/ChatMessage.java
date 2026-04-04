package com.scivicslab.chatui.openaicompat.client;

import java.util.List;

/**
 * Sealed interface representing a single message in the conversation history.
 */
public sealed interface ChatMessage {

    /**
     * Returns the role of this message in the conversation (e.g. "user", "assistant").
     *
     * @return the role string
     */
    String role();

    record User(String content, List<String> imageDataUrls) implements ChatMessage {
        /**
         * Creates a text-only user message with no images.
         *
         * @param content the user's text content
         */
        public User(String content) { this(content, List.of()); }
        /** {@inheritDoc} */
        @Override public String role() { return "user"; }

        /**
         * Checks whether this message contains any attached images.
         *
         * @return {@code true} if at least one image data URL is present
         */
        public boolean hasImages() { return imageDataUrls != null && !imageDataUrls.isEmpty(); }
    }

    record Assistant(String content) implements ChatMessage {
        @Override public String role() { return "assistant"; }
    }
}
