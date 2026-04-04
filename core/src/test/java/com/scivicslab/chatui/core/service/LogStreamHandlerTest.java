package com.scivicslab.chatui.core.service;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 tests for {@link LogStreamHandler} and the underlying
 * {@link ChatActor} log ring buffer.
 *
 * <p>Uses a real {@link ActorSystem} with a minimal stub LlmProvider.
 * No Mockito, no {@code @QuarkusTest}.</p>
 */
class LogStreamHandlerTest {

    private ActorSystem actorSystem;
    private ActorRef<ChatActor> chatRef;
    private ChatActor chatActor;
    private LogStreamHandler handler;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem("log-test");
        chatActor = new ChatActor(new NoOpLlmProvider(), Optional.empty());
        chatRef = actorSystem.actorOf("chat", chatActor);

        handler = new LogStreamHandler();
        handler.wireActorRef(chatRef);
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) actorSystem.terminate();
    }

    // ========================================================================
    // LogStreamHandler tests
    // ========================================================================

    @Test
    @DisplayName("publish() forwards log record to actor and stores in ring buffer")
    void publish_forwardsToActorRingBuffer() throws Exception {
        LogRecord record = new LogRecord(Level.INFO, "Hello from test");
        record.setLoggerName("com.example.Foo");

        handler.publish(record);

        // Wait for actor message processing
        List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        assertEquals(1, logs.size());
        ChatEvent event = logs.get(0);
        assertEquals("log", event.type());
        assertEquals("INFO", event.logLevel());
        assertEquals("com.example.Foo", event.loggerName());
        assertEquals("Hello from test", event.content());
        assertNotNull(event.timestamp());
    }

    @Test
    @DisplayName("publish() with null record is ignored")
    void publish_nullRecord_ignored() throws Exception {
        handler.publish(null);

        List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("publish() suppresses records from own logger to prevent recursion")
    void publish_ownLogger_suppressed() throws Exception {
        LogRecord record = new LogRecord(Level.INFO, "Self-referencing log");
        record.setLoggerName(LogStreamHandler.class.getName());

        handler.publish(record);

        List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("publish() does nothing when actor ref is not wired")
    void publish_noActorRef_doesNothing() {
        LogStreamHandler unwired = new LogStreamHandler();

        LogRecord record = new LogRecord(Level.WARNING, "Orphan log");
        record.setLoggerName("com.example.Bar");

        // Should not throw
        assertDoesNotThrow(() -> unwired.publish(record));
    }

    @Test
    @DisplayName("publish() with different log levels preserves each level")
    void publish_differentLevels_preservesLevel() throws Exception {
        Level[] levels = {Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE};

        for (Level level : levels) {
            LogRecord record = new LogRecord(level, "msg-" + level.getName());
            record.setLoggerName("com.example.Levels");
            handler.publish(record);
        }

        List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        assertEquals(levels.length, logs.size());
        for (int i = 0; i < levels.length; i++) {
            assertEquals(levels[i].getName(), logs.get(i).logLevel());
            assertEquals("msg-" + levels[i].getName(), logs.get(i).content());
        }
    }

    @Test
    @DisplayName("publish() formats parameterized messages using MessageFormat")
    void publish_parameterizedMessage_formatted() throws Exception {
        LogRecord record = new LogRecord(Level.INFO, "User {0} logged in from {1}");
        record.setLoggerName("com.example.Auth");
        record.setParameters(new Object[]{"alice", "10.0.0.1"});

        handler.publish(record);

        List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        assertEquals(1, logs.size());
        assertEquals("User alice logged in from 10.0.0.1", logs.get(0).content());
    }

    @Test
    @DisplayName("publish() falls back to raw message when MessageFormat fails")
    void publish_badFormatParams_fallsBackToRawMessage() throws Exception {
        // MessageFormat.format throws on malformed patterns with params
        LogRecord record = new LogRecord(Level.INFO, "{bad-pattern");
        record.setLoggerName("com.example.Format");
        record.setParameters(new Object[]{"value"});

        handler.publish(record);

        List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        assertEquals(1, logs.size());
        assertEquals("{bad-pattern", logs.get(0).content());
    }

    @Test
    @DisplayName("publish() with null message produces empty string")
    void publish_nullMessage_producesEmptyString() throws Exception {
        LogRecord record = new LogRecord(Level.INFO, null);
        record.setLoggerName("com.example.Null");

        handler.publish(record);

        List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        assertEquals(1, logs.size());
        assertEquals("", logs.get(0).content());
    }

    @Test
    @DisplayName("getRecentLogs() returns empty list when actor ref is not wired")
    void getRecentLogs_noActorRef_returnsEmpty() {
        LogStreamHandler unwired = new LogStreamHandler();
        List<ChatEvent> logs = unwired.getRecentLogs();
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("getRecentLogs() returns logs from actor in chronological order")
    void getRecentLogs_returnsChrono() throws Exception {
        for (int i = 0; i < 5; i++) {
            LogRecord record = new LogRecord(Level.INFO, "log-" + i);
            record.setLoggerName("com.example.Order");
            handler.publish(record);
        }

        // Allow actor processing
        List<ChatEvent> logs = handler.getRecentLogs();

        assertEquals(5, logs.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("log-" + i, logs.get(i).content());
        }
    }

    @Test
    @DisplayName("setSseEmitter() delegates to actor")
    void setSseEmitter_delegatesToActor() throws Exception {
        List<ChatEvent> captured = new ArrayList<>();

        handler.setSseEmitter(captured::add);

        // Give the tell message time to process
        Thread.sleep(100);

        // Now publish a log - it should go to the SSE emitter
        LogRecord record = new LogRecord(Level.INFO, "SSE test");
        record.setLoggerName("com.example.Sse");
        handler.publish(record);

        // Wait for actor to process
        chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        assertFalse(captured.isEmpty());
        assertEquals("SSE test", captured.get(0).content());
    }

    @Test
    @DisplayName("clearSseEmitter() stops forwarding to emitter")
    void clearSseEmitter_stopsForwarding() throws Exception {
        List<ChatEvent> captured = new ArrayList<>();

        handler.setSseEmitter(captured::add);
        Thread.sleep(100);

        // Publish one record while emitter is active
        LogRecord record1 = new LogRecord(Level.INFO, "Before clear");
        record1.setLoggerName("com.example.Clear");
        handler.publish(record1);

        // Wait for processing
        chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        int countBeforeClear = captured.size();
        assertTrue(countBeforeClear > 0);

        // Clear emitter
        handler.clearSseEmitter();
        Thread.sleep(100);

        // Publish another record
        LogRecord record2 = new LogRecord(Level.INFO, "After clear");
        record2.setLoggerName("com.example.Clear");
        handler.publish(record2);

        // Wait for processing
        chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

        // Should not have received additional events
        assertEquals(countBeforeClear, captured.size());
    }

    @Test
    @DisplayName("setSseEmitter() with no actor ref does not throw")
    void setSseEmitter_noActorRef_doesNotThrow() {
        LogStreamHandler unwired = new LogStreamHandler();
        assertDoesNotThrow(() -> unwired.setSseEmitter(e -> {}));
    }

    @Test
    @DisplayName("clearSseEmitter() with no actor ref does not throw")
    void clearSseEmitter_noActorRef_doesNotThrow() {
        LogStreamHandler unwired = new LogStreamHandler();
        assertDoesNotThrow(unwired::clearSseEmitter);
    }

    @Test
    @DisplayName("flush() is a no-op and does not throw")
    void flush_noOp() {
        assertDoesNotThrow(() -> handler.flush());
    }

    @Test
    @DisplayName("close() is a no-op and does not throw")
    void close_noOp() {
        assertDoesNotThrow(() -> handler.close());
    }

    // ========================================================================
    // ChatActor ring buffer tests (direct, without LogStreamHandler)
    // ========================================================================

    @Nested
    @DisplayName("ChatActor log ring buffer")
    class RingBufferTest {

        @Test
        @DisplayName("empty buffer returns empty list")
        void emptyBuffer_returnsEmpty() throws Exception {
            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);
            assertTrue(logs.isEmpty());
        }

        @Test
        @DisplayName("fills correctly up to capacity")
        void fillsCorrectly() throws Exception {
            int count = 10;
            for (int i = 0; i < count; i++) {
                final int idx = i;
                chatRef.tell(a -> a.publishLog("INFO", "test", "msg-" + idx, 1000L + idx))
                        .get(5, TimeUnit.SECONDS);
            }

            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

            assertEquals(count, logs.size());
            for (int i = 0; i < count; i++) {
                assertEquals("msg-" + i, logs.get(i).content());
                assertEquals(1000L + i, logs.get(i).timestamp());
            }
        }

        @Test
        @DisplayName("wraps around when exceeding buffer size (500)")
        void wrapsAround() throws Exception {
            int bufferSize = 500;
            int totalLogs = bufferSize + 50;

            for (int i = 0; i < totalLogs; i++) {
                final int idx = i;
                chatRef.tell(a -> a.publishLog("INFO", "test", "msg-" + idx, 1000L + idx))
                        .get(5, TimeUnit.SECONDS);
            }

            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

            // Should contain exactly bufferSize entries
            assertEquals(bufferSize, logs.size());

            // Oldest should be totalLogs - bufferSize = 50
            assertEquals("msg-50", logs.get(0).content());
            // Newest should be totalLogs - 1 = 549
            assertEquals("msg-549", logs.get(bufferSize - 1).content());
        }

        @Test
        @DisplayName("chronological order preserved after wraparound")
        void chronoOrderAfterWrap() throws Exception {
            int bufferSize = 500;
            int totalLogs = bufferSize + 123;

            for (int i = 0; i < totalLogs; i++) {
                final int idx = i;
                chatRef.tell(a -> a.publishLog("INFO", "test", "msg-" + idx, idx))
                        .get(5, TimeUnit.SECONDS);
            }

            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

            // Verify all entries are in ascending timestamp order
            for (int i = 1; i < logs.size(); i++) {
                assertTrue(logs.get(i).timestamp() > logs.get(i - 1).timestamp(),
                        "Logs should be in chronological order at index " + i);
            }
        }

        @Test
        @DisplayName("single entry fills and retrieves correctly")
        void singleEntry() throws Exception {
            chatRef.tell(a -> a.publishLog("WARNING", "com.example", "only-one", 42L))
                    .get(5, TimeUnit.SECONDS);

            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

            assertEquals(1, logs.size());
            ChatEvent e = logs.get(0);
            assertEquals("log", e.type());
            assertEquals("WARNING", e.logLevel());
            assertEquals("com.example", e.loggerName());
            assertEquals("only-one", e.content());
            assertEquals(42L, e.timestamp());
        }

        @Test
        @DisplayName("exactly buffer size entries fills without wrapping")
        void exactBufferSize() throws Exception {
            int bufferSize = 500;

            for (int i = 0; i < bufferSize; i++) {
                final int idx = i;
                chatRef.tell(a -> a.publishLog("INFO", "test", "msg-" + idx, idx))
                        .get(5, TimeUnit.SECONDS);
            }

            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

            assertEquals(bufferSize, logs.size());
            assertEquals("msg-0", logs.get(0).content());
            assertEquals("msg-499", logs.get(bufferSize - 1).content());
        }

        @Test
        @DisplayName("SSE emitter receives log events in real-time")
        void sseEmitter_receivesRealTime() throws Exception {
            List<ChatEvent> captured = new ArrayList<>();

            chatRef.tell(a -> a.setSseEmitter(captured::add))
                    .get(5, TimeUnit.SECONDS);

            chatRef.tell(a -> a.publishLog("INFO", "test", "realtime-1", 100L))
                    .get(5, TimeUnit.SECONDS);
            chatRef.tell(a -> a.publishLog("WARNING", "test", "realtime-2", 200L))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(2, captured.size());
            assertEquals("realtime-1", captured.get(0).content());
            assertEquals("INFO", captured.get(0).logLevel());
            assertEquals("realtime-2", captured.get(1).content());
            assertEquals("WARNING", captured.get(1).logLevel());
        }

        @Test
        @DisplayName("clearSseEmitter stops forwarding to emitter")
        void clearSseEmitter_stopsForwarding() throws Exception {
            List<ChatEvent> captured = new ArrayList<>();

            chatRef.tell(a -> a.setSseEmitter(captured::add))
                    .get(5, TimeUnit.SECONDS);

            chatRef.tell(a -> a.publishLog("INFO", "test", "before-clear", 100L))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(1, captured.size());

            chatRef.tell(ChatActor::clearSseEmitter)
                    .get(5, TimeUnit.SECONDS);

            chatRef.tell(a -> a.publishLog("INFO", "test", "after-clear", 200L))
                    .get(5, TimeUnit.SECONDS);

            // Still only 1 captured event
            assertEquals(1, captured.size());

            // But ring buffer has both
            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);
            assertEquals(2, logs.size());
        }

        @Test
        @DisplayName("SSE emitter exception is silently ignored")
        void sseEmitter_exceptionIgnored() throws Exception {
            Consumer<ChatEvent> failingEmitter = e -> {
                throw new RuntimeException("SSE write failed");
            };

            chatRef.tell(a -> a.setSseEmitter(failingEmitter))
                    .get(5, TimeUnit.SECONDS);

            // Should not throw despite the failing emitter
            chatRef.tell(a -> a.publishLog("INFO", "test", "resilient", 100L))
                    .get(5, TimeUnit.SECONDS);

            // Log should still be in the ring buffer
            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);
            assertEquals(1, logs.size());
            assertEquals("resilient", logs.get(0).content());
        }

        @Test
        @DisplayName("multiple wraparounds produce correct results")
        void multipleWraparounds() throws Exception {
            int bufferSize = 500;
            // Write 3x buffer size worth of logs
            int totalLogs = bufferSize * 3;

            for (int i = 0; i < totalLogs; i++) {
                final int idx = i;
                chatRef.tell(a -> a.publishLog("INFO", "test", "msg-" + idx, idx))
                        .get(5, TimeUnit.SECONDS);
            }

            List<ChatEvent> logs = chatRef.ask(ChatActor::getRecentLogs).get(5, TimeUnit.SECONDS);

            assertEquals(bufferSize, logs.size());
            // Should contain the last 500 entries: 1000..1499
            int expectedFirst = totalLogs - bufferSize;
            assertEquals("msg-" + expectedFirst, logs.get(0).content());
            assertEquals("msg-" + (totalLogs - 1), logs.get(bufferSize - 1).content());
        }
    }

    // ========================================================================
    // Stub LlmProvider
    // ========================================================================

    /**
     * Minimal no-op LlmProvider required by ChatActor's constructor.
     */
    static class NoOpLlmProvider implements LlmProvider {
        @Override public String id() { return "noop"; }
        @Override public String displayName() { return "NoOp"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "noop-model"; }
        @Override public void setModel(String model) {}
        @Override public void sendPrompt(String prompt, String model,
                                         Consumer<ChatEvent> emitter, ProviderContext ctx) {}
        @Override public void cancel() {}
    }
}
