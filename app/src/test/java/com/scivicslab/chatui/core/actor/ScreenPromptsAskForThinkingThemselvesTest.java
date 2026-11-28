package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.chatui.plugin.ProviderCreationContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A message typed on the screen carries its own answer to "think or not" (Shift+Enter or the Send button, and Ctrl+Shift+Enter): the
 * conversation's no-think setting, which a workflow can set, does not apply to it. A message from anywhere else still
 * follows the setting.
 */
class ScreenPromptsAskForThinkingThemselvesTest {

    /** What each turn's provider was asked: {@code ProviderContext.noThink()}. */
    static final List<Boolean> ASKED = new CopyOnWriteArrayList<>();

    static final class RecordingFactory implements LlmProviderFactory {
        @Override public String kind() { return "recording"; }
        @Override public List<ProviderChoice> choices() { return List.of(new ProviderChoice("recording", ToolSet.FULL, "Recording")); }
        @Override public LlmProvider create(ProviderCreationContext ctx) {
            return new LlmProvider() {
                @Override public String id() { return "recording"; }
                @Override public String displayName() { return "Recording"; }
                @Override public List<ModelEntry> getAvailableModels() { return List.of(new ModelEntry("m", id(), null)); }
                @Override public String getCurrentModel() { return "m"; }
                @Override public void setModel(String m) { }
                @Override public void cancel() { }
                @Override public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext c) {
                    ASKED.add(c.noThink());
                    emitter.accept(ChatEvent.delta("noted"));
                    emitter.accept(ChatEvent.result(null, 0, 0));
                }
            };
        }
    }

    private static void send(ChatUiActorSystem system, String source, boolean noThink) throws Exception {
        ChatSessionIIAR session = system.getChatSession("project1", "01");
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        system.getPromptQueue("project1", "01").tell(q -> q.enqueue("hello", null, "queue", e -> { },
                session.asChatSessionRef(), source, null, done, noThink, true, List.of(), null, false)).get(10, TimeUnit.SECONDS);
        done.get(20, TimeUnit.SECONDS);
    }

    @Test
    void aScreenMessageDecidesItselfAndAnOtherSourceFollowsTheConversationSetting(@TempDir Path dir) throws Exception {
        ASKED.clear();
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("think"))) {
            ChatUiActorSystem system = systems.start(s -> s.extraProviderFactories.add(new RecordingFactory()));
            system.setProvider("project1", "01", "recording", ToolSet.FULL);

            // The old toggle left the conversation set to answer without thinking.
            assertTrue(system.setNoThink("project1", "01", true));
            send(system, "screen", false);                    // Ctrl+Shift+Enter: thinks, whatever the setting says
            send(system, "screen", true);                     // Send: never thinks
            // The setting is off: plain Send still does not think, and a workflow's message follows the setting.
            assertTrue(system.setNoThink("project1", "01", false));
            send(system, "screen", true);
            send(system, "workflow", false);
            assertTrue(system.setNoThink("project1", "01", true));
            send(system, "workflow", false);

            assertEquals(new ArrayList<>(List.of(false, true, true, false, true)), new ArrayList<>(ASKED),
                    "the thinking each turn's provider was asked for");
        }
    }
}
