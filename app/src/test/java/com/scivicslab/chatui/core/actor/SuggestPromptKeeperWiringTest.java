package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.suggestprompt.SuggestKeeperDoubles.Repo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the work frame's keeper stands in the actor tree and what drives it
 * ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}): a child of the conversation next to its timekeeper, a new
 * generation for a new conversation and at {@code /clear}, the fixed questions loaded at startup. No CDI container.
 */
class SuggestPromptKeeperWiringTest {

    private static final String SQUAD = "project1/chat-01";

    private static List<String> childrenOfTheSquad(ChatUiActorSystem system) {
        ActorNode root = system.getActorTree();
        ActorNode project = root.children().stream().filter(n -> "project1".equals(n.name())).findFirst().orElseThrow();
        ActorNode squad = project.children().stream().filter(n -> SQUAD.equals(n.name())).findFirst().orElseThrow();
        return squad.children().stream().map(ActorNode::name).toList();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("the condition did not come true in 10 seconds");
            Thread.sleep(20);
        }
    }

    @Test
    void theKeeperIsAChildOfTheConversationNextToItsTimekeeper() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.suggestRepository = new Repo();
        system.init();

        List<String> children = childrenOfTheSquad(system);

        assertTrue(children.contains(SQUAD + ".suggestprompt"), children.toString());
        assertTrue(children.contains(SQUAD + ".timekeeper"), children.toString());
    }

    @Test
    void withoutARepositoryThereIsNoKeeper() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertFalse(childrenOfTheSquad(system).contains(SQUAD + ".suggestprompt"));
    }

    @Test
    void theFixedQuestionsAreLoadedAtStartup() {
        Repo repo = new Repo();
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.suggestRepository = repo;
        system.init();

        assertEquals(16, repo.questions().size());
        assertEquals("common-1", repo.questions().get(0).questionId());
    }

    @Test
    void aNewConversationStartsEpochOneAndAClearStartsTheNext() throws Exception {
        Repo repo = new Repo();
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.suggestRepository = repo;
        system.init();
        waitUntil(() -> repo.maxEpoch(SQUAD) == 1);

        system.getChatSession("project1", "01").tell(a -> ((ChatSession) a).clearHistory()).get(10, TimeUnit.SECONDS);

        waitUntil(() -> repo.maxEpoch(SQUAD) == 2);
        assertTrue(repo.loadCurrent(SQUAD).orElseThrow().isEmpty());
        assertEquals(1, repo.frames.get(SQUAD).get(1).epoch(), "the first generation's row stays");
    }

    @Test
    void theFinishedTurnOfANewConversationIsZeroAndIsReadWithoutWaiting() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertEquals(0, system.getChatSession("project1", "01").finishedTurnDirect());
    }
}
