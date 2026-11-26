package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget a conversation gives a provider whose tool loop it runs: the system message names the step limit, a
 * warning comes once at 80% of the steps, at the step limit or the time limit the turn makes one last request that must
 * be answered in text, and its text is the turn's answer (production {@code chat-session-agent-loop.yaml}).
 */
class ChatSessionStepBudgetTest {

    /** A provider that takes a budget and answers each request from a script: the request's number from 1, and whether it must be text. */
    private static final class BudgetProvider implements LlmProvider {
        final List<String> prompts = new ArrayList<>();
        final List<Boolean> textOnly = new ArrayList<>();
        final boolean budget;
        private final BiFunction<Integer, Boolean, String> script;
        private final Runnable onRequest;

        BudgetProvider(boolean budget, BiFunction<Integer, Boolean, String> script, Runnable onRequest) {
            this.budget = budget;
            this.script = script;
            this.onRequest = onRequest;
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean takesStepBudget() { return budget; }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            textOnly.add(ctx.noToolCalls());
            onRequest.run();
            String reply = script.apply(prompts.size(), ctx.noToolCalls());
            if (reply != null && !reply.isEmpty()) emitter.accept(ChatEvent.delta(reply));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    /** A call to a different file each time, so that no run of identical calls ends the turn. */
    private static String call(int n) {
        return "<invoke name=\"read\">\n<parameter name=\"path\">no-such-file-" + n + "</parameter>\n</invoke>";
    }

    private record Outcome(BudgetProvider provider, String answer, String ending) {}

    private static Outcome runTurn(BudgetProvider provider, int stepLimit, int timeLimitMinutes, AtomicLong clock) {
        return runTurn(provider, stepLimit, timeLimitMinutes, clock, 0);
    }

    /** @param maxObservationChars a ceiling on one observation, or 0 to leave the default */
    private static Outcome runTurn(BudgetProvider provider, int stepLimit, int timeLimitMinutes, AtomicLong clock,
                                   int maxObservationChars) {
        IIActorSystem system = new IIActorSystem("chat-session-step-budget-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setStepLimit(stepLimit);
            c.setTimeLimitMinutes(timeLimitMinutes);
            if (clock != null) c.setClock(clock::get);
            if (maxObservationChars > 0) c.setMaxObservationChars(maxObservationChars);
        }).join();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        String[] result = new String[2];
        chatRef.tellNow(c -> {
            c.start("find something", null, event -> {}, chatRef, new CompletableFuture<>(), "key", false);
            c.runUntilEnd();
            result[0] = c.getCompletedResult("key");
            result[1] = c.getCompletedEnding("key");
        }).join();
        return new Outcome(provider, result[0], result[1]);
    }

    private static BudgetProvider alwaysCalls(String textAtTheCap) {
        return new BudgetProvider(true, (n, textOnly) -> textOnly ? textAtTheCap : call(n), () -> {});
    }

    @Test
    void theSystemMessageNamesTheStepLimitOfTheConversation() {
        ChatSession budget = new ChatSession(alwaysCalls("x"), Optional.empty());
        assertTrue(budget.systemMessageText().contains(
                "You may make at most 100 tool-call steps in a turn; answer in text before you run out."),
                budget.systemMessageText());
        budget.setStepLimit(7);
        assertTrue(budget.systemMessageText().contains("You may make at most 7 tool-call steps in a turn;"));

        ChatSession noBudget = new ChatSession(new BudgetProvider(false, (n, t) -> "", () -> {}), Optional.empty());
        assertFalse(noBudget.systemMessageText().contains("tool-call steps in a turn"),
                "a provider that runs its own tool loop is told nothing of a budget");
    }

    @Test
    void theWarningComesOnceWhenEightyPercentOfTheStepsAreUsedAndOnlyThen() {
        Outcome o = runTurn(alwaysCalls("Here is what I found."), 10, 0, null);

        List<Integer> warned = new ArrayList<>();
        for (int i = 0; i < o.provider().prompts.size(); i++) {
            if (o.provider().prompts.get(i).contains("steps used. Start wrapping up: answer in text soon.")) warned.add(i + 1);
        }
        assertEquals(List.of(9), warned, "after 8 of 10 steps, in the 9th request only");
        assertTrue(o.provider().prompts.get(8).contains("8 of 10 steps used. Start wrapping up: answer in text soon."));
    }

