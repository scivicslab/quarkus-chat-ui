package com.scivicslab.coderagent.core.actor;

import com.scivicslab.coderagent.core.rest.ChatEvent;
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

    static final Duration DEFAULT_STALL_THRESHOLD = Duration.ofSeconds(90);

    private final Duration stallThreshold;

    private boolean promptActive;
    private Instant lastActivityTime;
    private boolean stallNotified;
    private Consumer<ChatEvent> sseEmitter;

    public WatchdogActor() {
        this(DEFAULT_STALL_THRESHOLD);
    }

    public WatchdogActor(Duration stallThreshold) {
        this.stallThreshold = stallThreshold;
        this.promptActive = false;
        this.lastActivityTime = Instant.now();
        this.stallNotified = false;
    }

    public void onPromptStarted() {
        promptActive = true;
        lastActivityTime = Instant.now();
        stallNotified = false;
        LOG.fine("Watchdog: prompt started");
    }

    public void onActivity() {
        lastActivityTime = Instant.now();
        if (stallNotified) {
            stallNotified = false;
            emitEvent(ChatEvent.info("LLM has resumed responding."));
            LOG.info("Watchdog: activity resumed after stall");
        }
    }

    public void onPromptFinished() {
        promptActive = false;
        stallNotified = false;
        LOG.fine("Watchdog: prompt finished");
    }

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

    public void setSseEmitter(Consumer<ChatEvent> emitter) { this.sseEmitter = emitter; }
    public void clearSseEmitter() { this.sseEmitter = null; }

    public boolean isPromptActive() { return promptActive; }
    public boolean isStallNotified() { return stallNotified; }
    public Instant getLastActivityTime() { return lastActivityTime; }
    public Duration getStallThreshold() { return stallThreshold; }
    public Duration getElapsedSinceActivity() { return Duration.between(lastActivityTime, Instant.now()); }

    private void emitEvent(ChatEvent event) {
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception e) { LOG.fine("Watchdog: SSE emit failed: " + e.getMessage()); }
        }
    }
}
