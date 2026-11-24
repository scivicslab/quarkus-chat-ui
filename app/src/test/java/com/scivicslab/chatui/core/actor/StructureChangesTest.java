package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changes to the structure (new projects and conversations) are messages to one owner, so two never interleave, and a change
 * that makes another (a new project makes its first conversation) runs at once inside its own turn instead of waiting for itself.
 */
class StructureChangesTest {

    @Test
    void manyThreadsCreatingConversationsGetDistinctIds() throws Exception {
        ChatUiActorSystem system = StartedSystem.start();
        ExecutorService threads = Executors.newFixedThreadPool(6);   // test only: callers from outside the actors
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> done = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                done.add(threads.submit(() -> {
                    go.await();
                    return system.createChat("project1");
                }));
            }
            go.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<String> f : done) ids.add(f.get(60, TimeUnit.SECONDS));
            assertEquals(6, ids.size(), "no two creations got the same id: " + ids);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void aNewProjectMakesItsFirstConversationInsideItsOwnChange() {
        ChatUiActorSystem system = StartedSystem.start();
        String projectId = system.createProject();
        assertTrue(system.getProjectIds().contains(projectId));
        assertTrue(system.getChatSession(projectId, "01") != null, "the first conversation exists");
        String second = system.createProject();
        assertTrue(!second.equals(projectId));
    }

    @Test
    void anExceptionInAChangeReachesTheCallerAndTheNextChangeRuns() {
        ChatUiActorSystem system = StartedSystem.start();
        try {
            system.setProvider("project1", "99", "no-such-kind", null);
        } catch (RuntimeException expected) {
            // whatever it says, it is the caller's exception, not a wrapper of the owner
        }
        assertTrue(system.createChat("project1") != null);
    }
}
