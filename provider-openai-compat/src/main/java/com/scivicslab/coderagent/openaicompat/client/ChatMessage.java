package com.scivicslab.coderagent.openaicompat.client;

import java.util.List;

/**
 * Sealed interface representing a single message in the conversation history.
 */
public sealed interface ChatMessage {

    String role();

    record User(String content, List<String> imageDataUrls) implements ChatMessage {
        public User(String content) { this(content, List.of()); }
        @Override public String role() { return "user"; }
        public boolean hasImages() { return imageDataUrls != null && !imageDataUrls.isEmpty(); }
    }

    record Assistant(String content) implements ChatMessage {
        @Override public String role() { return "assistant"; }
    }
}
