package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.mcp.McpClientActor;
import com.scivicslab.chatui.core.multiuser.MultiUserExtension;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.service.LogStreamHandler;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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
 *
 * <p>When a {@link MultiUserExtension} CDI bean is present on the classpath the
 * application runs in multi-user mode. When absent it runs in single-user mode.</p>
 */
@ApplicationScoped
public class ChatUiActorSystem {

    private static final Logger LOG = Logger.getLogger(ChatUiActorSystem.class.getName());

    @Inject
    LlmProvider provider;

    @ConfigProperty(name = "chat-ui.api-key")
    Optional<String> configApiKey;

    @Inject
    LogStreamHandler logStreamHandler;

    @Inject
    Instance<MultiUserExtension> multiUserExtInstance;

    private ActorSystem actorSystem;
    private ActorRef<ChatActor> chatActorRef;
    private ActorRef<WatchdogActor> watchdogRef;
    private ActorRef<QueueActor> queueActorRef;
    private ActorRef<BtwActor> btwActorRef;
    private ActorRef<McpClientActor> mcpClientActorRef;
    private ScheduledExecutorService watchdogTimer;
    private ScheduledExecutorService queueTimer;

    /**
     * Initialises the actor system. When a {@link MultiUserExtension} is available,
     * delegates to it (multi-user mode). Otherwise initialises the full single-user stack.
     */
    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("chat-ui");

        if (!multiUserExtInstance.isUnsatisfied() && multiUserExtInstance.get().isEnabled()) {
            initMultiUser();
        } else {
            initSingleUser();
        }
    }

    private void initSingleUser() {
        chatActorRef = actorSystem.actorOf("chat", new ChatActor(provider, configApiKey));
        logStreamHandler.wireActorRef(chatActorRef);

        queueActorRef = actorSystem.actorOf("queue", new QueueActor());
        chatActorRef.tell(a -> a.setQueueActor(queueActorRef));
        queueTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "queue-timer");
            t.setDaemon(true);
            return t;
        });
        queueTimer.scheduleAtFixedRate(
                () -> queueActorRef.tell(q -> q.tick(chatActorRef)),
                2, 2, TimeUnit.SECONDS);
        LOG.info("QueueActor initialized with 2s tick interval");

        btwActorRef = actorSystem.actorOf("btw", new BtwActor(provider));
        LOG.info("BtwActor initialized");

        mcpClientActorRef = actorSystem.actorOf("mcp-client", new McpClientActor());
        LOG.info("McpClientActor initialized");

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
            LOG.info("ChatUiActorSystem initialized (single-user, provider=" + provider.id() + ", watchdog=enabled)");
        } else {
            LOG.info("ChatUiActorSystem initialized (single-user, provider=" + provider.id() + ", watchdog=disabled)");
        }

        if (provider.capabilities().supportsAutonomousEvents()) {
            chatActorRef.tell(a -> a.startAutonomousMonitor(chatActorRef));
            LOG.info("Autonomous event monitor started (provider=" + provider.id() + ")");
        }
    }

    private void initMultiUser() {
        String apiKey = resolveApiKey();
        MultiUserExtension ext = multiUserExtInstance.get();
        ext.initialize(actorSystem, apiKey);
        logStreamHandler.wireMultiUserExtension(ext);
        LOG.info("ChatUiActorSystem initialized (multi-user, provider=" + provider.id() + ")");
    }

    private String resolveApiKey() {
        String envKey = provider.detectEnvApiKey();
        if (envKey != null && !envKey.isBlank()) return envKey;
        return configApiKey.filter(k -> !k.isBlank()).orElse(null);
    }

    /** Shuts down timer threads and terminates the actor system. Called by CDI before destruction. */
    @PreDestroy
    void shutdown() {
        if (queueTimer != null) queueTimer.shutdownNow();
        if (watchdogTimer != null) watchdogTimer.shutdownNow();
        if (actorSystem != null) actorSystem.terminate();
    }

    public ActorRef<ChatActor> getChatActor() { return chatActorRef; }

    public ActorRef<WatchdogActor> getWatchdog() { return watchdogRef; }

    public ActorRef<QueueActor> getQueueActor() { return queueActorRef; }

    public ActorRef<BtwActor> getBtwActor() { return btwActorRef; }

    public ActorRef<McpClientActor> getMcpClientActor() { return mcpClientActorRef; }

    /** Returns true when the system is running in multi-user mode. */
    public boolean isMultiUser() {
        return !multiUserExtInstance.isUnsatisfied() && multiUserExtInstance.get().isEnabled();
    }

    /**
     * Returns the active {@link MultiUserExtension}, or {@code null} in single-user mode.
     */
    public MultiUserExtension getMultiUserExtension() {
        return isMultiUser() ? multiUserExtInstance.get() : null;
    }

    public LlmProvider getProvider() { return provider; }
}