    @Test
    void atTheCapOneLastRequestMustBeAnsweredInTextAndItsTextIsTheAnswer() {
        Outcome o = runTurn(alwaysCalls("Found nothing; the files are missing."), 5, 0, null);

        assertEquals(6, o.provider().prompts.size(), "five steps, then the one request for an answer in text");
        assertEquals(List.of(false, false, false, false, false, true), o.provider().textOnly);
        assertTrue(o.provider().prompts.get(5).contains("You reached the step limit of 5; say what you found and what is left."),
                o.provider().prompts.get(5));
        assertEquals("Found nothing; the files are missing.", o.answer());
        assertEquals("[ended: step-limit; model calls: 6; tool calls: 5]", o.ending());
    }

    @Test
    void ifTheLastRequestReturnsNoTextTheTurnEndsWithTheErrorText() {
        Outcome o = runTurn(alwaysCalls(""), 5, 0, null);

        assertEquals(6, o.provider().prompts.size(), "an empty answer is not retried");
        assertEquals("The step limit of 5 was reached and the model gave no answer", o.answer());
        assertEquals("[ended: step-limit; model calls: 6; tool calls: 5]", o.ending());
    }

    @Test
    void aTurnThatAnswersBeforeTheLimitsEndsAnswered() {
        BudgetProvider provider = new BudgetProvider(true, (n, textOnly) -> n < 3 ? call(n) : "Done.", () -> {});
        Outcome o = runTurn(provider, 100, 10, null);

        assertEquals(3, provider.prompts.size());
        assertFalse(provider.textOnly.contains(true));
        assertEquals("Done.", o.answer());
        assertEquals("[ended: answered; model calls: 3; tool calls: 2]", o.ending());
    }

    @Test
    void theTimeLimitEndsTheTurnWithTimeLimitOnAnInjectedClock() {
        AtomicLong clock = new AtomicLong();
        // Each request takes four minutes of the injected clock.
        BudgetProvider provider = new BudgetProvider(true,
                (n, textOnly) -> textOnly ? "So far I read three files." : call(n), () -> clock.addAndGet(4 * 60_000L));
        Outcome o = runTurn(provider, 100, 10, clock);

        // Starts at 0; after three requests it is 12 minutes, past 10, so the fourth request is the last.
        assertEquals(4, provider.prompts.size());
        assertEquals(List.of(false, false, false, true), provider.textOnly);
        assertTrue(provider.prompts.get(3).contains("You reached the time limit of 10 minutes; say what you found and what is left."),
                provider.prompts.get(3));
        assertEquals("So far I read three files.", o.answer());
        assertEquals("[ended: time-limit; model calls: 4; tool calls: 3]", o.ending());
    }

    @Test
    void noTimeLimitMeansTheClockIsNotLookedAt() {
        AtomicLong clock = new AtomicLong();
        BudgetProvider provider = new BudgetProvider(true,
                (n, textOnly) -> n < 5 ? call(n) : "Done.", () -> clock.addAndGet(60 * 60_000L));
        Outcome o = runTurn(provider, 100, 0, clock);

        assertEquals(5, provider.prompts.size());
        assertFalse(provider.textOnly.contains(true));
        assertEquals("[ended: answered; model calls: 5; tool calls: 4]", o.ending());
    }

    @Test
    void aProviderWithoutABudgetIsLeftToItsWorkflowsLimitAndNeverWarned() {
        // The workflow's limit is 300 and the conversation's stepLimit is not used: 300 steps are made.
        BudgetProvider provider = new BudgetProvider(false, (n, textOnly) -> call(n), () -> {});
        Outcome o = runTurn(provider, 5, 0, null);

        assertEquals(300, provider.prompts.size());
        assertFalse(provider.textOnly.contains(true));
        assertTrue(provider.prompts.stream().noneMatch(p -> p.contains("Start wrapping up")));
        assertEquals("[ended: step-limit; model calls: 300; tool calls: 299]".substring(0, 21), o.ending().substring(0, 21));
    }

    @Test
    void theDefaultsAreOneHundredStepsAndTenMinutes() {
        assertEquals(100, ChatSession.DEFAULT_CONVERSATION_STEP_LIMIT);
        assertEquals(10, ChatSession.DEFAULT_TIME_LIMIT_MINUTES);
        ChatSession session = new ChatSession(alwaysCalls("x"), Optional.empty());
        assertEquals(100, session.getStepLimit());
        assertEquals(10, session.getTimeLimitMinutes());
    }

