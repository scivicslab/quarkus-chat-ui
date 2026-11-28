package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.agent.ConversationToolCatalog;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ask_chat} and {@code ask_chat_urgent} declare {@code think} each time: the request is queued with it, and
 * the receiving conversation's own no-think setting does not apply. No declaration means no think
 * ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}).
 */
class AskChatNoThinkTest {

    /** Says one scripted thing per prompt, and records the {@code noThink} each prompt was sent with. */
    private static final class Fake implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<Boolean> noThinks = new CopyOnWriteArrayList<>();
        final List<String> replies;

        Fake(String... replies) { this.replies = List.of(replies); }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            noThinks.add(ctx.noThink());
            emitter.accept(ChatEvent.delta(replies.get(Math.min(prompts.size() - 1, replies.size() - 1))));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

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
        return new Squad(iiar, queue);
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

    /**
     * The worker's noThink for the request, after the sender's turn called the tool with these arguments. The worker's
     * own conversation setting is {@code receiverNoThink}.
     */
    private static boolean workerNoThink(String name, boolean receiverNoThink, String tool, String... extra) throws Exception {
        IIActorSystem system = new IIActorSystem(name);
        String[] args = new String[4 + extra.length];
        args[0] = "chatId"; args[1] = "02"; args[2] = "prompt"; args[3] = "short question";
        System.arraycopy(extra, 0, args, 4, extra.length);
        Fake sender = new Fake(invoke(tool, args), "sent");
        Fake worker = new Fake("the answer");
        Squad one = squad(system, "01", sender);
        Squad two = squad(system, "02", worker);
        two.chat().tellNow(a -> ((ChatSession) a).setNoThink(receiverNoThink)).join();
        one.queue().tellNow(q -> q.enqueue("go", null, "queue", e -> { }, one.chat().asChatSessionRef(), "human")).join();
        waitFor(() -> !worker.noThinks.isEmpty(), "the worker did not receive the request");
        assertTrue(worker.prompts.get(0).contains("short question"), worker.prompts.get(0));
        return worker.noThinks.get(0);
    }

    /** The declaration alone decides, whatever the receiver's own no-think setting is. */
    @Test
    void thinkTrueThinksWhateverTheReceiverSettingIs() throws Exception {
        for (String tool : List.of("ask_chat", "ask_chat_urgent")) {
            assertEquals(false, workerNoThink("think-t-off-" + tool, false, tool, "think", "true"));
            assertEquals(false, workerNoThink("think-t-on-" + tool, true, tool, "think", "true"));
        }
    }

    @Test
    void thinkFalseDoesNotThinkWhateverTheReceiverSettingIs() throws Exception {
        for (String tool : List.of("ask_chat", "ask_chat_urgent")) {
            assertEquals(true, workerNoThink("think-f-off-" + tool, false, tool, "think", "false"));
            assertEquals(true, workerNoThink("think-f-on-" + tool, true, tool, "think", "false"));
        }
    }

    @Test
    void noDeclarationMeansNoThinkWhateverTheReceiverSettingIs() throws Exception {
        for (String tool : List.of("ask_chat", "ask_chat_urgent")) {
            assertEquals(true, workerNoThink("think-o-off-" + tool, false, tool));
            assertEquals(true, workerNoThink("think-o-on-" + tool, true, tool));
        }
    }

    /** An older caller that still passes noThink is read as think = !noThink; think wins when both come. */
    @Test
    void theOldNoThinkArgumentIsReadAsTheOppositeOfThink() throws Exception {
        assertEquals(true, workerNoThink("old-true", false, "ask_chat", "noThink", "true"));
        assertEquals(false, workerNoThink("old-false", true, "ask_chat", "noThink", "false"));
        assertEquals(false, workerNoThink("both-think-wins", true, "ask_chat", "noThink", "true", "think", "true"));
        assertEquals(true, workerNoThink("both-think-wins-2", false, "ask_chat_urgent", "noThink", "false", "think", "false"));
    }

    /** Only a request from another conversation ignores the setting: other sources still honour it. */
    @Test
    void aMessageFromAnotherSourceStillFollowsTheConversationSetting() throws Exception {
        IIActorSystem system = new IIActorSystem("other-source");
        Fake worker = new Fake("ok");
        Squad two = squad(system, "02", worker);
        two.chat().tellNow(a -> ((ChatSession) a).setNoThink(true)).join();
        two.queue().tellNow(q -> q.enqueue("from a workflow", null, "queue", e -> { },
                two.chat().asChatSessionRef(), "workflow")).join();
        waitFor(() -> !worker.noThinks.isEmpty(), "the worker did not receive the prompt");
        assertEquals(true, worker.noThinks.get(0));
        two.chat().tellNow(a -> ((ChatSession) a).setNoThink(false)).join();
        two.queue().tellNow(q -> q.enqueue("from a workflow again", null, "queue", e -> { },
                two.chat().asChatSessionRef(), "workflow")).join();
        waitFor(() -> worker.noThinks.size() >= 2, "the second prompt did not arrive");
        assertEquals(false, worker.noThinks.get(1));
    }

    @Test
    void declaredNoThinkReadsThinkFirstThenNoThinkThenNoThink() {
        assertEquals(true, AskChatTool.declaredNoThink(new org.json.JSONObject()));
        assertEquals(false, AskChatTool.declaredNoThink(new org.json.JSONObject("{\"think\":true}")));
        assertEquals(true, AskChatTool.declaredNoThink(new org.json.JSONObject("{\"think\":false}")));
        assertEquals(false, AskChatTool.declaredNoThink(new org.json.JSONObject("{\"noThink\":false}")));
        assertEquals(true, AskChatTool.declaredNoThink(new org.json.JSONObject("{\"noThink\":true}")));
        assertEquals(false, AskChatTool.declaredNoThink(new org.json.JSONObject("{\"noThink\":true,\"think\":true}")));
    }

    @Test
    void bothToolsDeclareAnOptionalBooleanThink() {
        for (String tool : List.of("ask_chat", "ask_chat_urgent")) {
            org.json.JSONObject schema = ConversationToolCatalog.builtIn(tool).inputSchema();
            assertEquals("boolean", schema.getJSONObject("properties").getJSONObject("think").getString("type"));
            assertTrue(!schema.getJSONArray("required").toList().contains("think"), "think is optional");
        }
        String description = ConversationToolCatalog.builtIn("ask_chat").description();
        assertTrue(description.contains("\"think\"") && description.contains("leaving \"think\" out"), description);
    }
}
