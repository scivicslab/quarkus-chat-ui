package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatProviderTest {

    private static final String DEFAULT_MODEL = "Qwen3.5-35B-A3B";
    private OpenAiCompatProvider provider;

    @BeforeEach
    void setUp() {
        // Use a dummy URL; we never actually connect in these unit tests
        provider = new OpenAiCompatProvider(
                List.of("http://localhost:8000"), DEFAULT_MODEL);
    }

    // ---- id() ----

    @Test
    @DisplayName("id() returns 'openai-compat'")
    void id_returnsOpenaiCompat() {
        assertEquals("openai-compat", provider.id());
    }

    // ---- displayName() ----

    @Test
    @DisplayName("displayName() returns 'OpenAI-compatible LLM'")
    void displayName_returnsExpectedString() {
        assertEquals("OpenAI-compatible LLM", provider.displayName());
    }

    // ---- capabilities() ----

    @Test
    @DisplayName("capabilities() returns OPENAI_COMPAT preset")
    void capabilities_returnsOpenaiCompatPreset() {
        ProviderCapabilities caps = provider.capabilities();
        assertSame(ProviderCapabilities.OPENAI_COMPAT, caps);
    }

    @Test
    @DisplayName("capabilities() has supportsImages=true")
    void capabilities_supportsImages() {
        assertTrue(provider.capabilities().supportsImages());
    }

    @Test
    @DisplayName("capabilities() has supportsUrlFetch=true")
    void capabilities_supportsUrlFetch() {
        assertTrue(provider.capabilities().supportsUrlFetch());
    }

    @Test
    @DisplayName("capabilities() has supportsInteractivePrompts=false")
    void capabilities_noInteractivePrompts() {
        assertFalse(provider.capabilities().supportsInteractivePrompts());
    }

    @Test
    @DisplayName("capabilities() has supportsSessionRestore=false")
    void capabilities_noSessionRestore() {
        assertFalse(provider.capabilities().supportsSessionRestore());
    }

    @Test
    @DisplayName("capabilities() has supportsWatchdog=false")
    void capabilities_noWatchdog() {
        assertFalse(provider.capabilities().supportsWatchdog());
    }

    @Test
    @DisplayName("capabilities() has supportsSlashCommands=false")
    void capabilities_noSlashCommands() {
        assertFalse(provider.capabilities().supportsSlashCommands());
    }

    // ---- getCurrentModel() / setModel() ----

    @Test
    @DisplayName("getCurrentModel() returns the default model after construction")
    void getCurrentModel_returnsDefault() {
        assertEquals(DEFAULT_MODEL, provider.getCurrentModel());
    }

    @Test
    @DisplayName("setModel() changes the current model")
    void setModel_updatesCurrentModel() {
        provider.setModel("llama3-70b");
        assertEquals("llama3-70b", provider.getCurrentModel());
    }

    @Test
    @DisplayName("setModel() can be called multiple times")
    void setModel_multipleChanges() {
        provider.setModel("model-a");
        assertEquals("model-a", provider.getCurrentModel());
        provider.setModel("model-b");
        assertEquals("model-b", provider.getCurrentModel());
    }

    // ---- getSessionId() ----

    @Test
    @DisplayName("getSessionId() returns null (stateless provider)")
    void getSessionId_returnsNull() {
        assertNull(provider.getSessionId());
    }

    // ---- detectEnvApiKey() ----

    @Test
    @DisplayName("detectEnvApiKey() returns null when LLM_API_KEY is not set")
    void detectEnvApiKey_returnsNullWhenNotSet() {
        // In most CI/test environments the var is not set
        String key = provider.detectEnvApiKey();
        String envVal = System.getenv("LLM_API_KEY");
        if (envVal == null) {
            assertNull(key);
        } else {
            // If the env var happens to be set, verify it matches
            assertEquals(envVal, key);
        }
    }

    // ---- cancel() ----

    @Nested
    @DisplayName("cancel()")
    class CancelTests {

        @Test
        @DisplayName("cancel() does not throw")
        void cancel_doesNotThrow() {
            assertDoesNotThrow(() -> provider.cancel());
        }

        @Test
        @DisplayName("cancel() can be called multiple times without error")
        void cancel_idempotent() {
            provider.cancel();
            provider.cancel();
            // No exception means success
        }

        @Test
        @DisplayName("cancel() interrupts the thread registered as sendingThread")
        void cancel_interruptsSendingThread() throws Exception {
            AtomicBoolean interrupted = new AtomicBoolean(false);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            Thread fakeSender = new Thread(() -> {
                started.countDown();
                try {
                    Thread.sleep(10_000); // wait until interrupted
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } finally {
                    done.countDown();
                }
            });
            fakeSender.setDaemon(true);
            fakeSender.start();
            started.await();

            // Inject the thread into the provider as if sendPrompt() was running
            Field f = OpenAiCompatProvider.class.getDeclaredField("sendingThread");
            f.setAccessible(true);
            f.set(provider, fakeSender);

            provider.cancel();
            done.await();

            assertTrue(interrupted.get(), "cancel() must interrupt the sending thread");
        }

        @Test
        @DisplayName("cancel() with no active request does not throw")
        void cancel_withNoSendingThread_doesNotThrow() {
            assertDoesNotThrow(() -> provider.cancel());
        }
    }

    // ---- Constructor edge cases ----

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor with empty server list creates provider without error")
        void emptyServerList_createsProvider() {
            OpenAiCompatProvider p = new OpenAiCompatProvider(List.of(), "model-x");
            assertEquals("model-x", p.getCurrentModel());
            assertEquals("openai-compat", p.id());
        }

        @Test
        @DisplayName("Constructor with multiple server URLs creates provider")
        void multipleServers_createsProvider() {
            OpenAiCompatProvider p = new OpenAiCompatProvider(
                    List.of("http://host1:8000", "http://host2:8000"), "model-y");
            assertEquals("model-y", p.getCurrentModel());
        }

        @Test
        @DisplayName("Constructor stores blank default model as-is")
        void blankDefaultModel_storedAsIs() {
            OpenAiCompatProvider p = new OpenAiCompatProvider(List.of("http://localhost:8000"), "");
            assertEquals("", p.getCurrentModel());
        }
    }
}
