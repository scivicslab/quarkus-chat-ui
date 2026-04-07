package com.scivicslab.chatui.multiuser;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.multiuser.MultiUserExtension;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * CDI implementation of {@link MultiUserExtension} for the openai-compat provider.
 *
 * <p>Including this JAR on the classpath activates multi-user mode automatically
 * via CDI discovery — no configuration flag needed.</p>
 *
 * <p>{@link #initialize} is called once by {@code LlmConsoleActorSystem} after the
 * actor system is ready.</p>
 */
@ApplicationScoped
public class MultiUserExtensionImpl implements MultiUserExtension {

    private static final Logger logger = Logger.getLogger(MultiUserExtensionImpl.class.getName());

    @Inject
    LlmProvider provider;

    @ConfigProperty(name = "chat-ui.multi-user", defaultValue = "false")
    boolean multiUserEnabled;

    private ActorRef<MultiUserChatActor> actorRef;

    @Override
    public void initialize(ActorSystem actorSystem, String apiKey) {
        actorRef = actorSystem.actorOf("multi-user-chat", new MultiUserChatActor(provider, apiKey));
        logger.info("MultiUserExtensionImpl initialized (provider=" + provider.id() + ")");
    }

    @Override
    public boolean isBusy(String userId) {
        return actorRef.ask(a -> a.isBusy(userId)).join();
    }

    @Override
    public String getModel() {
        return actorRef.ask(MultiUserChatActor::getModel).join();
    }

    @Override
    public void startPrompt(String userId, String prompt, String model,
                            Consumer<ChatEvent> emitter, CompletableFuture<Void> done) {
        actorRef.tell(a -> a.startPrompt(userId, prompt, model, emitter, actorRef, done));
    }

    @Override
    public void cancel(String userId) {
        actorRef.tell(a -> a.cancel(userId));
    }

    @Override
    public List<LlmProvider.ModelEntry> getAvailableModels() {
        return actorRef.ask(MultiUserChatActor::getAvailableModels).join();
    }

    @Override
    public List<ChatEvent> getRecentLogs() {
        return actorRef.ask(MultiUserChatActor::getRecentLogs).join();
    }

    @Override
    public List<ChatActor.HistoryEntry> getHistory(String userId, int limit) {
        return actorRef.ask(a -> a.getHistory(userId, limit)).join();
    }

    @Override
    public void publishLog(String level, String loggerName, String message, long timestamp) {
        actorRef.tell(a -> a.publishLog(level, loggerName, message, timestamp));
    }

    @Override
    public boolean isEnabled() {
        if (!multiUserEnabled) return false;
        // Multi-user mode is only meaningful for the openai-compat provider.
        // CLI providers (claude, codex) manage sessions inside a single process
        // and cannot share that process across users.
        return "openai-compat".equals(provider.id());
    }
}
