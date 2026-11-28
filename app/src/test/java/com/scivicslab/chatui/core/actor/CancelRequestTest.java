package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cancel_request}: a ConversationSquad actor takes back a request it sent with {@code ask_chat}
 * while the request still waits in the other actor's queue ({@code CancelARequestInTheQueue_261113_oo01}).
 */
class CancelRequestTest {

    /**
     * Says one scripted thing per prompt. When {@code hold} is set, its first prompt does not finish until
     * {@link #release} is counted down, so that the conversation stays busy and later prompts wait.
     */
    private static final class Fake implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<String> replies;
        final boolean hold;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Fake(boolean hold, String... replies) {
            this.hold = hold;
            this.replies = List.of(replies);
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        volatile boolean cancelled;
        final java.util.concurrent.atomic.AtomicInteger cancelCalls = new java.util.concurrent.atomic.AtomicInteger();
        /** Like a real provider: a cancel ends the call that is held, without a result. */
        @Override public void cancel() {
            cancelCalls.incrementAndGet();
            cancelled = true;
            release.countDown();
        }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            if (hold && started.getCount() > 0) {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            if (cancelled) return;
            emitter.accept(ChatEvent.delta(replies.get(Math.min(prompts.size() - 1, replies.size() - 1))));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    /** A conversation of the test system, with its queue. */
    private record Squad(ChatSessionIIAR chat, ActorRef<PromptQueue> queue) {}

    private static Squad squad(IIActorSystem system, String chatId, Fake provider) {
        ChatSessionIIAR iiar = new ChatSessionIIAR("project1/chat-" + chatId + ".chat", provider,
                Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", chatId);
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild("project1/chat-" + chatId + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        return new Squad(iiar, queue);
    }

    /** Puts a prompt typed by a person into the squad's queue, so that the held provider starts running it. */
    private static void startRunning(Squad squad, Fake provider, String text) throws Exception {
        squad.queue().tellNow(q -> q.enqueue(text, null, "queue", e -> { }, squad.chat().asChatSessionRef(),
                "human")).join();
        assertTrue(provider.started.await(10, TimeUnit.SECONDS), "the running prompt did not start");
    }

    private static List<String> waiting(Squad squad) {
        return squad.queue().askNow(PromptQueue::snapshot).join().stream()
                .map(PromptQueue.QueueEntry::prompt).toList();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), what);
    }

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("</invoke>").toString();
    }

    private static final String ONE = "project1/chat-01";

    @Test
    void cancelTakesAWaitingRequestOutOfTheTargetsQueueAndNoReplyComes() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-waiting");
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(true, "ok");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);
        startRunning(two, worker, "running");

        String request = "project1/chat-01:s1:t1:r1";
        String sent = AskChatTool.send(system, "project1", "01", "02", "do the thing", request);
        assertTrue(sent.startsWith("sent to project1/chat-02"), sent);
        assertEquals(1, waiting(two).size(), "the request waits behind the running prompt");

        String result = AskChatTool.cancel(system, ONE, request);
        assertTrue(result.startsWith("removed"), result);
        assertEquals(List.of(), waiting(two), "the request is out of the queue");

