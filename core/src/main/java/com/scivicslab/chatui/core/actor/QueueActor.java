package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Actor that manages message queueing when the ChatActor is busy processing a prompt.
 *
 * <p>Supports three modes:</p>
 * <ul>
 *   <li><b>queue</b> - Accept the message, hold it, and send it when ChatActor becomes idle.
 *       Returns immediately with a "Queued" confirmation.</li>
 *   <li><b>cancel_and_send</b> - Cancel the current prompt, add the new message to the front
 *       of the queue, and send it once ChatActor becomes idle.</li>
 *   <li><b>wait</b> - Wait up to {@code timeoutSeconds} for ChatActor to become idle.
 *       If it becomes idle in time, send the message. If timeout expires, cancel the current
 *       prompt and then send.</li>
 * </ul>
 *
 * <p>This is a plain POJO actor — no CDI annotations, no synchronized blocks.
 * Thread safety is guaranteed by the actor's sequential message processing via ActorRef.</p>
 */
public class QueueActor {

    private static final Logger LOG = Logger.getLogger(QueueActor.class.getName());

    private final Deque<QueueItem> queue = new ArrayDeque<>();

    /**
     * Main entry point for enqueuing a prompt when ChatActor may be busy.
     *
     * @param prompt       the prompt text
     * @param model        the model to use (may be null)
     * @param mode         one of "queue", "cancel_and_send", "wait"
     * @param timeoutSeconds timeout in seconds (only used for "wait" mode)
     * @param emitter      callback to send ChatEvent responses to the client
     * @param chatActorRef reference to the ChatActor
     * @param self         reference to this QueueActor (for scheduling dequeue)
     */
    public void enqueue(String prompt, String model, String mode,
                        int timeoutSeconds, Consumer<ChatEvent> emitter,
                        ActorRef<ChatActor> chatActorRef, ActorRef<QueueActor> self) {

        CompletableFuture<Void> done = new CompletableFuture<>();

        switch (mode) {
            case "cancel_and_send" -> {
                QueueItem item = new QueueItem(prompt, model, mode, 0, Instant.now(), emitter, done);
                queue.addFirst(item);
                chatActorRef.tell(ChatActor::cancel);
                emitter.accept(ChatEvent.info("Current prompt cancelled. Your message is queued."));
                LOG.info("cancel_and_send: cancelled current prompt, queued at front (queue size=" + queue.size() + ")");
            }
            case "wait" -> {
                QueueItem item = new QueueItem(prompt, model, mode, timeoutSeconds, Instant.now(), emitter, done);
                queue.addLast(item);
                emitter.accept(ChatEvent.info("Waiting up to " + timeoutSeconds + "s for ChatActor to become idle."));
                LOG.info("wait: queued with timeout=" + timeoutSeconds + "s (queue size=" + queue.size() + ")");
            }
            default -> {
                // "queue" mode (default)
                QueueItem item = new QueueItem(prompt, model, mode, 0, Instant.now(), emitter, done);
                queue.addLast(item);
                emitter.accept(ChatEvent.info("Queued. Your message will be sent when the current prompt finishes."));
                LOG.info("queue: queued prompt (queue size=" + queue.size() + ")");
            }
        }
    }

    /**
     * Called periodically (e.g. every 2 seconds) to check if ChatActor is idle
     * and process the next item in the queue.
     *
     * <p>Also checks timeouts on "wait" mode items. If a wait item has expired,
     * it cancels the current ChatActor prompt so that the next tick can dispatch it.</p>
     *
     * @param chatActorRef reference to the ChatActor
     */
    public void tick(ActorRef<ChatActor> chatActorRef) {
        if (queue.isEmpty()) return;

        // Check timeouts on wait-mode items before attempting dequeue
        checkWaitTimeouts(chatActorRef);

        // Ask ChatActor if it is idle; if so, dequeue and send
        chatActorRef.ask(ChatActor::isBusy).thenAccept(busy -> {
            if (!busy) {
                chatActorRef.tell(chat -> dequeueAndSend(chat, chatActorRef));
            }
        });
    }

    /**
     * Called when ChatActor finishes a prompt. Triggers immediate dequeue attempt
     * if the queue is not empty.
     *
     * @param chatActorRef reference to the ChatActor
     */
    public void onPromptComplete(ActorRef<ChatActor> chatActorRef) {
        if (queue.isEmpty()) return;

        chatActorRef.tell(chat -> dequeueAndSend(chat, chatActorRef));
    }

    /**
     * Returns the current number of items in the queue.
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Returns true if the queue has pending items.
     */
    public boolean hasPending() {
        return !queue.isEmpty();
    }

    // ---- Internal ----

    /**
     * Dequeues the next item and tells ChatActor to start the prompt.
     * This method is called within ChatActor's message context (via tell),
     * so isBusy() is safe to read directly.
     */
    private void dequeueAndSend(ChatActor chat, ActorRef<ChatActor> chatActorRef) {
        if (queue.isEmpty()) return;
        if (chat.isBusy()) return;

        QueueItem item = queue.pollFirst();
        if (item == null) return;

        LOG.info("Dequeuing prompt (remaining=" + queue.size() + "): "
                + truncate(item.prompt(), 80));

        chat.startPrompt(item.prompt(), item.model(), item.emitter(), chatActorRef, item.done());
    }

    /**
     * Checks "wait" mode items for timeout expiration.
     * If the first wait item in the queue has timed out, cancel the current prompt
     * so the next tick/onPromptComplete can dispatch it.
     */
    private void checkWaitTimeouts(ActorRef<ChatActor> chatActorRef) {
        for (QueueItem item : queue) {
            if (!"wait".equals(item.mode())) continue;
            if (item.timeoutSeconds() <= 0) continue;

            Duration elapsed = Duration.between(item.enqueuedAt(), Instant.now());
            if (elapsed.getSeconds() >= item.timeoutSeconds()) {
                LOG.info("wait timeout expired (" + item.timeoutSeconds()
                        + "s). Cancelling current prompt.");
                item.emitter().accept(ChatEvent.info(
                        "Wait timeout expired after " + item.timeoutSeconds()
                                + "s. Cancelling current prompt."));
                chatActorRef.tell(ChatActor::cancel);
                break; // Only cancel once per tick
            }
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Represents a queued prompt with its metadata.
     */
    public record QueueItem(
            String prompt,
            String model,
            String mode,
            int timeoutSeconds,
            Instant enqueuedAt,
            Consumer<ChatEvent> emitter,
            CompletableFuture<Void> done
    ) {}
}
