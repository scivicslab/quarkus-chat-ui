package com.scivicslab.coderagent.core.actor;

import com.scivicslab.coderagent.core.provider.LlmProvider;
import com.scivicslab.coderagent.core.provider.ProviderContext;
import com.scivicslab.coderagent.core.rest.ChatEvent;
import com.scivicslab.coderagent.core.service.AuthMode;
import com.scivicslab.pojoactor.core.ActorRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actor owning the entire chat session state.
 *
 * <p>All fields are plain (no volatile / synchronized) — thread safety is guaranteed
 * by the actor's sequential message processing. The one exception is {@code activeThread},
 * which is marked volatile so that {@code cancel()} can interrupt it from any thread.</p>
 *
 * <p>Heavy work (LLM I/O) runs on a spawned virtual thread so the actor remains idle
 * and responsive during a prompt (e.g., for {@code cancel()} or {@code publishLog()}).</p>
 *
 * <p>Provider-specific logic lives entirely in the {@link LlmProvider} implementation.
 * This actor only orchestrates the prompt lifecycle and manages shared state.</p>
 */
public class ChatActor {

    private static final Logger logger = Logger.getLogger(ChatActor.class.getName());
    private static final int MAX_HISTORY = 200;
    private static final int LOG_BUFFER_SIZE = 500;

    private final LlmProvider provider;
    private final AuthMode authMode;

    private boolean busy;
    private String apiKey;
    private final LinkedList<HistoryEntry> conversationHistory = new LinkedList<>();

    private volatile Thread activeThread;

    private ActorRef<WatchdogActor> watchdog;

    private final ChatEvent[] logBuffer = new ChatEvent[LOG_BUFFER_SIZE];
    private int logHead = 0;
    private int logCount = 0;
    private Consumer<ChatEvent> sseEmitter;

    public ChatActor(LlmProvider provider, Optional<String> configApiKey) {
        this.provider = provider;

        if (provider.capabilities().supportsWatchdog()) {
            // CLI-based provider: no API key needed, CLI binary handles auth
            this.authMode = AuthMode.CLI;
            this.apiKey = null;
            logger.info("Provider: " + provider.displayName() + " (CLI mode)");
        } else {
            // HTTP-based provider: needs API key
            String envKey = provider.detectEnvApiKey();
            if (envKey != null && !envKey.isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = envKey;
                logger.info("Provider: " + provider.displayName() + " (API key from environment)");
            } else if (configApiKey.isPresent() && !configApiKey.get().isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = configApiKey.get();
                logger.info("Provider: " + provider.displayName() + " (API key from config)");
            } else {
                this.authMode = AuthMode.NONE;
                this.apiKey = null;
                logger.info("Provider: " + provider.displayName() + " (no API key — must be set via Web UI)");
            }
        }
    }

    // ---- Authentication ----

    public AuthMode getAuthMode() { return authMode; }

    public boolean isAuthenticated() {
        return authMode == AuthMode.CLI
                || (authMode == AuthMode.API_KEY && apiKey != null)
                || (authMode == AuthMode.NONE && apiKey != null);
    }

    public void setApiKey(String key) {
        this.apiKey = key;
        logger.info("API key set via Web UI");
    }

    public String getApiKey() { return apiKey; }

    // ---- Provider delegation ----

    public boolean isBusy() { return busy; }

    public String getModel() { return provider.getCurrentModel(); }

    public String getSessionId() { return provider.getSessionId(); }

    public boolean isCommand(String input) { return provider.isCommand(input); }

    public List<LlmProvider.ModelEntry> getAvailableModels() { return provider.getAvailableModels(); }

