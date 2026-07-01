package com.scivicslab.chatui.tmux;

/**
 * Detects when the TUI output has gone quiet (settled) by debouncing successive
 * {@code capture-pane} snapshots.
 *
 * <p>The signal is raw-snapshot equality: while the screen keeps changing (the
 * thinking spinner animating, text streaming in), snapshots differ and the
 * tracker stays "busy". Once snapshots stop changing for {@code settleTicks}
 * consecutive polls, the tracker reports settled exactly once.
 *
 * <p>Pure state machine with no I/O, so it is unit-tested directly. A single
 * {@code ScreenPoller} owns one instance and feeds it serially.
 */
public final class QuiescenceTracker {

    private final int settleTicks;

    private String last;
    private boolean busy;
    private int unchanged;

    /**
     * @param settleTicks number of consecutive unchanged polls required to
     *                    declare the screen settled (must be at least 1)
     */
    public QuiescenceTracker(int settleTicks) {
        if (settleTicks < 1) {
            throw new IllegalArgumentException("settleTicks must be >= 1");
        }
        this.settleTicks = settleTicks;
    }

    /**
     * Feeds one snapshot.
     *
     * @param capture the latest {@code capture-pane} text
     * @return {@code true} exactly on the poll where the screen transitions from
     *         changing to settled; {@code false} otherwise (including while idle
     *         and during the first, baseline, sample)
     */
    public boolean update(String capture) {
        if (last == null) {
            last = capture;
            return false;
        }
        if (!capture.equals(last)) {
            last = capture;
            busy = true;
            unchanged = 0;
            return false;
        }
        if (!busy) {
            return false;
        }
        unchanged++;
        if (unchanged >= settleTicks) {
            busy = false;
            unchanged = 0;
            return true;
        }
        return false;
    }

    /** Forgets all history so the next snapshot becomes a fresh baseline. */
    public void reset() {
        last = null;
        busy = false;
        unchanged = 0;
    }
}
