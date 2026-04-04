package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.rest.ChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WatchdogActorTest {

    private WatchdogActor watchdog;
    private List<ChatEvent> emitted;

    @BeforeEach
    void setUp() {
        // Use a very short threshold so tests don't need to wait 90s
        watchdog = new WatchdogActor(Duration.ofMillis(10));
        emitted = new ArrayList<>();
        watchdog.setSseEmitter(emitted::add);
    }

    @Test
    @DisplayName("initially not active and not stall-notified")
    void initialState_notActive() {
        assertFalse(watchdog.isPromptActive());
        assertFalse(watchdog.isStallNotified());
    }

    @Test
    @DisplayName("onPromptStarted activates the watchdog")
    void onPromptStarted_becomesActive() {
        watchdog.onPromptStarted();
        assertTrue(watchdog.isPromptActive());
        assertFalse(watchdog.isStallNotified());
    }

    @Test
    @DisplayName("onPromptFinished deactivates the watchdog")
    void onPromptFinished_becomesInactive() {
        watchdog.onPromptStarted();
        watchdog.onPromptFinished();
        assertFalse(watchdog.isPromptActive());
        assertFalse(watchdog.isStallNotified());
    }

    @Test
    @DisplayName("tick emits stall warning after threshold exceeded")
    void tick_afterThreshold_emitsStallWarning() throws InterruptedException {
        watchdog.onPromptStarted();
        Thread.sleep(20); // exceed the 10ms threshold
        watchdog.tick(null); // chatActor not needed for this test path
        assertEquals(1, emitted.size());
        assertEquals("info", emitted.get(0).type());
        assertTrue(emitted.get(0).content().contains("not responded"));
        assertTrue(watchdog.isStallNotified());
    }

    @Test
    @DisplayName("tick does not emit when prompt is not active")
    void tick_noPrompt_noEmit() {
        watchdog.tick(null);
        assertTrue(emitted.isEmpty());
    }

    @Test
    @DisplayName("tick does not emit twice for the same stall")
    void tick_onlyEmitsStallOnce() throws InterruptedException {
        watchdog.onPromptStarted();
        Thread.sleep(20);
        watchdog.tick(null);
        watchdog.tick(null);
        assertEquals(1, emitted.size());
    }

    @Test
    @DisplayName("onActivity resets stall and emits resumed message")
    void onActivity_afterStall_emitsResumed() throws InterruptedException {
        watchdog.onPromptStarted();
        Thread.sleep(20);
        watchdog.tick(null); // triggers stall
        assertEquals(1, emitted.size());
        assertTrue(watchdog.isStallNotified());

        watchdog.onActivity(); // resume
        assertEquals(2, emitted.size());
        assertTrue(emitted.get(1).content().contains("resumed"));
        assertFalse(watchdog.isStallNotified());
    }

    @Test
    @DisplayName("onActivity before stall does not emit")
    void onActivity_beforeStall_noEmit() {
        watchdog.onPromptStarted();
        watchdog.onActivity();
        assertTrue(emitted.isEmpty());
    }

    @Test
    @DisplayName("tick does not emit when active but within threshold")
    void tick_withinThreshold_noEmit() {
        watchdog.onPromptStarted();
        watchdog.tick(null); // called immediately, well within threshold
        assertTrue(emitted.isEmpty());
        assertFalse(watchdog.isStallNotified());
    }

    @Test
    @DisplayName("default stall threshold is 90 seconds")
    void defaultThreshold_is90Seconds() {
        WatchdogActor defaultWatchdog = new WatchdogActor();
        assertEquals(Duration.ofSeconds(90), defaultWatchdog.getStallThreshold());
    }

    @Test
    @DisplayName("clearSseEmitter prevents emission")
    void clearSseEmitter_preventsEmission() throws InterruptedException {
        watchdog.onPromptStarted();
        watchdog.clearSseEmitter();
        Thread.sleep(20);
        watchdog.tick(null);
        assertTrue(emitted.isEmpty()); // emitter was cleared
        assertTrue(watchdog.isStallNotified()); // still marks stall internally
    }
}
