package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.service.LogStreamHandler;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * CDI bean that owns the POJO-actor system for this application.
 * Creates and holds the single {@link ChatActor} instance.
 * Conditionally creates {@link WatchdogActor} based on provider capabilities.
 */
@ApplicationScoped
public class LlmConsoleActorSystem {

    private static final Logger LOG = Logger.getLogger(LlmConsoleActorSystem.class.getName());

    @Inject
    LlmProvider provider;

    @ConfigProperty(name = "chat-ui.api-key")
    Optional<String> configApiKey;

    @Inject
    LogStreamHandler logStreamHandler;

    private ActorSystem actorSystem;
    private ActorRef<ChatActor> chatActorRef;
    private ActorRef<WatchdogActor> watchdogRef;
    private ActorRef<QueueActor> queueActorRef;
    private ScheduledExecutorService watchdogTimer;
    private ScheduledExecutorService queueTimer;

    /**
     * Initialises the actor system, creates the chat, queue, and (optionally) watchdog actors,
     * and starts their periodic timer tasks. Called automatically by CDI after construction.
     */
    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("chat-ui");

        chatActorRef = actorSystem.actorOf("chat", new ChatActor(provider, configApiKey));
        logStreamHandler.wireActorRef(chatActorRef);

        // Create QueueActor and wire periodic tick
        queueActorRef = actorSystem.actorOf("queue", new QueueActor());
        queueTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "queue-timer");
            t.setDaemon(true);
            return t;
        });
        queueTimer.scheduleAtFixedRate(
                () -> queueActorRef.tell(q -> q.tick(chatActorRef)),
                2, 2, TimeUnit.SECONDS);
        LOG.info("QueueActor initialized with 2s tick interval");

        if (provider.capabilities().supportsWatchdog()) {
            watchdogRef = actorSystem.actorOf("watchdog", new WatchdogActor());
            chatActorRef.tell(a -> a.setWatchdog(watchdogRef));

            watchdogTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "watchdog-timer");
                t.setDaemon(true);
                return t;
            });
            watchdogTimer.scheduleAtFixedRate(
                    () -> watchdogRef.tell(w -> w.tick(chatActorRef)),
                    10, 10, TimeUnit.SECONDS);

            LOG.info("LlmConsoleActorSystem initialized (provider=" + provider.id() + ", watchdog=enabled)");
        } else {
            LOG.info("LlmConsoleActorSystem initialized (provider=" + provider.id() + ", watchdog=disabled)");
        }
    }

    /** Shuts down timer threads and terminates the actor system. Called by CDI before destruction. */
    @PreDestroy
    void shutdown() {
        if (queueTimer != null) queueTimer.shutdownNow();
        if (watchdogTimer != null) watchdogTimer.shutdownNow();
        if (actorSystem != null) actorSystem.terminate();
    }

    /**
     * Returns the reference to the singleton chat actor.
     *
     * @return the chat actor reference
     */
    public ActorRef<ChatActor> getChatActor() { return chatActorRef; }

    /**
     * Returns the reference to the watchdog actor, or {@code null} if the provider does not support it.
     *
     * @return the watchdog actor reference, or {@code null}
     */
    public ActorRef<WatchdogActor> getWatchdog() { return watchdogRef; }

    /**
     * Returns the reference to the prompt queue actor.
     *
     * @return the queue actor reference
     */
    public ActorRef<QueueActor> getQueueActor() { return queueActorRef; }

    /**
     * Returns the injected LLM provider instance.
     *
     * @return the LLM provider
     */
    public LlmProvider getProvider() { return provider; }
}
