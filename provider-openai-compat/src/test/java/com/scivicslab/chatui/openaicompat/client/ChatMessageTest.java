package com.scivicslab.chatui.openaicompat.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageTest {

    @Test
    @DisplayName("User single-arg constructor has no images")
    void user_noImages_hasImagesIsFalse() {
        ChatMessage.User u = new ChatMessage.User("Hello");
        assertEquals("Hello", u.content());
        assertEquals("user", u.role());
        assertFalse(u.hasImages());
        assertTrue(u.imageDataUrls().isEmpty());
    }

    @Test
    @DisplayName("User with images has hasImages=true")
    void user_withImages_hasImagesIsTrue() {
        List<String> imgs = List.of("data:image/png;base64,abc");
        ChatMessage.User u = new ChatMessage.User("See image", imgs);
        assertTrue(u.hasImages());
        assertEquals(1, u.imageDataUrls().size());
    }

    @Test
    @DisplayName("Assistant role() returns assistant")
    void assistant_roleIsAssistant() {
        ChatMessage.Assistant a = new ChatMessage.Assistant("response");
        assertEquals("assistant", a.role());
        assertEquals("response", a.content());
    }

    @Test
    @DisplayName("User and Assistant are sealed ChatMessage implementations")
    void sealedInterface_patternMatch() {
        ChatMessage msg = new ChatMessage.User("hi");
        String role = switch (msg) {
            case ChatMessage.User u -> u.role();
            case ChatMessage.Assistant a -> a.role();
        };
        assertEquals("user", role);
    }

    @Test
    @DisplayName("User equality based on content and imageDataUrls")
    void user_equalityByContent() {
        ChatMessage.User u1 = new ChatMessage.User("same");
        ChatMessage.User u2 = new ChatMessage.User("same");
        assertEquals(u1, u2);
    }

    @Test
    @DisplayName("Assistant equality based on content")
    void assistant_equalityByContent() {
        ChatMessage.Assistant a1 = new ChatMessage.Assistant("answer");
        ChatMessage.Assistant a2 = new ChatMessage.Assistant("answer");
        assertEquals(a1, a2);
    }
}
