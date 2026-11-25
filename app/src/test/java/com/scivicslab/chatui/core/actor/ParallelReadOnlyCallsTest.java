package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calls of one response run at the same time only when every one of them is to a read-only tool, and keep
 * their order where it shows: what is handed back to the model, the order the observations are announced, the count of
 * tool calls, the repeated-call guard, the cap of eight at once, and the cancel.
 */
class ParallelReadOnlyCallsTest {

    /** One call the fake model makes: the tool and the {@code q} argument. */
    private record Call(String tool, String q) {}

    /** A plugin tool that takes a while, and notes when it began and ended and how many ran at once. */
    private static final class SlowTool implements ConversationTool {
        private final String name;
        private final long millis;
        private final CountDownLatch hold;
        final AtomicInteger now;
        final AtomicInteger most;
        final List<String> ended;

        SlowTool(String name, long millis, CountDownLatch hold, AtomicInteger now, AtomicInteger most, List<String> ended) {
            this.name = name;
            this.millis = millis;
            this.hold = hold;
            this.now = now;
            this.most = most;
            this.ended = ended;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "- " + name + "(q): waits a while, then answers with its own name and q.\n"; }

        @Override
        public String execute(String argumentsJson) {
            String q = new org.json.JSONObject(argumentsJson).getString("q");
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            try {
                if (hold != null) hold.await(30, TimeUnit.SECONDS);
                else Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                now.decrementAndGet();
            }
            ended.add(name + ":" + q);
            return name + ":" + q;
        }
    }

    /** Answers its first request with the given calls, and the next one with text. */
    private static final class CallingProvider implements LlmProvider {
        final List<Call> calls;
        final AtomicInteger modelRequests = new AtomicInteger();
        final List<String> toolResults = new CopyOnWriteArrayList<>();

        /** The tools a response run in order calls through the provider, as the local LLM's calls go through the MCP server. */
        final java.util.Map<String, ConversationTool> tools = new java.util.HashMap<>();

        CallingProvider(List<Call> calls) { this.calls = calls; }

