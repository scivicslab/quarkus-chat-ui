package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The question of a turn is in the I/O log from the moment the turn starts, before its first step. */
class QuestionRecordedAtTurnStartTest {

    private static final class CapturingIoLog extends IoLogStore {
        final List<String> labels = new ArrayList<>();
        final List<String> contents = new ArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 3L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            labels.add(label);
            contents.add(content);
        }
    }

    /** Answers at once, and notes what the log held when the model was first called. */
    private static final class NotingProvider implements LlmProvider {
        final List<String> labelsAtTheCall = new ArrayList<>();
        private final CapturingIoLog ioLog;
        NotingProvider(CapturingIoLog ioLog) { this.ioLog = ioLog; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            if (labelsAtTheCall.isEmpty()) labelsAtTheCall.addAll(ioLog.labels);
            emitter.accept(ChatEvent.delta("the answer"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void theQuestionIsWrittenBeforeTheFirstStepAndTheConversationEntryKeepsItsShape() {
        CapturingIoLog ioLog = new CapturingIoLog();
        NotingProvider provider = new NotingProvider(ioLog);
        IIActorSystem system = new IIActorSystem("question-at-turn-start-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project1", "01");
        }).join();

        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("what is in the box?", null, event -> {}, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();

        assertTrue(provider.labelsAtTheCall.contains("turn1/question"),
                "the question was in the log when the model was first called: " + provider.labelsAtTheCall);
        int question = ioLog.labels.indexOf("turn1/question");
        assertTrue(question >= 0 && question < ioLog.labels.indexOf("turn1/step1/llm"), ioLog.labels.toString());
        assertEquals("QUESTION:\nwhat is in the box?", ioLog.contents.get(question));
        int conversation = ioLog.labels.indexOf("turn1/conversation");
        assertEquals("QUESTION:\nwhat is in the box?\n\nANSWER:\nthe answer", ioLog.contents.get(conversation));
        assertEquals(1, ioLog.labels.stream().filter("turn1/question"::equals).count());
    }

    @Test
    void anUnfinishedTurnRestoresAsItsUserLineOnly() {
        ChatSession session = new ChatSession(new NotingProvider(new CapturingIoLog()), Optional.empty());

        ChatUiActorSystem.restoreTurnInto(session, new IoLogView.Turn("never finished", null, null));
        ChatUiActorSystem.restoreTurnInto(session, new IoLogView.Turn("finished", "done"));

        List<ChatSession.HistoryEntry> history = session.historySnapshot();
        assertEquals(List.of("user", "user", "assistant"), history.stream().map(ChatSession.HistoryEntry::role).toList());
        assertEquals("never finished", history.get(0).content());
        assertEquals("done", history.get(2).content());
    }
}
