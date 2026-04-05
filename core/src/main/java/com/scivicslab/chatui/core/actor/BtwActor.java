package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actor that handles /btw side questions independently of the main ChatActor.
 *
 * <p>A /btw call runs a one-shot LLM request without touching ChatActor state
 * (no history, no busy flag, no queue). The response is streamed back via SSE
 * using {@code btw_delta} and {@code btw_result} event types.</p>
 *
 * <p>Mirrors ChatActor's virtual-thread pattern: heavy LLM I/O runs on a spawned
 * virtual thread so this actor remains responsive (e.g., for {@code cancel()}).</p>
 *
 * <p>This is a plain POJO actor — no CDI annotations, no synchronized blocks.
 * Thread safety is guaranteed by the actor's sequential message processing via ActorRef.</p>
 */
public class BtwActor {

    private static final Logger logger = Logger.getLogger(BtwActor.class.getName());

    private final LlmProvider provider;

    /** The virtual thread running the current btw LLM call, or {@code null} if idle. */
    private volatile Thread activeThread;

    /**
     * Creates a new BtwActor bound to the given LLM provider.
     *
     * @param provider the LLM provider implementation to delegate btw prompts to
     */
    public BtwActor(LlmProvider provider) {
        this.provider = provider;
    }

    /**
     * Begins an asynchronous /btw side question. Spawns a virtual thread for blocking
     * LLM I/O, keeping the actor idle and responsive during the call (e.g., for cancel).
     *
     * <p>Unlike ChatActor, this method does not call {@code provider.setModel()} to avoid
     * mutating shared provider state while a main prompt may be running concurrently.
     * The model is passed directly to {@code sendPrompt()}.</p>
     *
     * @param question the btw question text
     * @param model    the model to use (must not be null or blank)
     * @param apiKey   the API key for authentication
     * @param emitter  callback to send btw_delta / btw_result events to the client
     * @param self     reference to this BtwActor (for finalising active thread)
     */
    public void startBtw(String question, String model, String apiKey,
                         Consumer<ChatEvent> emitter, ActorRef<BtwActor> self) {
        activeThread = Thread.startVirtualThread(() -> {
            try {
                ProviderContext ctx = new ProviderContext(apiKey, List.of(), false, () -> {});
                provider.sendPrompt(question, model, event -> {
                    if ("delta".equals(event.type())) {
                        emitter.accept(ChatEvent.btwDelta(event.content()));
                    } else if ("result".equals(event.type())) {
                        emitter.accept(ChatEvent.btwResult());
                    }
                }, ctx);
            } catch (Exception e) {
                logger.log(Level.WARNING, "BtwActor sendPrompt failed", e);
                emitter.accept(ChatEvent.error("BTW error: " + e.getMessage()));
            } finally {
                self.tell(a -> a.activeThread = null);
            }
        });
    }

    /**
     * Cancels the currently running /btw LLM call, if any.
     *
     * <p>Signals the provider to abort and interrupts the worker virtual thread.</p>
     */
    public void cancel() {
        Thread t = activeThread;
        if (t != null) {
            provider.cancel();
            t.interrupt();
        }
    }

    /**
     * Returns whether a /btw call is currently in progress.
     *
     * @return {@code true} if a btw virtual thread is active
     */
    public boolean isBusy() {
        return activeThread != null;
    }
}
