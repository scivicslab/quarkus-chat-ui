package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The timekeeper is the clock of the conversation's Suggest prompt work frame
 * ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}): it sends one update each time the fixed interval has passed, and
 * nothing when the interval is 0 or less. Its other checks read the conversation, which these cases do not give it; the
 * timekeeper logs that and goes on, as it does when a conversation fails to answer.
 */
class TimekeeperSuggestPromptTest {

    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final long T0 = 1_000_000L;

    private final AtomicInteger sent = new AtomicInteger();

    private Timekeeper keeperEvery(long intervalMillis) {
        Timekeeper k = new Timekeeper("project1/chat-01", null, null, e -> { }, Timekeeper.OFF, Timekeeper.OFF);
        k.suggestPromptEvery(intervalMillis, T0, sent::incrementAndGet);
        return k;
    }

    @Test
    void sendsOnceWhenTheIntervalHasPassed() {
        Timekeeper k = keeperEvery(10 * MINUTE);

        k.tick(T0 + 10 * MINUTE);

        assertEquals(1, sent.get());
    }

    @Test
    void sendsNothingBeforeTheIntervalHasPassed() {
        Timekeeper k = keeperEvery(10 * MINUTE);

        k.tick(T0);
        k.tick(T0 + 9 * MINUTE + 59_000);

        assertEquals(0, sent.get());
    }

    @Test
    void doesNotSendAgainUntilTheNextInterval() {
        Timekeeper k = keeperEvery(10 * MINUTE);

        k.tick(T0 + 10 * MINUTE);
        k.tick(T0 + 10 * MINUTE + 30_000);   // the next half-minute check
        k.tick(T0 + 19 * MINUTE);
        assertEquals(1, sent.get());

        k.tick(T0 + 20 * MINUTE + 30_000);
        assertEquals(2, sent.get());
    }

    @Test
    void sendsNothingWhenTheIntervalIsZeroOrLess() {
        Timekeeper zero = keeperEvery(0);
        Timekeeper negative = keeperEvery(-5 * MINUTE);

        zero.tick(T0 + 1000 * MINUTE);
        negative.tick(T0 + 1000 * MINUTE);

        assertEquals(0, sent.get());
    }

    @Test
    void aTimekeeperThatWasNeverGivenAnIntervalSendsNothing() {
        Timekeeper k = new Timekeeper("project1/chat-01", null, null, e -> { }, Timekeeper.OFF, Timekeeper.OFF);

        k.tick(T0 + 1000 * MINUTE);

        assertEquals(0, sent.get());
    }

    @Test
    void aSendThatFailsDoesNotStopTheCheck() {
        Timekeeper k = new Timekeeper("project1/chat-01", null, null, e -> { }, Timekeeper.OFF, Timekeeper.OFF);
        k.suggestPromptEvery(MINUTE, T0, () -> {
            sent.incrementAndGet();
            throw new IllegalStateException("the keeper is gone");
        });

        k.tick(T0 + MINUTE);
        k.tick(T0 + 2 * MINUTE);

        assertEquals(2, sent.get(), "the next interval tries again");
    }
}
