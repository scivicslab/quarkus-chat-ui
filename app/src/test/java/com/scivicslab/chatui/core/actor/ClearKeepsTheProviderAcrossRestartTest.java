package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.scivicslab.chatui.agent.ToolSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /clear} ends the conversation's I/O-log session and the next write opens a new one. The provider, the model
 * and the other settings are recorded in the session, so a new session that holds none put the conversation back on the
 * default provider at the next restart (a conversation on Claude Code showed "local LLM" after a restart).
 */
class ClearKeepsTheProviderAcrossRestartTest {

    private static final java.util.function.Consumer<ChatUiActorSystem> WITH_FAKE =
            s -> s.extraProviderFactories.add(new NoThinkAndEffortSurviveRestartTest.EffortKeepingFactory());

    @Test
    void aClearedConversationStaysOnItsProviderAfterARestart(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("clear-provider"))) {
            ChatUiActorSystem first = systems.start(WITH_FAKE);
            first.setProvider("project1", "01", "fake", ToolSet.FULL);
            assertEquals("fake", first.getChatSession("project1", "01").getProviderIdDirect());

            first.getChatSession("project1", "01").tell(a -> ((ChatSession) a).clearHistory()).get(10, TimeUnit.SECONDS);
            // The first thing written after a clear opens the conversation's new session, as a turn's first line does.
            systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "01"));

            ChatUiActorSystem second = systems.start(WITH_FAKE);

            assertEquals("fake", second.getChatSession("project1", "01").getProviderIdDirect(),
                    "the cleared conversation is still on the provider it was on");
        }
    }
}
