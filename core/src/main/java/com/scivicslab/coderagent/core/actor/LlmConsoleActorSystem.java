package com.scivicslab.coderagent.core.actor;

import com.scivicslab.coderagent.core.provider.LlmProvider;
import com.scivicslab.coderagent.core.service.LogStreamHandler;
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

    @ConfigProperty(name = "coder-agent.api-key")
    Optional<String> configApiKey;

    @Inject
    LogStreamHandler logStreamHandler;

    private ActorSystem actorSystem;
    private ActorRef<ChatActor> chatActorRef;
    private ActorRef<WatchdogActor> watchdogRef;
    private ScheduledExecutorService watchdogTimer;

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("coder-agent");

        chatActorRef = actorSystem.actorOf("chat", new ChatActor(provider, configApiKey));
        logStreamHandler.wireActorRef(chatActorRef);

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

    @PreDestroy
    void shutdown() {
        if (watchdogTimer != null) watchdogTimer.shutdownNow();
        if (actorSystem != null) actorSystem.terminate();
    }

    public ActorRef<ChatActor> getChatActor() { return chatActorRef; }

    public ActorRef<WatchdogActor> getWatchdog() { return watchdogRef; }

    public LlmProvider getProvider() { return provider; }
}
