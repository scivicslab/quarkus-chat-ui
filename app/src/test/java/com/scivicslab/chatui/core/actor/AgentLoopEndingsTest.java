package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the three ways a turn can end on a limit or a repetition ({@code repeated-call}, {@code step-limit},
 * {@code time-limit}) do together with the rest of the loop: the I/O log's question and conversation entries, the
 * restore after a restart, the reply decision, the provider's history collapse, and the one request that must be
 * answered in text.
 */
class AgentLoopEndingsTest {

    private static final String QUESTION = "find the file";
    private static final String REQUEST = "project1/chat-02:s1:t1:r1";

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new CopyOnWriteArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 3L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
        List<String> contentsOf(String label) {
            return records.stream().filter(r -> r.label().equals(label)).map(Rec::content).toList();
        }
    }

    /** A provider that takes a budget, answers from a script, and notes how the conversation ended each turn in its history. */
    private static final class BudgetProvider implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<Boolean> textOnly = new CopyOnWriteArrayList<>();
        final AtomicReference<Boolean> collapsedWithoutAnswer = new AtomicReference<>();
        private final BiFunction<Integer, Boolean, String> script;
        private final Runnable onRequest;

        BudgetProvider(BiFunction<Integer, Boolean, String> script, Runnable onRequest) {
            this.script = script;
            this.onRequest = onRequest;
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean takesStepBudget() { return true; }

        @Override
        public void collapseTurn(String question, String answer, boolean endedWithoutAnswer) {
            collapsedWithoutAnswer.set(endedWithoutAnswer);
        }

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

    private record Run(BudgetProvider provider, CapturingIoLog log, String answer, String ending) {}

    private static final String SAME_CALL = "<invoke name=\"read\">\n<parameter name=\"path\">no-such-file</parameter>\n</invoke>";

    private static String distinctCall(int n) {
        return "<invoke name=\"read\">\n<parameter name=\"path\">no-such-file-" + n + "</parameter>\n</invoke>";
    }

    /** One turn on a fresh conversation; a {@code requestId} makes it the turn that answers a reply. */
    private static Run turn(BudgetProvider provider, int stepLimit, int timeLimit, AtomicLong clock, String requestId)
            throws Exception {
        CapturingIoLog log = new CapturingIoLog();
        IIActorSystem system = new IIActorSystem("agent-loop-endings-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), log, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", "02");
            c.setReminderDecisionEnabled(false);
            c.setStepLimit(stepLimit);
            c.setTimeLimitMinutes(timeLimit);
            if (clock != null) c.setClock(clock::get);
        }).join();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        String[] result = new String[2];
        chatRef.tellNow(c -> {
            if (requestId == null) {
                c.start(QUESTION, null, e -> {}, chatRef, new CompletableFuture<>(), "key", false);
            } else {
                c.start(QUESTION, null, e -> {}, chatRef, new CompletableFuture<>(), "key", false, List.of(),
                        ChatSession.ASK_CHAT_REPLY_SOURCE_PREFIX + "project1/chat-03", requestId);
            }
            c.runUntilEnd();
            result[0] = c.getCompletedResult("key");
            result[1] = c.getCompletedEnding("key");
        }).join();
        long deadline = System.currentTimeMillis() + 5_000;
        while (provider.collapsedWithoutAnswer.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
        system.terminate();
        return new Run(provider, log, result[0], result[1]);
    }

    /** The three endings, each as a turn that the model answers in text when it is asked to. */
    private static List<Run> theThreeEndings() throws Exception {
        List<Run> runs = new ArrayList<>();
        // repeated-call: the same call three times
        runs.add(turn(new BudgetProvider((n, text) -> text ? "Repeated; here is what I know." : SAME_CALL, () -> {}),
                100, 0, null, null));
        // step-limit: different calls up to the limit of 4
        runs.add(turn(new BudgetProvider((n, text) -> text ? "Out of steps; here is what I know." : distinctCall(n), () -> {}),
                4, 0, null, null));
        // time-limit: four minutes per request on an injected clock, limit of ten
        AtomicLong clock = new AtomicLong();
        runs.add(turn(new BudgetProvider((n, text) -> text ? "Out of time; here is what I know." : distinctCall(n),
                () -> clock.addAndGet(4 * 60_000L)), 100, 10, clock, null));
        return runs;
    }

    @Test
    void theEndingLineNamesEachWayATurnCanEndOnALimit() throws Exception {
        List<Run> runs = theThreeEndings();

        assertEquals("[ended: repeated-call; model calls: 4; tool calls: 3]", runs.get(0).ending());
        assertEquals("[ended: step-limit; model calls: 5; tool calls: 4]", runs.get(1).ending());
        assertEquals("[ended: time-limit; model calls: 4; tool calls: 3]", runs.get(2).ending());
    }

    @Test
    void aTurnThatEndsOnALimitIsRestoredWithItsQuestionOnceAndItsAnswerOnce(@TempDir Path dir) throws Exception {
        int n = 0;
        for (Run run : theThreeEndings()) {
            assertEquals(1, run.log().contentsOf("turn1/question").size(), "the question was written once, when the turn started");
            assertEquals(1, run.log().contentsOf("turn1/conversation").size(), "and the conversation entry once, at its end");

            // The records go into a real log in a temporary directory and are read back the way a restart reads them.
            IoLogStore store = new IoLogStore();
            store.useDatabaseAt(dir.resolve("ending-" + (n++)).toString());
            try {
                long session = store.ensureSession("project1/chat-02");
                for (CapturingIoLog.Rec r : run.log().records) store.record(session, "agent", r.label(), r.content());
                List<IoLogView.Turn> turns = IoLogView.over(store).conversation(session, 50);
                assertEquals(1, turns.size(), "one turn, not the question entry and the conversation entry: " + turns);
                assertEquals(QUESTION, turns.get(0).question());
                assertEquals(run.answer(), turns.get(0).answer());

                ChatSession restored = new ChatSession(run.provider(), Optional.empty());
                ChatUiActorSystem.restoreTurnInto(restored, turns.get(0));
                assertEquals(List.of("user", "assistant"),
                        restored.historySnapshot().stream().map(ChatSession.HistoryEntry::role).toList());
            } finally {
                store.store().close();
            }
        }
    }

    @Test
    void theProviderIsToldTheTurnEndedWithoutAnAnswerOfItsOwnOnlyForTheseEndings() throws Exception {
        for (Run run : theThreeEndings()) {
            assertEquals(true, run.provider().collapsedWithoutAnswer.get(), run.ending());
        }
        BudgetProvider answers = new BudgetProvider((n, text) -> "Done at once.", () -> {});
        Run plain = turn(answers, 100, 10, null, null);
        assertEquals("[ended: answered; model calls: 1; tool calls: 0]", plain.ending());
        assertEquals(false, plain.provider().collapsedWithoutAnswer.get());
    }

    @Test
    void aReplyThatTheTurnEndedOnALimitForIsLeftOpenNotAccepted() throws Exception {
        List<String> expected = List.of("repeated call", "step limit", "time limit");
        List<Run> runs = new ArrayList<>();
        runs.add(turn(new BudgetProvider((n, text) -> text ? "Repeated." : SAME_CALL, () -> {}), 100, 0, null, REQUEST));
        runs.add(turn(new BudgetProvider((n, text) -> text ? "Steps." : distinctCall(n), () -> {}), 4, 0, null, REQUEST));
        AtomicLong clock = new AtomicLong();
        runs.add(turn(new BudgetProvider((n, text) -> text ? "Time." : distinctCall(n), () -> clock.addAndGet(4 * 60_000L)),
                100, 10, clock, REQUEST));

        for (int i = 0; i < runs.size(); i++) {
            List<String> decisions = runs.get(i).log().contentsOf("turn1/reply-decision");
            assertEquals(1, decisions.size(), decisions.toString());
            assertTrue(decisions.get(0).startsWith("LEFT OPEN | " + REQUEST + " | "), decisions.get(0));
            assertTrue(decisions.get(0).contains(expected.get(i)), decisions.get(0));
        }
    }

    @Test
    void whenTheStepLimitAndARepeatedCallCoincideThereIsOneRequestForTextAndTheEndingIsStepLimit() throws Exception {
        // The same call every step and a limit of 3: the third call is both the third identical one and the last step.
        Run run = turn(new BudgetProvider((n, text) -> text ? "Stuck." : SAME_CALL, () -> {}), 3, 0, null, null);

        assertEquals(4, run.provider().prompts.size());
        assertEquals(List.of(false, false, false, true), run.provider().textOnly, "exactly one request carries no tool choice");
        assertEquals("[ended: step-limit; model calls: 4; tool calls: 3]", run.ending());
        assertEquals("Stuck.", run.answer());
    }

    @Test
    void whenTheTimeLimitAndARepeatedCallCoincideThereIsOneRequestForTextAndTheEndingIsTimeLimit() throws Exception {
        AtomicLong clock = new AtomicLong();
        // Four minutes a request, the same call each time: after three requests it is 12 minutes and three identical calls.
        Run run = turn(new BudgetProvider((n, text) -> text ? "Stuck." : SAME_CALL, () -> clock.addAndGet(4 * 60_000L)),
                100, 10, clock, null);

        assertEquals(List.of(false, false, false, true), run.provider().textOnly);
        assertEquals("[ended: time-limit; model calls: 4; tool calls: 3]", run.ending());
    }

    @Test
    void theWarningRequestIsAnOrdinaryRequestThatKeepsItsToolChoice() throws Exception {
        Run run = turn(new BudgetProvider((n, text) -> text ? "Done." : distinctCall(n), () -> {}), 10, 0, null, null);

        int warned = -1;
        for (int i = 0; i < run.provider().prompts.size(); i++) {
            if (run.provider().prompts.get(i).contains("Start wrapping up")) warned = i;
        }
        assertEquals(8, warned, "the 9th request carries the warning");
        assertEquals(false, run.provider().textOnly.get(warned), "and still lets the model call a tool");
        assertEquals(1, run.provider().textOnly.stream().filter(b -> b).count());
        assertEquals(true, run.provider().textOnly.get(run.provider().textOnly.size() - 1));
    }
}