        @Override
        public String callTool(String name, String argumentsJson, com.scivicslab.chatui.core.provider.CancelScope scope) {
            ConversationTool tool = tools.get(name);
            return tool == null ? "error: no such tool" : tool.execute(argumentsJson);
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean callsToolsByDefinition() { return true; }
        @Override public void addToolResults(List<ToolCallResult> r) { r.forEach(x -> toolResults.add(x.content())); }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            if (modelRequests.incrementAndGet() == 1) {
                for (int i = 0; i < calls.size(); i++) {
                    emitter.accept(ChatEvent.toolCall("call-" + i, calls.get(i).tool(),
                            new org.json.JSONObject().put("q", calls.get(i).q()).toString()));
                }
                return;
            }
            emitter.accept(ChatEvent.delta("done"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static ChatSessionIIAR session(CallingProvider provider, List<ConversationTool> tools) {
        tools.forEach(t -> provider.tools.put(t.name(), t));
        IIActorSystem system = new IIActorSystem("parallel-read-only-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project9", "01");
            ((ChatSession) a).setPluginTools(tools);
        }).join();
        return iiar;
    }

    private static CompletableFuture<TurnReply> startTurn(ChatSessionIIAR iiar, List<String> announced) {
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        Consumer<ChatEvent> emitter = e -> {
            String text = String.valueOf(e.content());
            if (text.startsWith("Observation (")) announced.add(text.substring("Observation (".length(), text.indexOf(')')));
        };
        chatRef.tellNow(c -> {
            c.start("go", null, emitter, chatRef, done, "result-" + System.nanoTime(), false, List.of(), "screen", null);
            c.runUntilEnd();
        });
        return done;
    }

    private static final class Probe {
        final AtomicInteger now = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final List<String> ended = new CopyOnWriteArrayList<>();
        SlowTool tool(String name, long millis) { return new SlowTool(name, millis, null, now, most, ended); }
        SlowTool held(String name, CountDownLatch hold) { return new SlowTool(name, 0, hold, now, most, ended); }
    }

    @Test
    void allReadOnly_runAtTheSameTime_andTheResultsGoBackInCallOrderEvenWhenALaterCallEndsFirst() throws Exception {
        Probe probe = new Probe();
        CallingProvider provider = new CallingProvider(List.of(
                new Call("fetch", "a"), new Call("web_search", "b"), new Call("scholar_search", "c")));
        ChatSessionIIAR iiar = session(provider, List.of(probe.tool("fetch", 700), probe.tool("web_search", 350),
                probe.tool("scholar_search", 10)));
        try {
            List<String> announced = new CopyOnWriteArrayList<>();
            long start = System.nanoTime();
            TurnReply reply = startTurn(iiar, announced).get(20, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - start) / 1e9;

            assertEquals(List.of("scholar_search:c", "web_search:b", "fetch:a"), probe.ended, "the last call ended first");
            assertEquals(List.of("fetch:a", "web_search:b", "scholar_search:c"), provider.toolResults, "handed back in call order");
            assertEquals(List.of("fetch", "web_search", "scholar_search"), announced, "announced in call order");
            assertEquals(3, probe.most.get(), "all three ran at once");
            assertTrue(seconds < 1.3, "about the longest call, not the sum (1.06 s): " + seconds + " s");
            assertTrue(reply.ending().contains("tool calls: 3"), reply.ending());
        } finally {
            iiar.close();
        }
    }

    @Test
    void oneCallOutsideTheList_makesTheWholeResponseRunInOrder() throws Exception {
        Probe probe = new Probe();
        CallingProvider provider = new CallingProvider(List.of(
                new Call("fetch", "a"), new Call("not_listed", "b"), new Call("web_search", "c")));
        ChatSessionIIAR iiar = session(provider, List.of(probe.tool("fetch", 150), probe.tool("not_listed", 150),
                probe.tool("web_search", 150)));
        try {
            List<String> announced = new CopyOnWriteArrayList<>();
            TurnReply reply = startTurn(iiar, announced).get(20, TimeUnit.SECONDS);

            assertEquals(1, probe.most.get(), "one at a time, as before");
            assertEquals(List.of("fetch:a", "not_listed:b", "web_search:c"), probe.ended);
            assertEquals(List.of("fetch:a", "not_listed:b", "web_search:c"), provider.toolResults);
            assertTrue(reply.ending().contains("tool calls: 3"), reply.ending());
        } finally {
            iiar.close();
        }
    }

    @Test
    void twentyReadOnlyCalls_runEightAtOnce_andComeBackInCallOrder() throws Exception {
        Probe probe = new Probe();
        List<Call> calls = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(new Call("fetch", "u" + i));
            expected.add("fetch:u" + i);
        }
        CallingProvider provider = new CallingProvider(calls);
        ChatSessionIIAR iiar = session(provider, List.of(probe.tool("fetch", 150)));
        try {
            TurnReply reply = startTurn(iiar, new CopyOnWriteArrayList<>()).get(30, TimeUnit.SECONDS);

            assertEquals(8, probe.most.get(), "eight at once of twenty");
            assertEquals(expected, provider.toolResults, "call order");
            assertTrue(reply.ending().contains("tool calls: 20"), reply.ending());
        } finally {
            iiar.close();
        }
    }

    @Test
    void identicalReadOnlyCallsInOneResponseStillTripTheRepeatedCallGuard() throws Exception {
        Probe probe = new Probe();
        CallingProvider provider = new CallingProvider(List.of(
                new Call("fetch", "same"), new Call("fetch", "same"), new Call("fetch", "same")));
        ChatSessionIIAR iiar = session(provider, List.of(probe.tool("fetch", 50)));
        try {
            TurnReply reply = startTurn(iiar, new CopyOnWriteArrayList<>()).get(20, TimeUnit.SECONDS);

            assertTrue(reply.ending().contains("repeated"), reply.ending());
            assertTrue(reply.ending().contains("tool calls: 3"), reply.ending());
        } finally {
            iiar.close();
        }
    }

    @Test
    void aCancelEndsTheTurnWithinASecondWhileTheCallsAreRunning() throws Exception {
        Probe probe = new Probe();
        CountDownLatch hold = new CountDownLatch(1);
        CallingProvider provider = new CallingProvider(List.of(
                new Call("fetch", "a"), new Call("fetch", "b"), new Call("fetch", "c"), new Call("fetch", "d")));
        ChatSessionIIAR iiar = session(provider, List.of(probe.held("fetch", hold)));
        try {
            CompletableFuture<TurnReply> done = startTurn(iiar, new CopyOnWriteArrayList<>());
            for (int i = 0; i < 100 && probe.now.get() < 4; i++) Thread.sleep(50);
            assertEquals(4, probe.now.get(), "four calls are running");

            long cancelledAt = System.nanoTime();
            iiar.tellNow(a -> ((ChatSession) a).cancel());
            done.get(2, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - cancelledAt) / 1e9;

            assertTrue(seconds < 1.0, "the turn ended " + seconds + " s after the cancel");
            assertEquals(1, provider.modelRequests.get(), "no model request after the cancel");
        } finally {
            hold.countDown();
            iiar.close();
        }
    }
}
