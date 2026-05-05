package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.mcp.McpClientActor;
import com.scivicslab.chatui.core.multiuser.MultiUserExtension;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.service.LogStreamHandler;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import io.vertx.core.Vertx;
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

    @Inject
    Vertx vertx;

    @Inject
    ObjectMapper objectMapper;

    private ActorSystem actorSystem;
    private ActorRef<ChatActor> chatActorRef;
    private ActorRef<WatchdogActor> watchdogRef;
    private ActorRef<QueueActor> queueActorRef;
    private ActorRef<BtwActor> btwActorRef;
    private ActorRef<McpClientActor> mcpClientActorRef;
    private ActorRef<SseActor> sseActorRef;
    private ScheduledExecutorService watchdogTimer;

    // SubQueue stack for batch job isolation (single-user mode only)
    private final java.util.Deque<ActorRef<QueueActor>> subQueueStack = new java.util.ArrayDeque<>();

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
        chatActorRef.tell(a -> a.init(chatActorRef));
        logStreamHandler.wireActorRef(chatActorRef);

        SseActor sseActor = new SseActor(vertx, objectMapper);
        sseActorRef = actorSystem.actorOf("sse", sseActor);
        sseActorRef.tell(a -> a.init(sseActorRef, chatActorRef, null)); // watchdog wired below
        LOG.info("SseActor initialized");

        queueActorRef = actorSystem.actorOf("queue", new QueueActor());
        chatActorRef.tell(a -> a.setQueueActor(queueActorRef));
        LOG.info("QueueActor initialized");

        btwActorRef = actorSystem.actorOf("btw", new BtwActor(provider));
        LOG.info("BtwActor initialized");

        mcpClientActorRef = actorSystem.actorOf("mcp-client", new McpClientActor());
        LOG.info("McpClientActor initialized");

        if (provider.capabilities().supportsWatchdog()) {
            watchdogRef = actorSystem.actorOf("watchdog", new WatchdogActor());
            watchdogRef.tell(w -> w.setQueueActor(queueActorRef));
            chatActorRef.tell(a -> a.setWatchdog(watchdogRef));
            // Re-wire SseActor with the now-known watchdog ref
            sseActorRef.tell(a -> a.init(sseActorRef, chatActorRef, watchdogRef));
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
        if (watchdogTimer != null) watchdogTimer.shutdownNow();
        if (actorSystem != null) actorSystem.terminate();
    }

    /**
     * Pushes a new SubQueueActor onto the stack, making it the active queue.
     * All subsequent prompts (from any source) are routed to this SubQueue.
     * Safe for nested batch jobs — each push adds a new level.
     */
    public synchronized void pushSubQueue(String jobId) {
        String actorName = "queue-job-" + jobId.substring(0, Math.min(8, jobId.length()));
        ActorRef<QueueActor> sub = actorSystem.actorOf(actorName, new QueueActor());
        subQueueStack.push(sub);
        chatActorRef.tell(a -> a.setQueueActor(sub));
        LOG.info("SubQueue pushed for job " + jobId + " (depth=" + subQueueStack.size() + ")");
    }

    /**
     * Pops the top SubQueueActor, restoring the previous queue (or base queue if stack is empty).
     */
    public synchronized void popSubQueue() {
        if (!subQueueStack.isEmpty()) {
            subQueueStack.pop();
        }
        ActorRef<QueueActor> active = subQueueStack.isEmpty() ? queueActorRef : subQueueStack.peek();
        chatActorRef.tell(a -> a.setQueueActor(active));
        LOG.info("SubQueue popped (depth=" + subQueueStack.size() + ")");
    }

    public ActorRef<ChatActor> getChatActor() { return chatActorRef; }

    public ActorRef<WatchdogActor> getWatchdog() { return watchdogRef; }

    public synchronized ActorRef<QueueActor> getQueueActor() {
        return subQueueStack.isEmpty() ? queueActorRef : subQueueStack.peek();
    }

    public ActorRef<BtwActor> getBtwActor() { return btwActorRef; }

    public ActorRef<McpClientActor> getMcpClientActor() { return mcpClientActorRef; }

    public ActorRef<SseActor> getSseActor() { return sseActorRef; }

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
