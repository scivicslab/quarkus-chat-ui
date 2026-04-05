package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.service.AuthMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link ChatActor}.
 * Uses stub implementations of {@link LlmProvider} instead of Mockito.
 */
class ChatActorTest {

    // ---- Stub LlmProvider implementations ----

    /**
     * Minimal stub provider for HTTP-based (non-CLI) scenarios.
     * Returns no env API key by default and uses DEFAULT capabilities.
     */
    static class StubHttpProvider implements LlmProvider {
        private String currentModel = "test-model";
        private String envApiKey = null;
        private final List<String> commandsReceived = new ArrayList<>();
        private final List<ChatEvent> commandResponse = new ArrayList<>();

        @Override
        public String id() { return "stub-http"; }

        @Override
        public String displayName() { return "Stub HTTP Provider"; }

        @Override
        public List<ModelEntry> getAvailableModels() {
            return List.of(new ModelEntry("test-model", "chat", "local"));
        }

        @Override
        public String getCurrentModel() { return currentModel; }

        @Override
        public void setModel(String model) { this.currentModel = model; }

        @Override
        public void sendPrompt(String prompt, String model,
                               Consumer<ChatEvent> emitter, ProviderContext ctx) {
            // no-op for most tests
        }

        @Override
        public void cancel() { }

        @Override
        public ProviderCapabilities capabilities() { return ProviderCapabilities.DEFAULT; }

        @Override
        public String detectEnvApiKey() { return envApiKey; }

        void setEnvApiKey(String key) { this.envApiKey = key; }

        @Override
        public boolean isCommand(String input) {
            return input != null && input.startsWith("/");
        }

        @Override
        public List<ChatEvent> handleCommand(String input) {
            commandsReceived.add(input);
            return new ArrayList<>(commandResponse);
        }

        void setCommandResponse(List<ChatEvent> events) {
            commandResponse.clear();
            commandResponse.addAll(events);
        }

        List<String> getCommandsReceived() { return commandsReceived; }
    }

    /**
     * Stub provider that reports CLI capabilities (supportsWatchdog = true).
     */
    static class StubCliProvider extends StubHttpProvider {
        @Override
        public String id() { return "stub-cli"; }

        @Override
        public String displayName() { return "Stub CLI Provider"; }

