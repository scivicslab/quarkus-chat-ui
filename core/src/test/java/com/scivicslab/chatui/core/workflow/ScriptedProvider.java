package com.scivicslab.chatui.core.workflow;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A provider for tests: answers each prompt with the next scripted reply, streamed as one delta
 * followed by {@code result(busy=false)} exactly as the CLI and openai-compat providers do.
 */
class ScriptedProvider implements LlmProvider {
    final List<String> replies = new ArrayList<>();
    final List<String> prompts = new ArrayList<>();

    ScriptedProvider(String... scripted) {
        replies.addAll(List.of(scripted));
    }

    @Override public String id() { return "scripted"; }
    @Override public String displayName() { return "Scripted"; }
    @Override public List<ModelEntry> getAvailableModels() { return List.of(new ModelEntry("m", "chat", "local")); }
    @Override public String getCurrentModel() { return "m"; }
    @Override public void setModel(String model) { }
    @Override public void cancel() { }

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        prompts.add(prompt);
        String reply = replies.isEmpty() ? "" : replies.remove(0);
        emitter.accept(ChatEvent.delta(reply));
        emitter.accept(ChatEvent.result("sess", 0.0, 1L, "m", false));
    }
}
