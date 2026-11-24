package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import com.scivicslab.chatui.core.iolog.IoLogView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conversation's step limit and time limit are settings of the conversation, kept in its settings record beside the
 * model and the Think box, so a restart puts them back. A record written before they were kept restores to the
 * defaults, 100 steps and 10 minutes.
 */
class StepAndTimeLimitsSurviveRestartTest {

    @Test
    void aNewConversationHasTheDefaults(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("limits-default"))) {
            ChatUiActorSystem system = systems.start();
            assertEquals("02", system.createChat("project1"));
            ChatSessionIIAR chat = system.getChatSession("project1", "02");
            assertEquals(100, chat.getStepLimitDirect());
            assertEquals(10, chat.getTimeLimitMinutesDirect());
        }
    }

    @Test
    void theLimitsSetOnAConversationComeBackAfterARestart(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("limits-restart"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            assertEquals(true, first.setStepLimit("project1", "02", 42));
            assertEquals(true, first.setTimeLimitMinutes("project1", "02", 0));
            assertEquals(42, first.getChatSession("project1", "02").getStepLimitDirect());
            assertEquals(0, first.getChatSession("project1", "02").getTimeLimitMinutesDirect());

            ChatUiActorSystem second = systems.start();
            ChatSessionIIAR back = second.getChatSession("project1", "02");
            assertEquals(42, back.getStepLimitDirect(), "the step limit it was set to");
            assertEquals(0, back.getTimeLimitMinutesDirect(), "no time limit, as set");
        }
    }

    @Test
    void aRecordWrittenBeforeTheLimitsWereKeptRestoresToTheDefaults(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("limits-old"))) {
            systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "01"));
            long session = systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "02"));
            systems.log.record(session, "agent", IoLogView.SETTINGS_LABEL,
                    "{\"provider\":null,\"tools\":null,\"model\":null,\"temperature\":null,"
                            + "\"urgentWaitMinutes\":10,\"childReplyWarnMinutes\":5,\"noThink\":true}");

            ChatUiActorSystem system = systems.start();
            ChatSessionIIAR back = system.getChatSession("project1", "02");
            assertEquals(100, back.getStepLimitDirect());
            assertEquals(10, back.getTimeLimitMinutesDirect());
        }
    }

    @Test
    void aLimitThatIsOutOfRangeIsRefusedAndTheOldOneKept(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("limits-range"))) {
            ChatUiActorSystem system = systems.start();
            assertEquals("02", system.createChat("project1"));
            ChatSessionIIAR chat = system.getChatSession("project1", "02");
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> system.setStepLimit("project1", "02", 0));
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> system.setTimeLimitMinutes("project1", "02", -1));
            assertEquals(100, chat.getStepLimitDirect());
            assertEquals(10, chat.getTimeLimitMinutesDirect());
            assertFalse(system.setStepLimit("project1", "99", 5), "no such conversation");
        }
    }

    @Test
    void theRestoreStepLeavesAMissingOrInvalidValueAtItsDefault() {
        ChatSession session = new ChatSession(new NoProvider(), java.util.Optional.empty());
        ChatUiActorSystem.restoreLimits(session, null, null);
        assertEquals(100, session.getStepLimit());
        assertEquals(10, session.getTimeLimitMinutes());
        ChatUiActorSystem.restoreLimits(session, 0, -5);
        assertEquals(100, session.getStepLimit());
        assertEquals(10, session.getTimeLimitMinutes());
        ChatUiActorSystem.restoreLimits(session, 7, 0);
        assertEquals(7, session.getStepLimit());
        assertEquals(0, session.getTimeLimitMinutes());
    }

    private static final class NoProvider implements com.scivicslab.chatui.core.provider.LlmProvider {
        @Override public String id() { return "none"; }
        @Override public String displayName() { return "None"; }
        @Override public java.util.List<ModelEntry> getAvailableModels() { return java.util.List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String model) { }
        @Override public void cancel() { }
        @Override public void sendPrompt(String prompt, String model, java.util.function.Consumer<com.scivicslab.chatui.core.rest.ChatEvent> emitter,
                                         com.scivicslab.chatui.core.provider.ProviderContext ctx) { }
    }
}
