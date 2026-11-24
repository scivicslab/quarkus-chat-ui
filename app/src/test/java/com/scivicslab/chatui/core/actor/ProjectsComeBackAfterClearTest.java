package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.scivicslab.chatui.core.iolog.IoLogStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project is what its own session says, not what its conversations' sessions say: clearing every conversation of a
 * project ends their sessions, and the project must still be there after a restart. A project that was deleted must
 * not come back, whatever era of this code deleted it.
 */
class ProjectsComeBackAfterClearTest {

    private static String chat(String projectId) {
        return ChatUiActorSystem.chatActorName(projectId, "01");
    }

    @Test
    void aProjectWhoseConversationWasClearedComesBackWithItsConversation(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("cleared"))) {
            ChatUiActorSystem first = systems.start();
            String projectId = first.createProject();
            assertTrue(systems.log.ensureSession(chat(projectId)) >= 0);
            systems.log.resetSession(chat(projectId));            // what /clear does
            assertFalse(systems.log.resumableConversationSquads().contains(chat(projectId)));

            ChatUiActorSystem second = systems.start();

            assertTrue(second.getProjectIds().contains(projectId), "projects after restart: " + second.getProjectIds());
            assertNotNull(second.getChatSession(projectId, "01"), "its conversation is back, empty");
        }
    }

    @Test
    void aProjectThatNeverWroteAnythingComesBack(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("silent"))) {
            String projectId = systems.start().createProject();

            ChatUiActorSystem second = systems.start();

            assertTrue(second.getProjectIds().contains(projectId));
            assertNotNull(second.getChatSession(projectId, "01"));
        }
    }

    @Test
    void aClearedProjectKeepsItsNumberAndItsOtherConversationsAreBackToo(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("two"))) {
            ChatUiActorSystem first = systems.start();
            String projectId = first.createProject();
            first.createChat(projectId);                          // chat-02
            systems.log.ensureSession(chat(projectId));
            systems.log.resetSession(chat(projectId));
            systems.log.resetSession(ChatUiActorSystem.chatActorName(projectId, "02"));

            ChatUiActorSystem second = systems.start();

            assertNotNull(second.getChatSession(projectId, "01"));
            assertNotNull(second.getChatSession(projectId, "02"));
            assertFalse(second.createProject().equals(projectId));
        }
    }

    @Test
    void aDeletedProjectEndsItsOwnSessionAndStaysGone(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("deleted"))) {
            ChatUiActorSystem first = systems.start();
            String projectId = first.createProject();
            assertTrue(systems.log.findProjectSession(projectId) >= 0, "the project has an open session of its own");

            assertEquals(ChatUiActorSystem.ProjectDeletion.Kind.DELETED, first.deleteProject(projectId).kind());

            assertEquals(-1, systems.log.findProjectSession(projectId), "the project's session is ended");
            ChatUiActorSystem second = systems.start();
            assertFalse(second.getProjectIds().contains(projectId));
            assertFalse(second.createProject().equals(projectId), "its name stays taken");
        }
    }

    @Test
    void aProjectDeletedBeforeThisChangeStaysGone(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("old-deleted"))) {
            IoLogStore log = systems.log;
            // The state the earlier code left: the project's session still open, its list of conversations emptied.
            ChatUiActorSystem first = systems.start();
            String listed = first.createProject();
            String legacy = first.createProject();
            first.deleteProject(listed);
            first.deleteProject(legacy);
            long a = log.ensureProjectSession(listed);            // reopened as the old code left it
            long b = log.ensureProjectSession(legacy);
            assertTrue(a >= 0 && b >= 0 && log.findProjectSession(listed) == a);
            // listed: the empty list entry the earlier delete wrote; legacy: no list entry at all, only a name.
            log.record(a, "agent", Project.CONVERSATIONS_LABEL, "{\"conversations\":[]}");
            log.record(b, "agent", "settings", "{\"name\":\"gone\",\"workingDir\":null}");

            ChatUiActorSystem second = systems.start();

            assertFalse(second.getProjectIds().contains(listed), "empty list = deleted");
            assertFalse(second.getProjectIds().contains(legacy), "no list entry: never reopened from the project session");
        }
    }
}
