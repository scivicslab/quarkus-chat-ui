package com.scivicslab.chatui.tmux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OutputWatcher} glue: activity + idle → capture + extract.
 * The capture source is a fixture supplier, so no tmux is involved.
 */
@Tag("TmuxTuiDriver_Watcher_260630_oo01")
@DisplayName("OutputWatcher — activity/idle to extracted events")
class OutputWatcherTest {

    private static String fixture(String name) {
        try (InputStream in = OutputWatcherTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, "fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private OutputWatcher watcher(int idleMillis) {
        return new OutputWatcher(() -> fixture("settled_hello.txt"),
                new ScreenExtractor(), new IdleDebouncer(idleMillis));
    }

    @Test
    @DisplayName("reports not-settled and no events while the stream is still active")
    void tick_beforeIdle_notSettled() {
        OutputWatcher w = watcher(300);
        w.recordActivity(1000);

        TickResult r = w.tick(1100);   // 100ms < 300ms idle

        assertFalse(r.settled());
        assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("on idle, captures and returns the extracted assistant message")
    void tick_afterIdle_capturesAndReturns() {
        OutputWatcher w = watcher(300);
        w.recordActivity(1000);

        TickResult r = w.tick(1300);   // idle reached → capture + extract

        assertTrue(r.settled());
        assertEquals(1, r.events().size());
        assertInstanceOf(AssistantMessage.class, r.events().get(0));
        assertEquals("hello from claude", ((AssistantMessage) r.events().get(0)).text());
    }

    @Test
    @DisplayName("does not settle again on later ticks without new activity")
    void tick_settledOnce_noRepeat() {
        OutputWatcher w = watcher(300);
        w.recordActivity(1000);

        assertTrue(w.tick(1300).settled());
        assertFalse(w.tick(1400).settled());
        assertFalse(w.tick(2000).settled());
    }
}
