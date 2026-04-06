package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Watchdog actor that monitors LLM CLI activity and detects stalls.
 *
 * <p>When the ChatActor starts a prompt, it calls {@link #onPromptStarted()}.
 * Each stream event from the CLI triggers {@link #onActivity()}.
 * When the prompt completes, {@link #onPromptFinished()} is called.</p>
 *
 * <p>A periodic check (driven externally via {@link #tick(ActorRef)}) compares
 * the elapsed time since the last activity against the threshold.
 * If no activity is seen for that long while a prompt is active, the watchdog
 * emits a stall warning via SSE.</p>
 *
 * <p>Only used for CLI providers ({@code capabilities().supportsWatchdog() == true}).</p>
 */
public class WatchdogActor {

    private static final Logger LOG = Logger.getLogger(WatchdogActor.class.getName());

    static final Duration DEFAULT_STALL_THRESHOLD = Duration.ofSeconds(300);

    private final Duration stallThreshold;

    private boolean promptActive;
    private Instant promptStartTime;
    private Instant lastActivityTime;
    private boolean stallNotified;
    private boolean stallRecovered;
    private Consumer<ChatEvent> sseEmitter;

    /** Creates a watchdog with the default stall threshold of 90 seconds. */
    public WatchdogActor() {
        this(DEFAULT_STALL_THRESHOLD);
    }

    /**
     * Creates a watchdog with a custom stall threshold.
     *
     * @param stallThreshold the duration of inactivity after which a stall warning is emitted
     */
    public WatchdogActor(Duration stallThreshold) {
        this.stallThreshold = stallThreshold;
        this.promptActive = false;
        this.lastActivityTime = Instant.now();
        this.stallNotified = false;
    }

    /** Resets tracking state and begins monitoring for a new prompt. */
    public void onPromptStarted() {
        promptActive = true;
        promptStartTime = Instant.now();
        lastActivityTime = Instant.now();
        stallNotified = false;
        stallRecovered = false;
        LOG.fine("Watchdog: prompt started");
    }

    /**
     * Records an activity heartbeat from the LLM stream.
     *
     * <p>If a stall had been previously reported, emits a recovery notification via SSE.</p>
     */
    public void onActivity() {
        lastActivityTime = Instant.now();
        if (stallNotified) {
            stallNotified = false;
            stallRecovered = true;
            emitEvent(ChatEvent.info("LLM has resumed responding."));
            LOG.info("Watchdog: activity resumed after stall");
        }
    }

    /** Marks the prompt as complete and resets stall tracking state. */
    public void onPromptFinished() {
        if (stallRecovered) {
            long totalSeconds = Duration.between(promptStartTime, Instant.now()).getSeconds();
            LOG.info("Watchdog: prompt completed after stall recovery"
                    + " (total " + totalSeconds + "s)."
                    + " Session preserved by watchdog monitoring.");
        }
        promptActive = false;
        stallNotified = false;
        stallRecovered = false;
        LOG.fine("Watchdog: prompt finished");
    }

    /**
     * Periodic check invoked by an external timer.
     *
     * <p>If a prompt is active and the elapsed time since the last activity exceeds the
     * stall threshold, emits a warning event via SSE. Does nothing when no prompt is active.</p>
     *
     * @param chatActor reference to the chat actor (reserved for future recovery actions)
     */
    public void tick(ActorRef<ChatActor> chatActor) {
        if (!promptActive) return;
        Duration elapsed = Duration.between(lastActivityTime, Instant.now());
        if (elapsed.compareTo(stallThreshold) > 0 && !stallNotified) {
            stallNotified = true;
            long seconds = elapsed.getSeconds();
            String message = "LLM has not responded for " + seconds
                    + " seconds. This may indicate a context overflow or network issue. "
                    + "Consider sending a new message to resume.";
            LOG.warning("Watchdog: stall detected (" + seconds + "s without activity)");
            emitEvent(ChatEvent.info(message));
        }
    }

    /**
     * Registers an SSE emitter to receive watchdog notifications (stall warnings, recovery).
     *
     * @param emitter the consumer to receive watchdog events
     */
    public void setSseEmitter(Consumer<ChatEvent> emitter) { this.sseEmitter = emitter; }
    /** Unregisters the current SSE emitter. */
    public void clearSseEmitter() { this.sseEmitter = null; }

    /**
     * Returns whether a prompt is currently being monitored.
     *
     * @return {@code true} if a prompt is active
     */
    public boolean isPromptActive() { return promptActive; }

    /**
     * Returns whether a stall warning has been emitted for the current prompt.
     *
     * @return {@code true} if a stall notification was sent and not yet recovered
     */
    public boolean isStallNotified() { return stallNotified; }

    /**
     * Returns the timestamp of the most recent activity heartbeat.
     *
     * @return the last activity instant
     */
    public Instant getLastActivityTime() { return lastActivityTime; }

    /**
     * Returns the configured stall threshold duration.
     *
     * @return the stall threshold
     */
    public Duration getStallThreshold() { return stallThreshold; }

    /**
     * Returns the elapsed time since the last activity heartbeat.
     *
     * @return the duration since the last recorded activity
     */
    public Duration getElapsedSinceActivity() { return Duration.between(lastActivityTime, Instant.now()); }

    private void emitEvent(ChatEvent event) {
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception e) { LOG.fine("Watchdog: SSE emit failed: " + e.getMessage()); }
        }
    }
}
