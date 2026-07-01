package com.scivicslab.chatui.tmux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IdleDebouncer}, driven with synthetic timestamps so the
 * debounce logic is verified without real waiting. Pure logic, unit test.
 */
@Tag("TmuxTuiDriver_Idle_260630_oo01")
@DisplayName("IdleDebouncer — pipe-pane silence detection")
class IdleDebouncerTest {

    @Test
    @DisplayName("with no activity yet, never settles")
    void noActivity_neverSettles() {
        IdleDebouncer d = new IdleDebouncer(300);
        assertFalse(d.checkSettled(1000));
        assertFalse(d.checkSettled(9999));
    }

    @Test
    @DisplayName("does not settle before the idle threshold elapses")
    void beforeThreshold_notSettled() {
        IdleDebouncer d = new IdleDebouncer(300);
        d.onActivity(1000);
        assertFalse(d.checkSettled(1100));
        assertFalse(d.checkSettled(1299));
    }

    @Test
    @DisplayName("settles once at the idle threshold, then not again")
    void atThreshold_settlesOnce() {
        IdleDebouncer d = new IdleDebouncer(300);
        d.onActivity(1000);
        assertTrue(d.checkSettled(1300));
        assertFalse(d.checkSettled(1400));
        assertFalse(d.checkSettled(2000));
    }

    @Test
    @DisplayName("activity within the window pushes the settle time out")
    void activityResetsTimer() {
        IdleDebouncer d = new IdleDebouncer(300);
        d.onActivity(1000);
        assertFalse(d.checkSettled(1200));
        d.onActivity(1250);                 // more bytes arrived
        assertFalse(d.checkSettled(1400));  // 1400-1250 = 150 < 300
        assertTrue(d.checkSettled(1550));   // 1550-1250 = 300
    }

    @Test
    @DisplayName("a fresh burst after settling settles again")
    void burstAfterSettle_settlesAgain() {
        IdleDebouncer d = new IdleDebouncer(300);
        d.onActivity(1000);
        assertTrue(d.checkSettled(1300));
        d.onActivity(2000);
        assertFalse(d.checkSettled(2200));
        assertTrue(d.checkSettled(2300));
    }

    @Test
    @DisplayName("idleMillis must be at least 1")
    void idleMillis_mustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new IdleDebouncer(0));
    }
}
