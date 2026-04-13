package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.service.AuthMode;
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
    private ActorRef<QueueActor> queueActor;

    private final ChatEvent[] logBuffer = new ChatEvent[LOG_BUFFER_SIZE];
    private int logHead = 0;
    private int logCount = 0;
    private Consumer<ChatEvent> sseEmitter;

    /**
     * Creates a new ChatActor bound to the given LLM provider.
     *
     * <p>Determines the authentication mode by checking (in order):
     * CLI capability, environment variable, and config property.</p>
     *
     * @param provider     the LLM provider implementation to delegate prompts to
     * @param configApiKey optional API key supplied via application configuration
     */
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

    /**
     * Returns the authentication mode determined at construction time.
     *
     * @return the current authentication mode (CLI, API_KEY, or NONE)
     */
    public AuthMode getAuthMode() { return authMode; }

    /**
     * Checks whether this actor has sufficient credentials to send prompts.
     *
     * <p>Returns {@code true} when the provider uses CLI auth, or when an API key
     * has been supplied via environment, config, or the Web UI.</p>
     *
     * @return {@code true} if the actor is ready to authenticate with the provider
     */
    public boolean isAuthenticated() {
        return authMode == AuthMode.CLI
                || authMode == AuthMode.NONE
                || (authMode == AuthMode.API_KEY && apiKey != null);
    }

    /**
     * Sets the API key, typically called when a user provides one through the Web UI.
     *
     * @param key the API key to store
     */
    public void setApiKey(String key) {
        this.apiKey = key;
        logger.info("API key set via Web UI");
    }

    /**
     * Returns the currently stored API key, or {@code null} if none is set.
     *
     * @return the API key, or {@code null}
     */
    public String getApiKey() { return apiKey; }

    // ---- Provider delegation ----

    /**
     * Returns whether a prompt is currently being processed.
     *
     * @return {@code true} if the actor is busy with an LLM request
     */
    public boolean isBusy() { return busy; }

    /**
     * Returns the model identifier currently selected by the provider.
     *
     * @return the active model name
     */
    public String getModel() { return provider.getCurrentModel(); }

    /**
     * Returns the current provider session identifier, or {@code null} if no session is active.
     *
     * @return the session ID
     */
    public String getSessionId() { return provider.getSessionId(); }

    /**
     * Tests whether the given input string is a provider command (e.g. slash command).
     *
     * @param input the user input to check
     * @return {@code true} if the provider recognises this as a command
     */
    public boolean isCommand(String input) { return provider.isCommand(input); }

    /**
     * Returns the list of models available from the current provider.
     *
     * @return an unmodifiable list of model entries
     */
    public List<LlmProvider.ModelEntry> getAvailableModels() { return provider.getAvailableModels(); }

    /**
     * Delegates a slash command to the provider and returns the resulting events.
     *
     * <p>If the command is {@code /clear}, the conversation history is also cleared.
     * A status event is always appended to the response list.</p>
     *
     * @param input the raw command string entered by the user
     * @return a list of {@link ChatEvent}s produced by the command
     */
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
                self.tell(a -> a.onPromptComplete(emitter, done, self));
            }
        });
    }

    /** Called by the worker virtual thread when LLM processing finishes. */
    public void onPromptComplete(Consumer<ChatEvent> emitter, CompletableFuture<Void> done, ActorRef<ChatActor> self) {
        busy = false;
        activeThread = null;
        boolean useWatchdog = watchdog != null && provider.capabilities().supportsWatchdog();
        if (useWatchdog) watchdog.tell(WatchdogActor::onPromptFinished);

        // Notify QueueActor to process next prompt
        if (queueActor != null) {
            queueActor.tell(q -> q.onPromptComplete(self));
        }

        emitter.accept(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), false));
        done.complete(null);
    }

    /**
     * Cancels the currently running prompt, if any.
     *
     * <p>Signals the provider to abort and interrupts the worker virtual thread
     * so that blocking I/O is terminated promptly.</p>
     */
    public void cancel() {
        provider.cancel();
        Thread t = activeThread;
        if (t != null) t.interrupt();
    }

    /**
     * Sends a user response to an interactive prompt identified by {@code promptId}.
     *
     * @param promptId the identifier of the prompt awaiting a response
     * @param response the user's response text
     * @throws IOException if communicating with the provider fails
     */
    public void respond(String promptId, String response) throws IOException {
        provider.respond(promptId, response);
    }

    // ---- History ----

    /**
     * Appends an entry to the conversation history, evicting the oldest entry
     * when the maximum history size is exceeded.
     *
     * <p>Blank or null content is silently ignored.</p>
     *
     * @param role    the message role (e.g. "user" or "assistant")
     * @param content the message text
     */
    public void recordHistory(String role, String content) {
        if (content == null || content.isBlank()) return;
        conversationHistory.addLast(new HistoryEntry(role, content));
        while (conversationHistory.size() > MAX_HISTORY) conversationHistory.removeFirst();
    }

    /**
     * Returns the most recent conversation history entries, up to the given limit.
     *
     * @param limit the maximum number of entries to return
     * @return an unmodifiable list of the most recent history entries
     */
    public List<HistoryEntry> getHistory(int limit) {
        int size = conversationHistory.size();
        int from = Math.max(0, size - limit);
        return Collections.unmodifiableList(new ArrayList<>(conversationHistory.subList(from, size)));
    }

    /** Removes all entries from the conversation history. */
    public void clearHistory() { conversationHistory.clear(); }

    // ---- Log ring buffer ----

    /**
     * Stores a log event in the ring buffer and forwards it to the SSE emitter if connected.
     *
     * @param level      the log level (e.g. "INFO", "WARNING")
     * @param loggerName the name of the originating logger
     * @param message    the log message text
     * @param timestamp  the event timestamp in epoch milliseconds
     */
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

    /**
     * Registers an SSE emitter that receives real-time log events.
     *
     * @param emitter the consumer to receive log events
     */
    public void setSseEmitter(Consumer<ChatEvent> emitter) { this.sseEmitter = emitter; }
    /** Unregisters the current SSE emitter, stopping real-time log forwarding. */
    public void clearSseEmitter() { this.sseEmitter = null; }

    /**
     * Buffers a {@link ChatEvent} in the ring buffer and forwards it to the SSE emitter.
     *
     * <p>Used by the autonomous event monitor to emit events that arrive outside of a
     * user-prompted turn (e.g. ScheduleWakeup responses). Unlike {@link #publishLog}, this
     * method accepts a pre-built {@code ChatEvent} and does not wrap it in a log envelope.</p>
     *
     * @param event the event to buffer and emit
     */
    public void emitEvent(ChatEvent event) {
        logBuffer[logHead] = event;
        logHead = (logHead + 1) % LOG_BUFFER_SIZE;
        if (logCount < LOG_BUFFER_SIZE) logCount++;
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Returns the contents of the log ring buffer in chronological order.
     *
     * @return a list of the most recent log events (up to {@code LOG_BUFFER_SIZE})
     */
    public List<ChatEvent> getRecentLogs() {
        List<ChatEvent> result = new ArrayList<>(logCount);
        int start = (logHead - logCount + LOG_BUFFER_SIZE) % LOG_BUFFER_SIZE;
        for (int i = 0; i < logCount; i++) result.add(logBuffer[(start + i) % LOG_BUFFER_SIZE]);
        return result;
    }

    /** Wire the watchdog actor reference. Called by ChatUiActorSystem after construction. */
    public void setWatchdog(ActorRef<WatchdogActor> watchdog) { this.watchdog = watchdog; }

    public void setQueueActor(ActorRef<QueueActor> queueActor) { this.queueActor = queueActor; }

    /**
     * Starts the autonomous event monitor.
     *
     * <p>A persistent virtual thread polls the provider for events that arrive outside
     * of a user-prompted turn (e.g. ScheduleWakeup responses). When an event is
     * detected while idle, the actor transitions to {@code busy=true} and drains the
     * remainder of that autonomous turn, forwarding all events to the SSE stream via
     * {@link #publishLog}.</p>
     *
     * <p>Only started when {@code provider.capabilities().supportsAutonomousEvents()} is true.
     * Called once by {@link ChatUiActorSystem} during initialisation.</p>
     */
    public void startAutonomousMonitor(ActorRef<ChatActor> self) {
        if (!provider.capabilities().supportsAutonomousEvents()) return;
        Thread.startVirtualThread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (busy) {
                        Thread.sleep(100);
                        continue;
                    }
                    Optional<ChatEvent> event = provider.pollAutonomousEvent(200);
                    if (event.isEmpty()) continue;
                    // Deliver the first event to the actor thread; it will drain the rest.
                    ChatEvent first = event.get();
                    self.tell(a -> a.onAutonomousEvent(first, self));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /**
     * Called by the autonomous monitor when an event arrives while idle.
     * Sets {@code busy=true}, publishes the first event, then drains the remainder
     * of the autonomous turn in a new virtual thread until the {@code result} event.
     */
    public void onAutonomousEvent(ChatEvent firstEvent, ActorRef<ChatActor> self) {
        if (busy) return; // lost the race to a concurrent user prompt — discard
        busy = true;
        boolean useWatchdog = watchdog != null && provider.capabilities().supportsWatchdog();
        if (useWatchdog) watchdog.tell(WatchdogActor::onPromptStarted);

        emitEvent(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), true));
        emitEvent(firstEvent);

        // Drain the rest of the autonomous turn in a virtual thread.
        CompletableFuture<Void> done = new CompletableFuture<>();
        StringBuilder assistantBuf = new StringBuilder();
        activeThread = Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    Optional<ChatEvent> e;
                    try {
                        e = provider.pollAutonomousEvent(30_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (e.isEmpty()) {
                        self.tell(a -> a.emitEvent(
                                ChatEvent.error("Autonomous turn timed out waiting for result")));
                        break;
                    }
                    ChatEvent ce = e.get();
                    if ("delta".equals(ce.type()) && ce.content() != null) {
                        assistantBuf.append(ce.content());
                    } else if ("result".equals(ce.type()) && !assistantBuf.isEmpty()) {
                        String content = assistantBuf.toString();
                        self.tell(a -> a.recordHistory("assistant", content));
                    }
                    self.tell(a -> a.emitEvent(ce));
                    if ("result".equals(ce.type())) break;
                }
            } finally {
                self.tell(a -> a.onPromptComplete(a::emitEvent, done, self));
            }
        });
    }

    public record HistoryEntry(String role, String content) {}
}