        @Override
        public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }
    }

    // ---- Constructor / AuthMode tests ----

    @Nested
    @DisplayName("Constructor and AuthMode determination")
    class ConstructorTests {

        @Test
        @DisplayName("CLI provider sets AuthMode.CLI regardless of API key")
        void cliProvider_setsCliMode() {
            StubCliProvider provider = new StubCliProvider();
            ChatActor actor = new ChatActor(provider, Optional.of("some-key"));

            assertEquals(AuthMode.CLI, actor.getAuthMode());
            assertNull(actor.getApiKey());
        }

        @Test
        @DisplayName("HTTP provider with env API key sets AuthMode.API_KEY")
        void httpProvider_withEnvKey_setsApiKeyMode() {
            StubHttpProvider provider = new StubHttpProvider();
            provider.setEnvApiKey("env-secret-key");
            ChatActor actor = new ChatActor(provider, Optional.empty());

            assertEquals(AuthMode.API_KEY, actor.getAuthMode());
            assertEquals("env-secret-key", actor.getApiKey());
        }

        @Test
        @DisplayName("HTTP provider with config API key sets AuthMode.API_KEY")
        void httpProvider_withConfigKey_setsApiKeyMode() {
            StubHttpProvider provider = new StubHttpProvider();
            ChatActor actor = new ChatActor(provider, Optional.of("config-secret-key"));

            assertEquals(AuthMode.API_KEY, actor.getAuthMode());
            assertEquals("config-secret-key", actor.getApiKey());
        }

        @Test
        @DisplayName("HTTP provider env key takes priority over config key")
        void httpProvider_envKeyPriority() {
            StubHttpProvider provider = new StubHttpProvider();
            provider.setEnvApiKey("env-key");
            ChatActor actor = new ChatActor(provider, Optional.of("config-key"));

            assertEquals(AuthMode.API_KEY, actor.getAuthMode());
            assertEquals("env-key", actor.getApiKey());
        }

        @Test
        @DisplayName("HTTP provider with no API key sets AuthMode.NONE")
        void httpProvider_noKey_setsNoneMode() {
            StubHttpProvider provider = new StubHttpProvider();
            ChatActor actor = new ChatActor(provider, Optional.empty());

            assertEquals(AuthMode.NONE, actor.getAuthMode());
            assertNull(actor.getApiKey());
        }

        @Test
        @DisplayName("HTTP provider with blank env key and no config key sets AuthMode.NONE")
        void httpProvider_blankEnvKey_setsNoneMode() {
            StubHttpProvider provider = new StubHttpProvider();
            provider.setEnvApiKey("   ");
            ChatActor actor = new ChatActor(provider, Optional.empty());

            assertEquals(AuthMode.NONE, actor.getAuthMode());
            assertNull(actor.getApiKey());
        }

        @Test
        @DisplayName("HTTP provider with blank config key and no env key sets AuthMode.NONE")
        void httpProvider_blankConfigKey_setsNoneMode() {
            StubHttpProvider provider = new StubHttpProvider();
            ChatActor actor = new ChatActor(provider, Optional.of("   "));

            assertEquals(AuthMode.NONE, actor.getAuthMode());
            assertNull(actor.getApiKey());
        }
    }

    // ---- Authentication tests ----

    @Nested
    @DisplayName("isAuthenticated()")
    class AuthenticationTests {

        @Test
        @DisplayName("CLI mode is always authenticated")
        void cliMode_alwaysAuthenticated() {
            ChatActor actor = new ChatActor(new StubCliProvider(), Optional.empty());
            assertTrue(actor.isAuthenticated());
        }

        @Test
        @DisplayName("API_KEY mode with key is authenticated")
        void apiKeyMode_withKey_authenticated() {
            StubHttpProvider provider = new StubHttpProvider();
            provider.setEnvApiKey("key");
            ChatActor actor = new ChatActor(provider, Optional.empty());

            assertEquals(AuthMode.API_KEY, actor.getAuthMode());
            assertTrue(actor.isAuthenticated());
        }

        @Test
        @DisplayName("NONE mode without key is not authenticated")
        void noneMode_noKey_notAuthenticated() {
            ChatActor actor = new ChatActor(new StubHttpProvider(), Optional.empty());

            assertEquals(AuthMode.NONE, actor.getAuthMode());
            assertFalse(actor.isAuthenticated());
        }

        @Test
        @DisplayName("NONE mode becomes authenticated after setApiKey()")
        void noneMode_afterSetApiKey_authenticated() {
            ChatActor actor = new ChatActor(new StubHttpProvider(), Optional.empty());
            assertFalse(actor.isAuthenticated());

            actor.setApiKey("user-provided-key");

            assertTrue(actor.isAuthenticated());
            assertEquals("user-provided-key", actor.getApiKey());
        }
    }

    // ---- setApiKey tests ----

    @Nested
    @DisplayName("setApiKey()")
    class SetApiKeyTests {

        @Test
        @DisplayName("setApiKey stores the key and enables authentication")
        void setApiKey_storesAndEnables() {
            ChatActor actor = new ChatActor(new StubHttpProvider(), Optional.empty());
            assertNull(actor.getApiKey());
            assertFalse(actor.isAuthenticated());

            actor.setApiKey("new-key");

            assertEquals("new-key", actor.getApiKey());
            assertTrue(actor.isAuthenticated());
        }

        @Test
        @DisplayName("setApiKey overwrites previous key")
        void setApiKey_overwritesPrevious() {
            StubHttpProvider provider = new StubHttpProvider();
            provider.setEnvApiKey("original");
            ChatActor actor = new ChatActor(provider, Optional.empty());
            assertEquals("original", actor.getApiKey());

            actor.setApiKey("replacement");
            assertEquals("replacement", actor.getApiKey());
        }
    }

    // ---- isBusy tests ----

    @Nested
    @DisplayName("isBusy()")
    class BusyTests {

        @Test
        @DisplayName("initially not busy")
        void initiallyNotBusy() {
            ChatActor actor = new ChatActor(new StubHttpProvider(), Optional.empty());
            assertFalse(actor.isBusy());
        }

        @Test
        @DisplayName("busy becomes false after onPromptComplete")
        void onPromptComplete_clearsbusy() {
            ChatActor actor = new ChatActor(new StubCliProvider(), Optional.empty());
            List<ChatEvent> emitted = new ArrayList<>();

            // Simulate the completion callback
            actor.onPromptComplete(emitted::add, new java.util.concurrent.CompletableFuture<>(), null);

            assertFalse(actor.isBusy());
            // Should emit a status event
            assertEquals(1, emitted.size());
            assertEquals("status", emitted.get(0).type());
            assertEquals(false, emitted.get(0).busy());
        }
    }

    // ---- handleCommand tests ----

    @Nested
    @DisplayName("handleCommand()")
    class HandleCommandTests {

        private StubHttpProvider provider;
        private ChatActor actor;

        @BeforeEach
        void setUp() {
            provider = new StubHttpProvider();
            provider.setEnvApiKey("key");
            provider.setCommandResponse(List.of(ChatEvent.info("Command executed")));
            actor = new ChatActor(provider, Optional.empty());
        }

        @Test
        @DisplayName("delegates to provider and appends status event")
        void delegatesToProvider_appendsStatus() {
            List<ChatEvent> result = actor.handleCommand("/model gpt-4");

            assertEquals(1, provider.getCommandsReceived().size());
            assertEquals("/model gpt-4", provider.getCommandsReceived().get(0));

            // Provider response + status event appended
            assertEquals(2, result.size());
            assertEquals("info", result.get(0).type());
            assertEquals("status", result.get(1).type());
        }

        @Test
        @DisplayName("/clear command clears conversation history")
        void clearCommand_clearsHistory() {
            actor.recordHistory("user", "Hello");
            actor.recordHistory("assistant", "Hi there");
            assertEquals(2, actor.getHistory(100).size());

            actor.handleCommand("/clear");

            assertEquals(0, actor.getHistory(100).size());
        }

        @Test
        @DisplayName("/clear with mixed case still clears history")
        void clearCommand_caseInsensitive() {
            actor.recordHistory("user", "Hello");
            assertEquals(1, actor.getHistory(100).size());

            actor.handleCommand("/Clear");

            assertEquals(0, actor.getHistory(100).size());
        }

        @Test
        @DisplayName("/clear with extra args still clears history")
        void clearCommand_withArgs_clearsHistory() {
            actor.recordHistory("user", "Hello");
            actor.handleCommand("/clear all");
            assertEquals(0, actor.getHistory(100).size());
        }

        @Test
        @DisplayName("non-clear command does not affect history")
        void nonClearCommand_preservesHistory() {
            actor.recordHistory("user", "Hello");
            actor.handleCommand("/model gpt-4");
            assertEquals(1, actor.getHistory(100).size());
        }
    }

    // ---- History tests ----

    @Nested
    @DisplayName("recordHistory() / getHistory() / clearHistory()")
    class HistoryTests {

        private ChatActor actor;

        @BeforeEach
        void setUp() {
            actor = new ChatActor(new StubHttpProvider(), Optional.empty());
        }

        @Test
        @DisplayName("recordHistory adds entries")
        void recordHistory_addsEntries() {
            actor.recordHistory("user", "Hello");
            actor.recordHistory("assistant", "Hi");

            List<ChatActor.HistoryEntry> history = actor.getHistory(10);
            assertEquals(2, history.size());
            assertEquals("user", history.get(0).role());
            assertEquals("Hello", history.get(0).content());
            assertEquals("assistant", history.get(1).role());
            assertEquals("Hi", history.get(1).content());
        }

        @Test
        @DisplayName("recordHistory ignores null content")
        void recordHistory_ignoresNull() {
            actor.recordHistory("user", null);
            assertEquals(0, actor.getHistory(10).size());
        }

        @Test
        @DisplayName("recordHistory ignores blank content")
        void recordHistory_ignoresBlank() {
            actor.recordHistory("user", "   ");
            assertEquals(0, actor.getHistory(10).size());
        }

        @Test
        @DisplayName("recordHistory ignores empty string content")
        void recordHistory_ignoresEmpty() {
            actor.recordHistory("user", "");
            assertEquals(0, actor.getHistory(10).size());
        }

        @Test
        @DisplayName("recordHistory trims at MAX_HISTORY (200)")
        void recordHistory_trimsAtMaxHistory() {
            for (int i = 0; i < 210; i++) {
                actor.recordHistory("user", "Message " + i);
            }

            List<ChatActor.HistoryEntry> history = actor.getHistory(300);
            assertEquals(200, history.size());
            // Oldest entries (0-9) should have been evicted
            assertEquals("Message 10", history.get(0).content());
            assertEquals("Message 209", history.get(199).content());
        }

        @Test
        @DisplayName("getHistory returns only the last N entries when limit is smaller")
        void getHistory_respectsLimit() {
            for (int i = 0; i < 10; i++) {
                actor.recordHistory("user", "Msg " + i);
            }

            List<ChatActor.HistoryEntry> history = actor.getHistory(3);
            assertEquals(3, history.size());
            assertEquals("Msg 7", history.get(0).content());
            assertEquals("Msg 8", history.get(1).content());
            assertEquals("Msg 9", history.get(2).content());
        }

        @Test
        @DisplayName("getHistory returns all entries when limit exceeds size")
        void getHistory_limitExceedsSize() {
            actor.recordHistory("user", "Only one");
            List<ChatActor.HistoryEntry> history = actor.getHistory(100);
            assertEquals(1, history.size());
        }

        @Test
        @DisplayName("getHistory returns empty list when no history")
        void getHistory_empty() {
            List<ChatActor.HistoryEntry> history = actor.getHistory(10);
            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("getHistory returns unmodifiable list")
        void getHistory_unmodifiable() {
            actor.recordHistory("user", "Hello");
            List<ChatActor.HistoryEntry> history = actor.getHistory(10);
            assertThrows(UnsupportedOperationException.class, () -> history.add(
                    new ChatActor.HistoryEntry("user", "Hack")));
        }

        @Test
        @DisplayName("clearHistory removes all entries")
        void clearHistory_removesAll() {
            actor.recordHistory("user", "Hello");
            actor.recordHistory("assistant", "Hi");
            assertEquals(2, actor.getHistory(10).size());

            actor.clearHistory();

            assertEquals(0, actor.getHistory(10).size());
        }
    }

    // ---- Log ring buffer tests ----

    @Nested
    @DisplayName("publishLog() / getRecentLogs()")
    class LogRingBufferTests {

        private ChatActor actor;

        @BeforeEach
        void setUp() {
            actor = new ChatActor(new StubHttpProvider(), Optional.empty());
        }

        @Test
        @DisplayName("initially no logs")
        void initiallyEmpty() {
            List<ChatEvent> logs = actor.getRecentLogs();
            assertTrue(logs.isEmpty());
        }

        @Test
        @DisplayName("publishLog stores events retrievable by getRecentLogs")
        void publishLog_storesEvents() {
            actor.publishLog("INFO", "test.Logger", "Hello log", 1000L);

            List<ChatEvent> logs = actor.getRecentLogs();
            assertEquals(1, logs.size());
            assertEquals("log", logs.get(0).type());
            assertEquals("Hello log", logs.get(0).content());
            assertEquals("INFO", logs.get(0).logLevel());
            assertEquals("test.Logger", logs.get(0).loggerName());
            assertEquals(1000L, logs.get(0).timestamp());
        }

        @Test
        @DisplayName("publishLog maintains chronological order")
        void publishLog_chronologicalOrder() {
            actor.publishLog("INFO", "a", "First", 100L);
            actor.publishLog("WARN", "b", "Second", 200L);
            actor.publishLog("ERROR", "c", "Third", 300L);

            List<ChatEvent> logs = actor.getRecentLogs();
            assertEquals(3, logs.size());
            assertEquals("First", logs.get(0).content());
            assertEquals("Second", logs.get(1).content());
            assertEquals("Third", logs.get(2).content());
        }

        @Test
        @DisplayName("publishLog wraps around at LOG_BUFFER_SIZE (500)")
        void publishLog_wrapsAround() {
            // Fill buffer to capacity
            for (int i = 0; i < 500; i++) {
                actor.publishLog("INFO", "lg", "Log " + i, i);
            }
            List<ChatEvent> logs = actor.getRecentLogs();
            assertEquals(500, logs.size());
            assertEquals("Log 0", logs.get(0).content());
            assertEquals("Log 499", logs.get(499).content());

            // Add one more to trigger wrap-around
            actor.publishLog("INFO", "lg", "Log 500", 500L);
            logs = actor.getRecentLogs();
            assertEquals(500, logs.size());
            // Oldest (Log 0) should be evicted
            assertEquals("Log 1", logs.get(0).content());
            assertEquals("Log 500", logs.get(499).content());
        }

        @Test
        @DisplayName("publishLog wraps around multiple times correctly")
        void publishLog_multipleWraps() {
            // Write 1200 entries (wraps the 500-entry buffer more than twice)
            for (int i = 0; i < 1200; i++) {
                actor.publishLog("INFO", "lg", "Entry " + i, i);
            }
            List<ChatEvent> logs = actor.getRecentLogs();
            assertEquals(500, logs.size());
            // Should contain the last 500 entries: 700..1199
            assertEquals("Entry 700", logs.get(0).content());
            assertEquals("Entry 1199", logs.get(499).content());
        }

        @Test
        @DisplayName("publishLog forwards to SSE emitter when set")
        void publishLog_forwardsToSseEmitter() {
            List<ChatEvent> emitted = new ArrayList<>();
            actor.setSseEmitter(emitted::add);

            actor.publishLog("INFO", "lg", "Forwarded", 42L);

            assertEquals(1, emitted.size());
            assertEquals("Forwarded", emitted.get(0).content());
        }

        @Test
        @DisplayName("publishLog does not forward when no SSE emitter")
        void publishLog_noEmitter_noForward() {
            // No exception thrown, event stored in buffer only
            actor.publishLog("INFO", "lg", "Buffered only", 42L);

            List<ChatEvent> logs = actor.getRecentLogs();
            assertEquals(1, logs.size());
        }

        @Test
        @DisplayName("publishLog handles SSE emitter exception gracefully")
        void publishLog_emitterException_graceful() {
            actor.setSseEmitter(event -> { throw new RuntimeException("SSE failure"); });

            // Should not throw
            assertDoesNotThrow(() -> actor.publishLog("ERROR", "lg", "Still stored", 1L));

            List<ChatEvent> logs = actor.getRecentLogs();
            assertEquals(1, logs.size());
            assertEquals("Still stored", logs.get(0).content());
        }
    }

    // ---- SSE emitter tests ----

    @Nested
    @DisplayName("setSseEmitter() / clearSseEmitter()")
    class SseEmitterTests {

        private ChatActor actor;

        @BeforeEach
        void setUp() {
            actor = new ChatActor(new StubHttpProvider(), Optional.empty());
        }

        @Test
        @DisplayName("setSseEmitter enables forwarding")
        void setSseEmitter_enablesForwarding() {
            List<ChatEvent> emitted = new ArrayList<>();
            actor.setSseEmitter(emitted::add);

            actor.publishLog("INFO", "lg", "Test", 1L);

            assertEquals(1, emitted.size());
        }

        @Test
        @DisplayName("clearSseEmitter stops forwarding")
        void clearSseEmitter_stopsForwarding() {
            List<ChatEvent> emitted = new ArrayList<>();
            actor.setSseEmitter(emitted::add);
            actor.publishLog("INFO", "lg", "Before clear", 1L);
            assertEquals(1, emitted.size());

            actor.clearSseEmitter();
            actor.publishLog("INFO", "lg", "After clear", 2L);

            // No new event forwarded
            assertEquals(1, emitted.size());
            // But the log is still in the buffer
            assertEquals(2, actor.getRecentLogs().size());
        }

        @Test
        @DisplayName("replacing SSE emitter forwards to new one only")
        void replaceSseEmitter_forwardsToNew() {
            List<ChatEvent> first = new ArrayList<>();
            List<ChatEvent> second = new ArrayList<>();

            actor.setSseEmitter(first::add);
            actor.publishLog("INFO", "lg", "To first", 1L);

            actor.setSseEmitter(second::add);
            actor.publishLog("INFO", "lg", "To second", 2L);

            assertEquals(1, first.size());
            assertEquals(1, second.size());
            assertEquals("To first", first.get(0).content());
            assertEquals("To second", second.get(0).content());
        }
    }

    // ---- Provider delegation tests ----

    @Nested
    @DisplayName("Provider delegation methods")
    class ProviderDelegationTests {

        @Test
        @DisplayName("getModel delegates to provider")
        void getModel_delegatesToProvider() {
            StubHttpProvider provider = new StubHttpProvider();
            ChatActor actor = new ChatActor(provider, Optional.empty());
            assertEquals("test-model", actor.getModel());
        }

        @Test
        @DisplayName("getSessionId delegates to provider")
        void getSessionId_delegatesToProvider() {
            ChatActor actor = new ChatActor(new StubHttpProvider(), Optional.empty());
            assertNull(actor.getSessionId());
        }

        @Test
        @DisplayName("isCommand delegates to provider")
        void isCommand_delegatesToProvider() {
            ChatActor actor = new ChatActor(new StubHttpProvider(), Optional.empty());
            assertTrue(actor.isCommand("/clear"));
            assertFalse(actor.isCommand("hello"));
        }

        @Test
        @DisplayName("getAvailableModels delegates to provider")
        void getAvailableModels_delegatesToProvider() {
            ChatActor actor = new ChatActor(new StubHttpProvider(), Optional.empty());
            List<LlmProvider.ModelEntry> models = actor.getAvailableModels();
            assertEquals(1, models.size());
            assertEquals("test-model", models.get(0).name());
        }
    }

    // ---- HistoryEntry record tests ----

    @Nested
    @DisplayName("HistoryEntry record")
    class HistoryEntryTests {

        @Test
        @DisplayName("HistoryEntry stores role and content")
        void historyEntry_storesValues() {
            ChatActor.HistoryEntry entry = new ChatActor.HistoryEntry("user", "Hello");
            assertEquals("user", entry.role());
            assertEquals("Hello", entry.content());
        }

        @Test
        @DisplayName("HistoryEntry equals and hashCode work correctly")
        void historyEntry_equalsAndHashCode() {
            ChatActor.HistoryEntry a = new ChatActor.HistoryEntry("user", "Hello");
            ChatActor.HistoryEntry b = new ChatActor.HistoryEntry("user", "Hello");
            ChatActor.HistoryEntry c = new ChatActor.HistoryEntry("assistant", "Hello");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }
    }
}
