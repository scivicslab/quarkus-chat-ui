package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming translation's blocking call runs as one {@code BlockingJob}, off the subscriber's thread:
 * the pieces reach the stream in the order they were produced, the stream ends after the last one, and a
 * failure becomes one error element instead of a stream that never ends.
 */
class TranslateStreamJobTest {

    private static ChatResource resourceWith(ChatUiActorSystem system) {
        ChatResource r = new ChatResource();
        r.actorSystem = system;
        return r;
    }

    @Test
    void piecesComeInOrderThenDoneAndTheBlockingCallRunsOffTheSubscribersThread() {
        AtomicReference<Thread> calledOn = new AtomicReference<>();
        ChatUiActorSystem system = new ChatUiActorSystem() {
            @Override
            public Translation translate(String projectId, String chatId, String text, String languageCode,
                                         Consumer<String> onDelta) {
                calledOn.set(Thread.currentThread());
                onDelta.accept("A");
                onDelta.accept("B");
                return new Translation(Translation.Kind.DONE, "AB", "m", null);
            }
        };
        List<String> events = resourceWith(system)
                .translateStream("project1", "chat-01", java.util.Map.of("text", "x", "language", "ja"))
                .collect().asList().await().atMost(Duration.ofSeconds(10));
        assertEquals(3, events.size(), events.toString());
        assertTrue(events.get(0).contains("\"delta\"") && events.get(0).contains("\"A\""), events.get(0));
        assertTrue(events.get(1).contains("\"delta\"") && events.get(1).contains("\"B\""), events.get(1));
        assertTrue(events.get(2).contains("\"done\"") && events.get(2).contains("AB"), events.get(2));
        assertTrue(calledOn.get().isVirtual());
        assertNotSame(Thread.currentThread(), calledOn.get());
    }

    @Test
    void aFailureIsOneErrorElementAndTheStreamStillEnds() {
        ChatUiActorSystem system = new ChatUiActorSystem() {
            @Override
            public Translation translate(String projectId, String chatId, String text, String languageCode,
                                         Consumer<String> onDelta) {
                throw new IllegalStateException("model down");
            }
        };
        List<String> events = resourceWith(system)
                .translateStream("project1", "chat-01", java.util.Map.of("text", "x", "language", "ja"))
                .collect().asList().await().atMost(Duration.ofSeconds(10));
        assertEquals(1, events.size(), events.toString());
        assertTrue(events.get(0).contains("\"error\"") && events.get(0).contains("model down"), events.get(0));
    }
}
