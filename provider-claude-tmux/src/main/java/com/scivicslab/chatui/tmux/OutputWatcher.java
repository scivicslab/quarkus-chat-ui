package com.scivicslab.chatui.tmux;

import java.util.function.Supplier;

/**
 * Ties the case-B monitor together: pane byte activity (from {@link PipePaneReader})
 * feeds an {@link IdleDebouncer}; when the stream goes idle, the settled screen is
 * captured and run through a {@link ScreenExtractor}, and the extracted events are
 * returned to the caller.
 *
 * <p>Designed to be wrapped as a POJO-actor: {@link #recordActivity} is told from
 * the reader thread and {@link #tick} is asked from the caller's drive loop, so all
 * mutable state (the debouncer, the extractor) is touched only on the actor's
 * thread — no locks. The screen capture is supplied as a {@code Supplier<String>}
 * (in production {@code tmuxSession::captureAll}) so the glue can be unit-tested
 * without tmux.
 *
 * <p>{@link #tick} returns its outcome rather than pushing to a sink, so the caller
 * (the provider's blocking {@code sendPrompt} loop) decides how to emit each event
 * and when the turn is finished.
 */
public final class OutputWatcher {

    private final Supplier<String> captureSource;
    private final ScreenExtractor extractor;
    private final IdleDebouncer debouncer;

    /**
     * @param captureSource yields the current settled screen text (e.g. {@code tmux capture-pane})
     * @param extractor     turns screen text into events (diffing against prior captures)
     * @param debouncer     decides when the byte stream has gone idle
     */
    public OutputWatcher(Supplier<String> captureSource, ScreenExtractor extractor,
                         IdleDebouncer debouncer) {
        this.captureSource = captureSource;
        this.extractor = extractor;
        this.debouncer = debouncer;
    }

    /**
     * Records that pane output bytes arrived.
     *
     * @param nowMillis the current time in epoch milliseconds
     */
    public void recordActivity(long nowMillis) {
        debouncer.onActivity(nowMillis);
    }

    /**
     * Checks for settle; if the stream has just gone idle, captures the screen and
     * extracts events.
     *
     * @param nowMillis the current time in epoch milliseconds
     * @return a settled result with the extracted events, or
     *         {@link TickResult#notSettled()} while the stream is still active
     */
    public TickResult tick(long nowMillis) {
        if (!debouncer.checkSettled(nowMillis)) {
            return TickResult.notSettled();
        }
        String capture = captureSource.get();
        return new TickResult(true, extractor.ingest(capture), capture);
    }
}