    public List<ChatEvent> handleCommand(String input) {
        List<ChatEvent> responses = new ArrayList<>(provider.handleCommand(input));
        if (input.trim().toLowerCase().startsWith("/clear")) {
            conversationHistory.clear();
        }
        responses.add(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), busy));
        return responses;
    }

    // ---- Chat lifecycle ----

    /**
     * Begins an asynchronous prompt. Spawns a virtual thread for blocking LLM I/O,
     * keeping the actor idle for other messages (cancel, log, etc.).
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatActor> self, CompletableFuture<Void> done) {
        if (busy) {
            emitter.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            done.complete(null);
            return;
        }
        if (!isAuthenticated()) {
            emitter.accept(ChatEvent.error(
                    "No authentication configured. Please provide an API key."));
            done.complete(null);
            return;
        }

        busy = true;
        boolean useWatchdog = watchdog != null && provider.capabilities().supportsWatchdog();
        if (useWatchdog) watchdog.tell(WatchdogActor::onPromptStarted);

        final String snapApiKey = apiKey;
        if (model != null && !model.isBlank()) provider.setModel(model);

        activeThread = Thread.startVirtualThread(() -> {
            try {
                Runnable heartbeat = useWatchdog
                        ? () -> watchdog.tell(WatchdogActor::onActivity)
                        : () -> {};

                ProviderContext ctx = new ProviderContext(snapApiKey, List.of(), false, heartbeat);

                self.tell(a -> a.recordHistory("user", prompt));

                // Wrap emitter to intercept assistant content for history
                StringBuilder assistantBuf = new StringBuilder();
                Consumer<ChatEvent> wrappedEmitter = event -> {
                    if ("delta".equals(event.type()) && event.content() != null) {
                        assistantBuf.append(event.content());
                    } else if ("result".equals(event.type()) && !assistantBuf.isEmpty()) {
                        String content = assistantBuf.toString();
                        self.tell(a -> a.recordHistory("assistant", content));
                    }
                    emitter.accept(event);
                };

                emitter.accept(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), true));
                provider.sendPrompt(prompt, provider.getCurrentModel(), wrappedEmitter, ctx);

            } catch (Exception e) {
                logger.log(Level.WARNING, "Provider sendPrompt failed", e);
                emitter.accept(ChatEvent.error("Error: " + e.getMessage()));
            } finally {
                self.tell(a -> a.onPromptComplete(emitter, done));
            }
        });
    }

    /** Called by the worker virtual thread when LLM processing finishes. */
    public void onPromptComplete(Consumer<ChatEvent> emitter, CompletableFuture<Void> done) {
        busy = false;
        activeThread = null;
        boolean useWatchdog = watchdog != null && provider.capabilities().supportsWatchdog();
        if (useWatchdog) watchdog.tell(WatchdogActor::onPromptFinished);
        emitter.accept(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), false));
        done.complete(null);
    }

    public void cancel() {
        provider.cancel();
        Thread t = activeThread;
        if (t != null) t.interrupt();
    }

    public void respond(String promptId, String response) throws IOException {
        provider.respond(promptId, response);
    }

    // ---- History ----

    public void recordHistory(String role, String content) {
        if (content == null || content.isBlank()) return;
        conversationHistory.addLast(new HistoryEntry(role, content));
        while (conversationHistory.size() > MAX_HISTORY) conversationHistory.removeFirst();
    }

    public List<HistoryEntry> getHistory(int limit) {
        int size = conversationHistory.size();
        int from = Math.max(0, size - limit);
        return Collections.unmodifiableList(new ArrayList<>(conversationHistory.subList(from, size)));
    }

    public void clearHistory() { conversationHistory.clear(); }

    // ---- Log ring buffer ----

    public void publishLog(String level, String loggerName, String message, long timestamp) {
        ChatEvent event = ChatEvent.log(level, loggerName, message, timestamp);
        logBuffer[logHead] = event;
        logHead = (logHead + 1) % LOG_BUFFER_SIZE;
        if (logCount < LOG_BUFFER_SIZE) logCount++;
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception ignored) {}
        }
    }

    public void setSseEmitter(Consumer<ChatEvent> emitter) { this.sseEmitter = emitter; }
    public void clearSseEmitter() { this.sseEmitter = null; }

    public List<ChatEvent> getRecentLogs() {
        List<ChatEvent> result = new ArrayList<>(logCount);
        int start = (logHead - logCount + LOG_BUFFER_SIZE) % LOG_BUFFER_SIZE;
        for (int i = 0; i < logCount; i++) result.add(logBuffer[(start + i) % LOG_BUFFER_SIZE]);
        return result;
    }

    /** Wire the watchdog actor reference. Called by LlmConsoleActorSystem after construction. */
    public void setWatchdog(ActorRef<WatchdogActor> watchdog) { this.watchdog = watchdog; }

    public record HistoryEntry(String role, String content) {}
}
