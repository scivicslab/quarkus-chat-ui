package com.scivicslab.chatui.tmux;

/**
 * Time-based idle detector for the case-B ({@code pipe-pane}) output monitor.
 *
 * <p>The {@code pipe-pane} reader reports activity whenever bytes arrive from the
 * pane. This debouncer reports "settled" once no activity has occurred for
 * {@code idleMillis}. Because the thinking spinner redraws (and thus emits bytes)
 * while Claude works, the stream only goes idle when the program truly stops
 * writing, which is exactly the moment to capture and extract.
 *
 * <p>Pure state machine: the caller supplies the current time, so it is unit
 * tested deterministically with synthetic timestamps. Not thread-safe; a single
 * owner mutates it serially.
 */
public final class IdleDebouncer {

    private final long idleMillis;

    private long lastActivity;
    private boolean active;          // there has been activity since the last settle
    private boolean settledReported; // the current idle period has already been reported

    /**
     * @param idleMillis how long the stream must be silent before it is settled
     *                   (must be at least 1)
     */
    public IdleDebouncer(long idleMillis) {
        if (idleMillis < 1) {
            throw new IllegalArgumentException("idleMillis must be >= 1");
        }
        this.idleMillis = idleMillis;
    }

    /**
     * Records that pane output bytes arrived.
     *
     * @param nowMillis the current time in epoch milliseconds
     */
    public void onActivity(long nowMillis) {
        lastActivity = nowMillis;
        active = true;
        settledReported = false;
    }

    /**
     * Checks whether the stream has just gone idle.
     *
     * @param nowMillis the current time in epoch milliseconds
     * @return {@code true} exactly once per idle period, on the first check at or
     *         after {@code idleMillis} have elapsed since the last activity
     */
    public boolean checkSettled(long nowMillis) {
        if (!active || settledReported) {
            return false;
        }
        if (nowMillis - lastActivity >= idleMillis) {
            settledReported = true;
            active = false;
            return true;
        }
        return false;
    }
}