    @Test
    void timeLineGivesUsedAndLeftAndSwitchesToTheStrongWordingBelowTwentyPercentLeft() {
        long min = 60_000L;
        assertEquals("\n[time: 6.5 of 30 min used, 23.5 min left]", ChatSession.timeLine(390_000L, 30));
        assertEquals("\n[time: 0 of 30 min used, 30 min left]", ChatSession.timeLine(0, 30));
        // 24 of 30 minutes is exactly 20% left: not yet below it.
        assertEquals("\n[time: 24 of 30 min used, 6 min left]", ChatSession.timeLine(24 * min, 30));
        assertEquals("\n[time: 24.5 of 30 min used, 5.5 min left. Do not start long commands. Answer in text soon.]",
                ChatSession.timeLine(24 * min + 30_000L, 30));
        assertEquals("\n[time: 31 of 30 min used, 0 min left. Do not start long commands. Answer in text soon.]",
                ChatSession.timeLine(31 * min, 30));
        assertEquals("", ChatSession.timeLine(5 * min, 0), "no limit, no line");
    }

    @Test
    void everyObservationEndsWithTheTimeLineOnTheInjectedClockAndTheLineIsAbsentWithoutALimit() {
        AtomicLong clock = new AtomicLong();
        // Each request takes one minute of the injected clock; the limit is 10 minutes.
        BudgetProvider provider = new BudgetProvider(true,
                (n, textOnly) -> n < 4 ? call(n) : "Done.", () -> clock.addAndGet(60_000L));
        runTurn(provider, 100, 10, clock);
        assertTrue(provider.prompts.get(1).contains("[time: 1 of 10 min used, 9 min left]"), provider.prompts.get(1));
        assertTrue(provider.prompts.get(2).contains("[time: 2 of 10 min used, 8 min left]"), provider.prompts.get(2));

        AtomicLong clock2 = new AtomicLong();
        BudgetProvider unlimited = new BudgetProvider(true,
                (n, textOnly) -> n < 4 ? call(n) : "Done.", () -> clock2.addAndGet(60_000L));
        runTurn(unlimited, 100, 0, clock2);
        assertTrue(unlimited.prompts.stream().noneMatch(p -> p.contains("[time:")), "no limit, no line");
    }

    @Test
    void theStrongWordingComesWhenLessThanTwentyPercentIsLeft() {
        AtomicLong clock = new AtomicLong();
        BudgetProvider provider = new BudgetProvider(true,
                (n, textOnly) -> n < 4 ? call(n) : "Done.", () -> clock.addAndGet(3 * 60_000L));
        runTurn(provider, 100, 10, clock);
        // After the second request it is 6 minutes, after the third 9: 1 minute left of 10.
        assertTrue(provider.prompts.get(2).contains("[time: 6 of 10 min used, 4 min left]"), provider.prompts.get(2));
        assertTrue(provider.prompts.get(3).contains(
                "[time: 9 of 10 min used, 1 min left. Do not start long commands. Answer in text soon.]"), provider.prompts.get(3));
    }

    @Test
    void theTimeLineIsNotCutByTheTruncationOfTheObservation() {
        AtomicLong clock = new AtomicLong();
        BudgetProvider provider = new BudgetProvider(true,
                (n, textOnly) -> n < 3 ? call(n) : "Done.", () -> clock.addAndGet(60_000L));
        runTurn(provider, 100, 10, clock, 10);
        String second = provider.prompts.get(1);
        assertTrue(second.contains("characters elided"),
                "the observation itself was cut: " + second);
        assertTrue(second.stripTrailing().endsWith("[time: 1 of 10 min used, 9 min left]"), second);
    }

    @Test
    void theFinalRequestAtTheTimeLimitAsksForPlainText() {
        AtomicLong clock = new AtomicLong();
        BudgetProvider provider = new BudgetProvider(true,
                (n, textOnly) -> textOnly ? "Summary." : call(n), () -> clock.addAndGet(4 * 60_000L));
        runTurn(provider, 100, 10, clock);
        assertTrue(provider.prompts.get(3).contains(
                "You reached the time limit of 10 minutes; say what you found and what is left. Answer in plain text now."),
                provider.prompts.get(3));
    }

    @Test
    void ifTheFinalRequestReturnsNoTextTheLastTextTheModelWroteIsTheAnswer() {
        // The model wrote a line beside its first call, and then nothing, also at the final request.
        BudgetProvider provider = new BudgetProvider(true,
                (n, textOnly) -> n == 1 ? "I found the config file.\n" + call(n) : textOnly ? "" : call(n), () -> {});
        Outcome o = runTurn(provider, 5, 0, null);
        assertEquals("I found the config file.", o.answer());
        assertEquals("[ended: step-limit; model calls: 6; tool calls: 5]", o.ending());
    }

    @Test
    void ifTheFinalRequestReturnsNoTextAndTheModelWroteNoneTheErrorTextStays() {
        Outcome o = runTurn(alwaysCalls(""), 5, 0, null);
        assertEquals("The step limit of 5 was reached and the model gave no answer", o.answer());
    }
}