        worker.release.countDown();
        Thread.sleep(500);
        assertEquals(1, worker.prompts.size(), "the worker ran only the first prompt: " + worker.prompts);
        assertTrue(worker.prompts.get(0).endsWith("running"), "and never saw the cancelled request");
        assertEquals(List.of(), asker.prompts, "no reply, and no 'ended without a reply' message, comes back");
    }

    @Test
    void cancelStopsTheTurnThatIsRunningForTheRequestAndTheSenderIsToldItEnded() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-running");
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(true, "the answer");
        squad(system, "01", asker);
        squad(system, "02", worker);

        String request = "project1/chat-01:s1:t1:r1";
        AskChatTool.send(system, "project1", "01", "02", "do the thing", request);
        assertTrue(worker.started.await(10, TimeUnit.SECONDS), "the request did not start");

        String result = AskChatTool.cancel(system, ONE, request);
        assertTrue(result.startsWith(AskChatTool.CANCELLED_RUNNING), result);
        assertTrue(result.contains("ended without a reply"), "the result says whether a reply still comes: " + result);
        assertEquals(1, worker.cancelCalls.get(), "the provider of the running turn was told to cancel");

        waitFor(() -> !asker.prompts.isEmpty(), "the sender was not told the turn ended");
        String told = asker.prompts.get(0);
        assertTrue(told.contains("[request " + request + " to project1/chat-02 ended without a reply: "), told);
        assertTrue(told.contains("its turn was cancelled"), told);
        assertEquals(1, asker.prompts.stream().filter(p -> p.contains("ended without a reply")).count(),
                "one message that the turn ended");
        assertTrue(asker.prompts.stream().noneMatch(p -> p.startsWith("[reply to request")), "and no reply");
    }

    @Test
    void theMessageQueuedBehindACancelledTurnStartsAsUsual() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-next-starts");
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(true, "next one done");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);

        String request = "project1/chat-01:s1:t1:r1";
        AskChatTool.send(system, "project1", "01", "02", "do the thing", request);
        assertTrue(worker.started.await(10, TimeUnit.SECONDS), "the request did not start");
        two.queue().tellNow(q -> q.enqueue("the next message", null, "queue", e -> { },
                two.chat().asChatSessionRef(), "human")).join();
        assertEquals(1, waiting(two).size(), "the next message waits behind the running turn");

        assertTrue(AskChatTool.cancel(system, ONE, request).startsWith(AskChatTool.CANCELLED_RUNNING));

        waitFor(() -> worker.prompts.stream().anyMatch(p -> p.endsWith("the next message")),
                "the next message did not start: " + worker.prompts.size() + " prompts");
        assertEquals(List.of(), waiting(two), "the queue is empty again");
    }

    @Test
    void onlyTheSenderCanStopTheRunningTurnAndAnAnsweredRequestStopsNothing() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-running-owner");
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(true, "the answer");
        squad(system, "01", asker);
        squad(system, "02", worker);
        squad(system, "03", new Fake(false, "noted"));

        String request = "project1/chat-01:s1:t1:r1";
        AskChatTool.send(system, "project1", "01", "02", "do the thing", request);
        assertTrue(worker.started.await(10, TimeUnit.SECONDS), "the request did not start");

        String byAnother = AskChatTool.cancel(system, "project1/chat-03", request);
        assertTrue(byAnother.startsWith("not found"), byAnother);
        assertEquals(0, worker.cancelCalls.get(), "a request someone else sent does not stop the turn");
        worker.release.countDown();
        waitFor(() -> !asker.prompts.isEmpty(), "the reply did not arrive");
        assertTrue(asker.prompts.get(0).contains("[reply to request " + request), asker.prompts.get(0));
    }

    @Test
    void anAnsweredRequestDoesNotStopAnUnrelatedTurnThatRunsNow() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-answered-other-turn");
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(false, "the answer");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);

        String request = "project1/chat-01:s1:t1:r1";
        AskChatTool.send(system, "project1", "01", "02", "do the thing", request);
        waitFor(() -> !asker.prompts.isEmpty(), "the reply did not arrive");

        Fake slow = new Fake(true, "slow");
        Squad three = squad(system, "03", slow);
        startRunning(three, slow, "a person's long job");
        assertTrue(AskChatTool.cancel(system, ONE, request).startsWith("already answered"));
        assertEquals(0, slow.cancelCalls.get(), "a turn for no request of the sender is left alone");
        assertEquals(0, worker.cancelCalls.get());
        slow.release.countDown();
    }

    /** A conversation takes back a request of its own with the tool while the turn runs, as the LLM would call it. */
    @Test
    void theCancelToolStopsARunningTurnWhenAConversationCallsIt() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-tool-running");
        Fake one = new Fake(false, "thanks");
        Fake two = new Fake(false, invoke("ask_chat", "chatId", "03", "prompt", "side job"),
                invoke("cancel_request", "requestId", "project1/chat-02:t1:r1"), "final report");
        Fake three = new Fake(true, "never");
        squad(system, "01", one);
        squad(system, "02", two);
        squad(system, "03", three);

        AskChatTool.send(system, "project1", "01", "02", "do the work", "project1/chat-01:s1:t1:r1");

        assertTrue(three.started.await(10, TimeUnit.SECONDS), "chat-03 did not start the side job");
        waitFor(() -> two.prompts.size() >= 3, "chat-02 did not get the result of the tool: " + two.prompts);
        assertEquals(1, three.cancelCalls.get(), "the cancel tool stopped chat-03's running turn");
        assertTrue(two.prompts.get(2).contains(AskChatTool.CANCELLED_RUNNING), two.prompts.get(2));
        waitFor(() -> !one.prompts.isEmpty(), "chat-01 never got its reply: " + two.prompts);
        assertTrue(one.prompts.get(0).contains("final report"), one.prompts.get(0));
    }

    @Test
    void cancelSaysAnsweredWhenTheReplyHasComeBack() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-answered");
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(false, "the answer");
        squad(system, "01", asker);
        squad(system, "02", worker);

        String request = "project1/chat-01:s1:t1:r1";
        AskChatTool.send(system, "project1", "01", "02", "do the thing", request);
        waitFor(() -> !asker.prompts.isEmpty(), "the reply did not arrive");

        String result = AskChatTool.cancel(system, ONE, request);
        assertTrue(result.startsWith("already answered"), result);
    }

    @Test
    void cancelFindsNothingForAnUnknownIdOrARequestSomeoneElseSent() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-not-found");
        Fake worker = new Fake(true, "ok");
        squad(system, "01", new Fake(false, "noted"));
        Squad two = squad(system, "02", worker);
        squad(system, "03", new Fake(false, "noted"));
        startRunning(two, worker, "running");

        String request = "project1/chat-01:s1:t1:r1";
        AskChatTool.send(system, "project1", "01", "02", "do the thing", request);

        assertTrue(AskChatTool.cancel(system, ONE, "project1/chat-01:s9:t9:r9").startsWith("not found"));
        String byAnother = AskChatTool.cancel(system, "project1/chat-03", request);
        assertTrue(byAnother.startsWith("not found"), "only the sender may cancel: " + byAnother);
        assertEquals(1, waiting(two).size(), "the request is still waiting");
        worker.release.countDown();
    }

    /**
     * chat-02 works for a request from chat-01, sends a request on to chat-03, and takes it back with the
     * {@code cancel_request} tool. The request it sent no longer holds chat-01's request open, so chat-01
     * gets its reply when chat-02's turn ends.
     */
    @Test
    void theCancelToolClosesTheSendersRecordSoTheRequestItWorksForIsAnswered() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-record");
        Fake one = new Fake(false, "thanks");
        Fake two = new Fake(false, invoke("ask_chat", "chatId", "03", "prompt", "side job"),
                invoke("cancel_request", "requestId", "project1/chat-02:t1:r1"), "final report");
        Fake busy = new Fake(true, "ok");
        squad(system, "01", one);
        squad(system, "02", two);
        Squad three = squad(system, "03", busy);
        startRunning(three, busy, "running");

        AskChatTool.send(system, "project1", "01", "02", "do the work", "project1/chat-01:s1:t1:r1");

        waitFor(() -> !one.prompts.isEmpty(), "chat-01 never got its reply: the cancelled request still holds it open. "
                + "chat-02 saw " + two.prompts);
        assertTrue(one.prompts.get(0).contains("final report"), one.prompts.get(0));
        assertTrue(two.prompts.get(2).contains("removed"), "the tool said what it did: " + two.prompts.get(2));
        assertEquals(List.of(), waiting(three), "the side job is out of chat-03's queue");
        busy.release.countDown();
        assertFalse(busy.prompts.contains("side job"));
    }

    @Test
    void whenAPersonRemovesAQueuedRequestTheSenderIsToldItWasRemovedBeforeItRan() throws Exception {
        IIActorSystem system = new IIActorSystem("cancel-request-person-removes");
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(true, "ok");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);
        startRunning(two, worker, "running");

        String request = "project1/chat-01:s1:t1:r1";
        AskChatTool.send(system, "project1", "01", "02", "do the thing", request);
        assertTrue(two.queue().askNow(q -> q.removeAt(0)).join());

        waitFor(() -> !asker.prompts.isEmpty(), "the sender was not told");
        String told = asker.prompts.get(0);
        assertTrue(told.contains("[request " + request + " to project1/chat-02 ended without a reply: "), told);
        assertTrue(told.contains("removed from the queue before it ran"), told);
        worker.release.countDown();
    }
}
