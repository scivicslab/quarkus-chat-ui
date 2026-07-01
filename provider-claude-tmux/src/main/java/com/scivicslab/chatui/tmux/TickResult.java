package com.scivicslab.chatui.tmux;

import java.util.List;

/**
 * Outcome of one {@link OutputWatcher#tick} call.
 *
 * @param settled {@code true} if the byte stream had just gone idle on this tick
 * @param events  events extracted from the settled screen (empty unless {@code settled})
 * @param capture the screen text captured on settle (empty unless {@code settled});
 *                lets the caller classify the settled state (input-ready vs still working)
 */
public record TickResult(boolean settled, List<ExtractedEvent> events, String capture) {

    private static final TickResult NOT_SETTLED = new TickResult(false, List.of(), "");

    /** @return a shared "still active, nothing to emit" result */
    public static TickResult notSettled() {
        return NOT_SETTLED;
    }
}
