package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.openaicompat.AgentLoopExtension;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.OpenAiCompatClient;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * CDI implementation of {@link AgentLoopExtension}.
 *
 * <p>Creates one {@link AgentLoopActor} per user turn. The actor owns the loop state
 * and runs its state machine via tell/ask messages. All blocking I/O (LLM and MCP)
 * runs off the actor thread via ask(fn, actorSystem.getManagedThreadPool()), so the
 * actor remains responsive to cancel() at all times.</p>
 *
 * <p>cancel() is called via tellNow(), which bypasses the actor's message queue
 * and sets cancelled=true immediately — no Thread.interrupt() needed.</p>
 *
 * <p>Required config:
 * <pre>
 *   chat-ui.agent-loop.enabled=true
 *   chat-ui.agent-loop.mcp-urls=http://localhost:9000
 * </pre>
 * Optional:
 * <pre>
 *   chat-ui.agent-loop.max-iterations=10
 *   chat-ui.agent-loop.mcp-timeout=120
 * </pre>
 * </p>
 */
@ApplicationScoped
public class AgentLoopExtensionImpl implements AgentLoopExtension {

    private static final Logger LOG = Logger.getLogger(AgentLoopExtensionImpl.class.getName());

    @ConfigProperty(name = "chat-ui.agent-loop.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "chat-ui.agent-loop.mcp-urls")
    Optional<String> mcpUrlsRaw;

    @ConfigProperty(name = "chat-ui.agent-loop.max-iterations", defaultValue = "10")
    int maxIterations;

    @ConfigProperty(name = "chat-ui.agent-loop.mcp-timeout", defaultValue = "120")
    int mcpTimeoutSeconds;

    private List<OpenAiCompatClient> clients = List.of();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // MCP session IDs cached per endpoint; shared across loops (ConcurrentHashMap for safety)
    private final ConcurrentHashMap<String, String> sessionCache = new ConcurrentHashMap<>();

    private final ActorSystem actorSystem = new ActorSystem("agent-loop-system");

    // Reference to the currently running loop actor; null when idle
    private volatile ActorRef<AgentLoopActor> activeLoop;

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void initialize(List<OpenAiCompatClient> clients) {
        this.clients = List.copyOf(clients);
        LOG.info("AgentLoop initialized with " + clients.size() + " client(s), "
                + "mcp-urls=" + mcpUrlsRaw.orElse("(none)") + ", max-iterations=" + maxIterations);
    }

    // ── cancel (called via OpenAiCompatProvider.cancel()) ────────────────────

    /**
     * Cancels the currently running loop, if any.
     * Uses tellNow() to bypass the actor queue and set cancelled=true immediately.
     */
    @Override
    public void cancel() {
        ActorRef<AgentLoopActor> ref = activeLoop;
        if (ref != null) ref.tellNow(AgentLoopActor::cancel);
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Runs the tool-calling loop for a single user turn.
     * Blocks the caller's virtual thread until the loop completes or is cancelled.
     * Called from OpenAiCompatProvider.sendPrompt(), which runs on ChatActor's virtual thread.
     */
    @Override
    public void runAgentLoop(String model, LinkedList<ChatMessage> history,
                             Consumer<ChatEvent> emitter, ProviderContext ctx) {
        List<String> mcpUrls = parseMcpUrls();

        AgentLoopActor actor = new AgentLoopActor(httpClient, sessionCache, Duration.ofSeconds(mcpTimeoutSeconds));
        ActorRef<AgentLoopActor> ref = actorSystem.actorOf("agent-loop", actor);
        activeLoop = ref;

        CompletableFuture<Void> done = new CompletableFuture<>();

        ref.tell(a -> a.start(model, history, emitter, ctx, done,
                clients, mcpUrls, maxIterations, ref));

        try {
            done.get();  // blocks virtual thread until loop completes or is cancelled
        } catch (Exception e) {
            LOG.warning("Agent loop interrupted: " + e.getMessage());
            emitter.accept(ChatEvent.error("Agent loop interrupted: " + e.getMessage()));
        } finally {
            activeLoop = null;
            ref.close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> parseMcpUrls() {
        String raw = mcpUrlsRaw.orElse("");
        if (raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
