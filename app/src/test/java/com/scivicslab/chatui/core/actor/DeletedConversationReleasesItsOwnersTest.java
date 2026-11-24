package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.logging.RecentEntriesAccumulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deleting a conversation or a project stops what only it used: its log buffer's owner and its calc evaluator's JShell (a child JVM). */
class DeletedConversationReleasesItsOwnersTest {

    @Test
    void deletingAConversationClosesItsLogBufferAndItsJShell() throws Exception {
        ChatUiActorSystem system = StartedSystem.start();
        String chatId = system.createChat("project1");
        ChatSession session = system.getChatSession("project1", chatId).<ChatSession>ask(a -> (ChatSession) a)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        ConversationToolRunner.Calculator calc = session.calculatorForTest();
        assertEquals("2", calc.evaluate("1+1").strip());
        assertTrue(calc.isOpen(), "calc started a JShell");
        RecentEntriesAccumulator buffer = system.chatLogBufferForTest("project1", chatId);
        buffer.add("test", "INFO", "a line");
        assertEquals(1, buffer.recent().size());

        system.deleteChat("project1", chatId);

        assertFalse(calc.isOpen(), "the JShell child JVM was closed with the conversation");
        assertTrue(buffer.recent().isEmpty(), "the log buffer's owner was stopped with the conversation");
    }

    @Test
    void deletingAProjectClosesTheLogBuffersOfItsConversations() throws Exception {
        ChatUiActorSystem system = StartedSystem.start();
        String projectId = system.createProject();
        RecentEntriesAccumulator buffer = system.chatLogBufferForTest(projectId, "01");
        buffer.add("test", "INFO", "a line");
        assertEquals(1, buffer.recent().size());

        system.deleteProject(projectId);

        assertTrue(buffer.recent().isEmpty(), "the buffer's owner was stopped with the project");
    }
}
