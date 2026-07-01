package com.scivicslab.chatui.tmux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link QuiescenceTracker} debounce logic. Pure state machine,
 * no tmux, so this is a unit test.
 */
@Tag("TmuxTuiDriver_Quiescence_260630_oo01")
@DisplayName("QuiescenceTracker — output settle debounce")
class QuiescenceTrackerTest {

    @Test
    @DisplayName("the first sample is a baseline and never settles")
    void firstSample_isBaseline() {
        QuiescenceTracker t = new QuiescenceTracker(2);
        assertFalse(t.update("A"));
    }

    @Test
    @DisplayName("an unchanging idle screen never settles")
    void idleScreen_neverSettles() {
        QuiescenceTracker t = new QuiescenceTracker(2);
        t.update("idle");
        assertFalse(t.update("idle"));
        assertFalse(t.update("idle"));
        assertFalse(t.update("idle"));
    }

    @Test
    @DisplayName("after a change, settles once after settleTicks unchanged polls")
    void afterChange_settlesOnceAfterSettleTicks() {
        QuiescenceTracker t = new QuiescenceTracker(2);
        assertFalse(t.update("A"));   // baseline
        assertFalse(t.update("B"));   // change -> busy
        assertFalse(t.update("B"));   // unchanged 1
        assertTrue(t.update("B"));    // unchanged 2 -> settle
        assertFalse(t.update("B"));   // already settled, no repeat
    }

    @Test
    @DisplayName("a fresh change after settling settles again")
    void changeAfterSettle_settlesAgain() {
        QuiescenceTracker t = new QuiescenceTracker(2);
        t.update("A");
        t.update("B");
        t.update("B");
        assertTrue(t.update("B"));    // first settle
        assertFalse(t.update("C"));   // change -> busy again
        assertFalse(t.update("C"));   // unchanged 1
        assertTrue(t.update("C"));    // settle again
    }

    @Test
    @DisplayName("settleTicks of 1 settles on the first unchanged poll after a change")
    void settleTicksOne_settlesImmediately() {
        QuiescenceTracker t = new QuiescenceTracker(1);
        t.update("A");
        assertFalse(t.update("B"));   // change
        assertTrue(t.update("B"));    // 1 unchanged -> settle
    }

    @Test
    @DisplayName("settleTicks must be at least 1")
    void settleTicks_mustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new QuiescenceTracker(0));
    }
}
