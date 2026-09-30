package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;
import com.scivicslab.turingworkflow.workflow.accumulator.ConsoleAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 tests: the {@code harness} actor's generic actions driven by a real Interpreter over
 * a scripted provider. Each provider turn ends with {@code result(busy=false)} exactly as the CLI
 * and openai-compat providers do; the harness must forward it without the busy flag.
 */
class ClaudeHarnessActorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Answers each prompt with the next scripted reply, streamed as one delta plus a busy=false result. */
    static class ScriptedProvider implements LlmProvider {
        final List<String> replies = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        @Override public String id() { return "scripted"; }
        @Override public String displayName() { return "Scripted"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(new ModelEntry("m", "chat", "local")); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String model) { }
        @Override public void cancel() { }
        @Override public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            String reply = replies.isEmpty() ? "" : replies.remove(0);
            emitter.accept(ChatEvent.delta(reply));
            emitter.accept(ChatEvent.result("sess", 0.0, 1L, "m", false));
        }
    }

    private static List<ChatEvent> run(String yaml, ScriptedProvider provider) {
        List<ChatEvent> events = new ArrayList<>();
        IIActorSystem system = new IIActorSystem("test-harness");
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("interpreter").team(system).build();
            system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
            MultiplexerAccumulator mux = new MultiplexerAccumulator();
            mux.addTarget(new ConsoleAccumulator());
            system.addIIActor(new MultiplexerAccumulatorIIAR("log", mux, system));
            system.addIIActor(new VarsActor(system, new HashMap<>()));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
            interpreter.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);
            system.addIIActor(new ClaudeHarnessActor("harness", provider, events::add, null, system, MAPPER, "{}", null));
            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            ActionResult result = interpreter.runUntilEnd(100);
            assertTrue(result.isSuccess(), "workflow should end: " + result.getResult());
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
        return events;
    }

    @Test
    @DisplayName("a turn's result is forwarded without busy=false, so the browser stays busy until the run ends")
    void turnResultIsForwardedWithoutBusy() {
        ScriptedProvider p = new ScriptedProvider();
        p.replies.add("first");
        p.replies.add("second");
        String yaml = """
                name: t
                steps:
                  - states: ["0", "1"]
                    actions:
                      - actor: harness
                        method: send
                        arguments: "do one"
                  - states: ["1", "end"]
                    actions:
                      - actor: harness
                        method: send
                        arguments: "do two"
                """;
        List<ChatEvent> events = run(yaml, p);
        List<ChatEvent> results = events.stream().filter(e -> "result".equals(e.type())).toList();
        assertEquals(2, results.size(), "one result per turn is still forwarded (it closes the bubble)");
        for (ChatEvent r : results) {
            assertNull(r.busy(), "the turn's busy=false must not reach the browser");
            assertEquals("sess", r.sessionId());
        }
        assertEquals(2, events.stream().filter(e -> "delta".equals(e.type())).count());
        assertEquals(List.of("do one", "do two"), p.prompts);
    }

    @Test
    @DisplayName("check: YES takes the first transition, NO falls through to the next one")
    void checkGatesByFirstLine() {
        String yaml = """
                name: t
                steps:
                  - states: ["0", "yes"]
                    actions:
                      - actor: harness
                        method: check
                        arguments: "Is it ready?"
                  - states: ["0", "no"]
                    actions:
                      - actor: out
                        method: print
                        arguments: {message: "not ready"}
                  - states: ["yes", "end"]
                    actions:
                      - actor: harness
                        method: send
                        arguments: "go"
                  - states: ["no", "end"]
                    actions:
                      - actor: out
                        method: print
                        arguments: {message: "wait"}
                """;
        ScriptedProvider yes = new ScriptedProvider();
        yes.replies.add("YES\nbecause");
        yes.replies.add("done");
        run(yaml, yes);
        assertEquals(2, yes.prompts.size(), "YES: the check turn and then the action turn");
        assertTrue(yes.prompts.get(0).startsWith("Is it ready?"));
        assertTrue(yes.prompts.get(0).contains("YES or NO"), "the answer format is appended to the question");

        ScriptedProvider no = new ScriptedProvider();
        no.replies.add("NO\nnot yet");
        run(yaml, no);
        assertEquals(1, no.prompts.size(), "NO: only the check turn ran; the action was not sent");
    }
}
