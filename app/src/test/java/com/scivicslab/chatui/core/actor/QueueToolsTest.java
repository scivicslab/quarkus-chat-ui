package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.agent.QueueTools;
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
 * {@code list_queue}, {@code remove_queued_message} and {@code move_queued_message_to_front}: a ConversationSquad
 * actor reads and edits the queue of a conversation of its own project by {@code entryId}
 * ({@code AgentEditsAnotherQueue_261128_oo01}).
 */
class QueueToolsTest {

    /**
     * Says one scripted thing per prompt. When {@code hold} is set, every prompt waits for one permit of {@link #go},
     * so that the conversation stays busy and later prompts wait.
     */
    private static final class Fake implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<String> replies;
        final boolean hold;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final java.util.concurrent.Semaphore go = new java.util.concurrent.Semaphore(0);

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
            if (hold) {
                started.countDown();
                // Every prompt of a holding fake waits for one permit, so that a test sees which one started.
                try { go.tryAcquire(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
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
        iiar.tellNow(c -> ((ChatSession) c).setPromptQueueName(queue.getName())).join();
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

    private static final String REQ1 = "project1/chat-01:s1:t1:r1";
    private static final String REQ2 = "project1/chat-01:s1:t2:r1";

    private static void human(Squad squad, String text) {
        squad.queue().tellNow(q -> q.enqueue(text, null, "queue", e -> { }, squad.chat().asChatSessionRef(), "human")).join();
    }

    /** chat-02 runs "running now"; waiting: a typed message (entryId 2), an ask (3), an urgent ask (4). */
    private record Scene(IIActorSystem system, Fake asker, Fake worker, Squad two) {}

    private static Scene scene(String name) throws Exception {
        IIActorSystem system = new IIActorSystem(name);
        Fake asker = new Fake(false, "noted");
        Fake worker = new Fake(true, "ok");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);
        startRunning(two, worker, "running now");
        human(two, "typed by the person\nsecond line");
        AskChatTool.send(system, "project1", "01", "02", "do the first thing", REQ1);
        AskChatTool.sendUrgent(system, "project1", "01", "02", "status please", REQ2);
        return new Scene(system, asker, worker, two);
    }

    private static List<Long> entryIds(Squad squad) {
        return squad.queue().askNow(PromptQueue::snapshot).join().stream().map(PromptQueue.QueueEntry::entryId).toList();
    }

    @Test
    void listShowsTheWaitingMessagesInStartOrderWithEntryIdSenderRequestAndText() throws Exception {
        Scene s = scene("queue-tools-list");
        assertEquals("queue of project1/chat-02: a turn is running, 3 waiting\n"
                + "1. entryId=4 from=agent:ask_chat:project1/chat-01 request=" + REQ2 + " urgent=yes auto=yes: "
                + "[urgent request " + REQ2 + " from project1/chat-01] status please\n"
                + "2. entryId=2 from=human request=none urgent=no auto=yes: typed by the person second line\n"
                + "3. entryId=3 from=agent:ask_chat:project1/chat-01 request=" + REQ1 + " urgent=no auto=yes: "
                + "[request " + REQ1 + " from project1/chat-01] do the first thing",
                QueueTools.list(s.system(), "project1", "02"));
    }

    @Test
    void aLongTextIsCutAt200CharactersAndAnEmptyOwnQueueIsSaid() throws Exception {
        Scene s = scene("queue-tools-long");
        human(s.two(), "y".repeat(300));
        String list = QueueTools.list(s.system(), "project1", "02");
        assertTrue(list.endsWith("y".repeat(200) + "..."), list);
        assertFalse(list.contains("y".repeat(201)), list);
        assertEquals("queue of project1/chat-01: no turn is running, 0 waiting",
                QueueTools.list(s.system(), "project1", "01"), "a conversation's own queue may be read");
    }

    @Test
    void anEntryIdIsNeverUsedAgainAfterItsMessageLeft() throws Exception {
        Scene s = scene("queue-tools-never-reused");
        assertEquals(List.of(4L, 2L, 3L), entryIds(s.two()));
        assertTrue(QueueTools.remove(s.system(), "project1", "02", "3").startsWith("removed"));
        human(s.two(), "later one");
        assertEquals(List.of(4L, 2L, 5L), entryIds(s.two()), "the new message got 5, not the freed 3");
    }

    @Test
    void anEntryIdStillNamesTheSameMessageAfterTheFrontOneStarted() throws Exception {
        Scene s = scene("queue-tools-stale-position");
        // From the list read: message 3 was third. The running turn ends and the urgent message (4) starts, so 3 is second.
        s.worker().go.release();
        waitFor(() -> s.worker().prompts.size() >= 2, "the urgent message did not start: " + s.worker().prompts);
        assertEquals(2, s.worker().prompts.size(), "only the urgent message started: it holds the conversation");
        assertEquals(List.of(2L, 3L), entryIds(s.two()));
        assertTrue(QueueTools.remove(s.system(), "project1", "02", "3").startsWith("removed"));
        assertEquals(List.of(2L), entryIds(s.two()), "the message named 3 went, not the one now at the old position");
        assertTrue(QueueTools.remove(s.system(), "project1", "02", "4").startsWith("not found"),
                "a message that already started is not found, and nothing else is removed");
        assertEquals(List.of(2L), entryIds(s.two()));
    }

    @Test
    void removingAnAskChatRequestTellsItsSenderItEndedWithoutAReply() throws Exception {
        Scene s = scene("queue-tools-remove-tells-sender");
        String result = QueueTools.remove(s.system(), "project1", "02", "3");
        assertTrue(result.startsWith("removed: message 3 is out of the queue of 02"), result);
        assertEquals(List.of(4L, 2L), entryIds(s.two()));
        waitFor(() -> !s.asker().prompts.isEmpty(), "the sender was told nothing");
        String told = s.asker().prompts.get(0);
        assertTrue(told.contains("[request " + REQ1 + " to project1/chat-02 ended without a reply: "
                + "removed from the queue before it ran"), told);
    }

    @Test
    void removingAMessageNobodyAskedForTellsNoOne() throws Exception {
        Scene s = scene("queue-tools-remove-human");
        assertTrue(QueueTools.remove(s.system(), "project1", "02", "2").startsWith("removed"));
        Thread.sleep(300);
        assertEquals(List.of(), s.asker().prompts);
        assertEquals(List.of(4L, 3L), entryIds(s.two()));
    }

    @Test
    void moveToFrontPassesTheUrgentMessageAndLeavesTheOthersInOrder() throws Exception {
        Scene s = scene("queue-tools-move");
        String result = QueueTools.moveToFront(s.system(), "project1", "02", "2");
        assertEquals("moved: message 2 is first in the queue of 02 and starts after the running turn.", result);
        assertEquals(List.of(2L, 4L, 3L), entryIds(s.two()), "ahead of the urgent message 4; 4 and 3 keep their order");
        assertEquals("already at the front: message 2 starts next.", QueueTools.moveToFront(s.system(), "project1", "02", "2"));
        assertEquals("not found: no waiting message has that entryId (it may have started already). Call list_queue again.",
                QueueTools.moveToFront(s.system(), "project1", "02", "99"));
        assertEquals(1, s.worker().prompts.size(), "the running turn was not touched: " + s.worker().prompts);
        s.worker().go.release();
        waitFor(() -> s.worker().prompts.size() >= 2, "the moved message did not start next");
        assertTrue(s.worker().prompts.get(1).endsWith("typed by the person\nsecond line"), s.worker().prompts.get(1));
    }

    @Test
    void aConversationOfAnotherProjectIsRefusedAndNothingChanges() throws Exception {
        Scene s = scene("queue-tools-other-project");
        String refused = "error: not in this project: project2/02 belongs to another project, whose queue you may not edit";
        assertEquals(refused, QueueTools.list(s.system(), "project1", "project2/02"));
        assertEquals(refused, QueueTools.remove(s.system(), "project1", "project2/02", "2"));
        assertEquals(refused.replace("project2/02", "project2/chat-02"),
                QueueTools.moveToFront(s.system(), "project1", "project2/chat-02", "3"));
        assertEquals(List.of(4L, 2L, 3L), entryIds(s.two()));
        assertEquals("error: chat not found: 09", QueueTools.list(s.system(), "project1", "09"));
        assertEquals("error: entryId is a whole number, as list_queue shows it",
                QueueTools.remove(s.system(), "project1", "02", "abc"));
        assertEquals("error: chatId is required", QueueTools.list(s.system(), "project1", " "));
    }

    @Test
    void theThreeToolsAreInTheCatalogAndDoNotDriveAConversation() {
        for (String tool : List.of("list_queue", "remove_queued_message", "move_queued_message_to_front")) {
            assertTrue(com.scivicslab.chatui.agent.ConversationToolCatalog.builtIn(tool) != null, tool + " is in the catalog");
            assertFalse(com.scivicslab.chatui.agent.ConversationToolCatalog.conversationDrivingTools().contains(tool), tool);
        }
    }
}
