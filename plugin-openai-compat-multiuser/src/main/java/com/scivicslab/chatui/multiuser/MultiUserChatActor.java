package com.scivicslab.chatui.multiuser;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-user variant of ChatActor. Each user gets isolated conversation history
 * and busy state, identified by a userId string (from BasicAuth username or query param).
 *
 * <p>Activated when the {@code plugin-openai-compat-multiuser} JAR is on the classpath.
 * All per-user state lives in {@link UserSession} instances. Thread safety is guaranteed
 * by the actor's sequential message processing, except {@code activeThread} which is
 * volatile so that {@code cancel()} can interrupt it from any thread.</p>
 *
 * <p>The shared LLM provider is used for all users. This is suitable for local vLLM /
 * openai-compat deployments where a single model serves multiple users.</p>
 */
public class MultiUserChatActor {

    private static final Logger logger = Logger.getLogger(MultiUserChatActor.class.getName());
    private static final int MAX_HISTORY = 200;
    private static final int LOG_BUFFER_SIZE = 500;

    private final LlmProvider provider;
    private final String apiKey;

    /** Per-user sessions, created lazily on first access. */
    private final ConcurrentHashMap<String, UserSession> sessions = new ConcurrentHashMap<>();

    /** Shared log ring buffer. Not streamed to SSE in multi-user mode. */
    private final ChatEvent[] logBuffer = new ChatEvent[LOG_BUFFER_SIZE];
    private int logHead = 0;
    private int logCount = 0;

    public MultiUserChatActor(LlmProvider provider, String apiKey) {
        this.provider = provider;
        this.apiKey = apiKey;
    }

    // ---- Session access ----

    private UserSession session(String userId) {
        return sessions.computeIfAbsent(userId, k -> new UserSession());
    }

    // ---- Provider delegation ----

    public boolean isBusy(String userId) {
        return session(userId).busy;
    }

    public String getModel() {
        return provider.getCurrentModel();
    }

    public List<LlmProvider.ModelEntry> getAvailableModels() {
        return provider.getAvailableModels();
    }

    // ---- Chat lifecycle ----

    /**
     * Begins an asynchronous prompt for the given user. Spawns a virtual thread for
     * blocking LLM I/O so the actor remains idle and responsive during processing.
     */
    public void startPrompt(String userId, String prompt, String model,
                            Consumer<ChatEvent> emitter,
                            ActorRef<MultiUserChatActor> self,
                            CompletableFuture<Void> done) {
        UserSession s = session(userId);
        if (s.busy) {
            emitter.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            done.complete(null);
            return;
        }

        s.busy = true;
        if (model != null && !model.isBlank()) provider.setModel(model);

        s.activeThread = Thread.startVirtualThread(() -> {
            try {
                ProviderContext ctx = ProviderContext.simple(apiKey);

                self.tell(a -> a.recordHistory(userId, "user", prompt));

                StringBuilder assistantBuf = new StringBuilder();
                Consumer<ChatEvent> wrappedEmitter = event -> {
                    if ("delta".equals(event.type()) && event.content() != null) {
                        assistantBuf.append(event.content());
                    } else if ("result".equals(event.type()) && !assistantBuf.isEmpty()) {
                        String content = assistantBuf.toString();
                        self.tell(a -> a.recordHistory(userId, "assistant", content));
                    }
                    emitter.accept(event);
                };

                emitter.accept(ChatEvent.status(provider.getCurrentModel(), null, true));
                provider.sendPrompt(prompt, provider.getCurrentModel(), wrappedEmitter, ctx);

            } catch (Exception e) {
                logger.log(Level.WARNING, "Provider sendPrompt failed for user=" + userId, e);
                emitter.accept(ChatEvent.error("Error: " + e.getMessage()));
            } finally {
                self.tell(a -> a.onPromptComplete(userId, emitter, done));
            }
        });
    }

    /** Called by the worker virtual thread when LLM processing finishes. */
    public void onPromptComplete(String userId, Consumer<ChatEvent> emitter,
                                 CompletableFuture<Void> done) {
        UserSession s = session(userId);
        s.busy = false;
        s.activeThread = null;
        emitter.accept(ChatEvent.status(provider.getCurrentModel(), null, false));
        done.complete(null);
    }

    /**
     * Cancels the currently running prompt for the given user, if any.
     */
    public void cancel(String userId) {
        provider.cancel();
        UserSession s = sessions.get(userId);
        if (s != null) {
            Thread t = s.activeThread;
            if (t != null) t.interrupt();
        }
    }

    // ---- History ----

    public void recordHistory(String userId, String role, String content) {
        if (content == null || content.isBlank()) return;
        UserSession s = session(userId);
        s.history.addLast(new ChatActor.HistoryEntry(role, content));
        while (s.history.size() > MAX_HISTORY) s.history.removeFirst();
    }

    public List<ChatActor.HistoryEntry> getHistory(String userId, int limit) {
        UserSession s = session(userId);
        int size = s.history.size();
        int from = Math.max(0, size - limit);
        return Collections.unmodifiableList(new ArrayList<>(s.history.subList(from, size)));
    }

    public void clearHistory(String userId) {
        session(userId).history.clear();
    }

    // ---- Log ring buffer ----

    /**
     * Stores a log event in the shared ring buffer only.
     * Log streaming to SSE is intentionally disabled in multi-user mode.
     */
    public void publishLog(String level, String loggerName, String message, long timestamp) {
        ChatEvent event = ChatEvent.log(level, loggerName, message, timestamp);
        logBuffer[logHead] = event;
        logHead = (logHead + 1) % LOG_BUFFER_SIZE;
        if (logCount < LOG_BUFFER_SIZE) logCount++;
    }

    public List<ChatEvent> getRecentLogs() {
        List<ChatEvent> result = new ArrayList<>(logCount);
        int start = (logHead - logCount + LOG_BUFFER_SIZE) % LOG_BUFFER_SIZE;
        for (int i = 0; i < logCount; i++) result.add(logBuffer[(start + i) % LOG_BUFFER_SIZE]);
        return result;
    }

    // ---- Per-user state ----

    static class UserSession {
        boolean busy = false;
        volatile Thread activeThread = null;
        final LinkedList<ChatActor.HistoryEntry> history = new LinkedList<>();
    }
}
