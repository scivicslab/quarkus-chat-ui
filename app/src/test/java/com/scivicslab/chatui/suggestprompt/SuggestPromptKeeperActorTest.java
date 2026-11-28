package com.scivicslab.chatui.suggestprompt;

import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.FakeTurns;
import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Repo;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link SuggestPromptKeeper} relies on from the actor around it, pinned per send method
 * ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}, the test section): {@code tell} and {@code ask} are one at a
 * time and in order, the LLM call leaves the message loop free and its result comes back as a {@code tell}, and a closed
 * actor's {@code ask} does not answer, so a caller must be able to give up.
 */
class SuggestPromptKeeperActorTest {

    private static final String SQUAD = "project1/chat-02";

    private ActorSystem system;
    private final Repo repo = new Repo();
    private final FakeTurns turns = new FakeTurns();
    /** Counts how many LLM calls are inside {@code complete} at once, and the most there ever were. */
    private final AtomicInteger inCall = new AtomicInteger();
    private final AtomicInteger mostInCall = new AtomicInteger();
    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch holdTheCall = new CountDownLatch(0);

    @BeforeEach
    void start() {
        system = new ActorSystem("suggest-keeper-test", 2);
    }

    @AfterEach
    void stop() {
        system.terminate();
    }

    private ActorRef<SuggestPromptKeeper> actor() {
        SuggestPromptKeeper.Model model = prompt -> {
            int now = inCall.incrementAndGet();
            mostInCall.accumulateAndGet(now, Math::max);
            try {
                prompts.add(prompt);
                holdTheCall.await(10, TimeUnit.SECONDS);
                return SuggestKeeperDoubles.REPLY;
            } finally {
                inCall.decrementAndGet();
            }
        };
        SuggestPromptKeeper keeper = new SuggestPromptKeeper(SQUAD, repo, turns, model,
                call -> system.getManagedThreadPool().execute(call), () -> SuggestFrame.STANDALONE, System::currentTimeMillis);
        ActorRef<SuggestPromptKeeper> ref = system.actorOf("suggestprompt", keeper);
        ref.tell(k -> k.bind(ref::tell)).join();
        return ref;
    }

    // ---- tell ----

    @Test
    void tellDeliversInTheOrderSentAndOneAtATime() throws Exception {
        ActorRef<SuggestPromptKeeper> ref = actor();
        // restore, then a new generation, then another: the epoch each leaves is the order they ran in.
        ref.tell(SuggestPromptKeeper::restore);
        ref.tell(SuggestPromptKeeper::startNewFrame);
        ref.tell(SuggestPromptKeeper::startNewFrame);
        ref.tell(SuggestPromptKeeper::startNewFrame).join();

        assertEquals(3, ref.ask(k -> k.frame().epoch()).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2, 3), repo.saves.stream().map(SuggestFrame::epoch).toList());
    }

    // ---- ask ----

    @Test
    void askAnswersAfterTheTellsSentBeforeIt() throws Exception {
        ActorRef<SuggestPromptKeeper> ref = actor();
        ref.tell(SuggestPromptKeeper::startNewFrame);

        SuggestFrame seen = ref.ask(SuggestPromptKeeper::frame).get(5, TimeUnit.SECONDS);

        assertEquals(1, seen.epoch(), "the ask ran after the tell sent before it");
    }

    // ---- the call on the pool, the result back by tell ----

    @Test
    void theMessageLoopIsFreeWhileTheModelCallWaitsAndTheResultComesBackByTell() throws Exception {
        ActorRef<SuggestPromptKeeper> ref = actor();
        ref.tell(SuggestPromptKeeper::startNewFrame).join();
        turns.finish(2);
        holdTheCall = new CountDownLatch(1);

        ref.tell(SuggestPromptKeeper::bringUpToDate).join();
        waitUntil(() -> inCall.get() == 1);

        // The call is waiting, and the actor still answers.
        assertTrue(ref.ask(SuggestPromptKeeper::calling).get(5, TimeUnit.SECONDS));
        assertEquals(0, ref.ask(k -> k.frame().digestedTurn()).get(5, TimeUnit.SECONDS));

        holdTheCall.countDown();
        waitUntil(() -> ref.ask(k -> k.frame().digestedTurn()).join() == 2);
        assertFalse(ref.ask(SuggestPromptKeeper::calling).get(5, TimeUnit.SECONDS));
    }

    @Test
    void manyUpdatesAtOnceMakeOneCallAtATime() throws Exception {
        ActorRef<SuggestPromptKeeper> ref = actor();
        ref.tell(SuggestPromptKeeper::startNewFrame).join();
        for (int t = 1; t <= 8; t++) {
            turns.rows.add(new SuggestPatchPrompt.Message(t, false, "a".repeat(2900)));
        }
        turns.finished = 8;
        holdTheCall = new CountDownLatch(1);

        List<CompletableFuture<Void>> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) sent.add(ref.tell(SuggestPromptKeeper::bringUpToDate));
        CompletableFuture.allOf(sent.toArray(new CompletableFuture[0])).join();
        holdTheCall.countDown();
        waitUntil(() -> ref.ask(k -> k.frame().digestedTurn()).join() == 8);

        assertEquals(1, mostInCall.get(), "never two calls at once, however many updates came");
        assertTrue(prompts.size() >= 2, "the catch-up went on by itself: " + prompts.size());
    }

    // ---- tellNow and askNow ----

    @Test
    void tellNowDoesNotWaitInTheQueueSoTheKeeperIsNeverToldThatWay() throws Exception {
        ActorRef<SuggestPromptKeeper> ref = actor();
        ref.tell(SuggestPromptKeeper::startNewFrame).join();
        CountDownLatch inTheLoop = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ref.tell(k -> {   // occupies the message loop
            inTheLoop.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(inTheLoop.await(5, TimeUnit.SECONDS));

        CompletableFuture<Integer> queued = ref.ask(k -> k.frame().epoch());
        CompletableFuture<Integer> now = ref.askNow(k -> k.frame().epoch());

        assertEquals(1, now.get(5, TimeUnit.SECONDS), "askNow runs beside the occupied loop: it is not serialised");
        assertFalse(queued.isDone(), "the queued ask is still waiting behind the loop");
        release.countDown();
        assertEquals(1, queued.get(5, TimeUnit.SECONDS));
    }

    // ---- close ----

    @Test
    void anAskToAClosedActorDoesNotAnswerSoACallerMustBeAbleToGiveUp() throws Exception {
        ActorRef<SuggestPromptKeeper> ref = actor();
        ref.tell(SuggestPromptKeeper::startNewFrame).join();
        ref.close();

        CompletableFuture<SuggestFrame> late = ref.ask(SuggestPromptKeeper::frame);

        Exception ended = assertThrows(Exception.class, () -> late.get(1, TimeUnit.SECONDS));
        assertTrue(ended instanceof TimeoutException || ended instanceof ExecutionException,
                "the caller's own time limit (or a closed-actor failure) ends the wait: " + ended);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("the condition did not come true in 10 seconds");
            Thread.sleep(20);
        }
    }
}
