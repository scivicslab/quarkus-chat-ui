package com.scivicslab.chatui.core.multiuser;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * SPI for the optional multi-user plugin.
 *
 * <p>Implementations are discovered via CDI. When no implementation is present on the
 * classpath the application runs in single-user mode. When an implementation is present
 * (e.g. {@code plugin-openai-compat-multiuser}), it is activated automatically.</p>
 *
 * <p>{@link #initialize} is called once by {@code ChatUiActorSystem} after the actor
 * system is set up, passing in the shared resources the implementation needs.</p>
 */
public interface MultiUserExtension {

    /**
     * Called once at startup to give the extension access to the shared actor system
     * and resolved API key.
     */
    void initialize(ActorSystem actorSystem, String apiKey);

    boolean isBusy(String userId);

    String getModel();

    void startPrompt(String userId, String prompt, String model,
                     Consumer<ChatEvent> emitter, CompletableFuture<Void> done);

    void cancel(String userId);

    List<LlmProvider.ModelEntry> getAvailableModels();

    List<ChatEvent> getRecentLogs();

    List<ChatActor.HistoryEntry> getHistory(String userId, int limit);

    /**
     * The users this extension is holding a conversation for.
     *
     * <p>{@code getHistory} answers for a user someone already named. This names them, which is what
     * a caller describing the whole instance needs ({@code ActivitySummary_260905_oo01}).</p>
     *
     * @return the user ids, in no particular order; empty when nobody has spoken yet
     */
    default List<String> getUserIds() {
        return List.of();
    }

    /** Stores a log record in the shared ring buffer. */
    void publishLog(String level, String loggerName, String message, long timestamp);

    /**
     * Returns true when multi-user mode is enabled by configuration.
     * The plugin may be on the classpath but still disabled via {@code chat-ui.multi-user=false}.
     */
    boolean isEnabled();
}
