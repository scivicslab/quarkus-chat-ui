package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 tests: the {@code queue} workflow actor driven by a real Turing Workflow Interpreter,
 * so the action-name dispatch, argument parsing and {@code ${var}} expansion are all exercised.
 */
class QueueBridgeActorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Runs the YAML with the same actor set as ClaudeHarnessRunner (minus harness) and returns the events. */
    private static List<ChatEvent> runYaml(String yaml, Map<String, String> vars) {
        List<ChatEvent> events = new ArrayList<>();
        IIActorSystem system = new IIActorSystem("test-workflow");
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("interpreter").team(system).build();
            interpreter.setWorkflowBaseDir(".");
            system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
            MultiplexerAccumulator mux = new MultiplexerAccumulator();
            mux.addTarget(new ConsoleAccumulator());
            system.addIIActor(new MultiplexerAccumulatorIIAR("log", mux, system));
            system.addIIActor(new VarsActor(system, new java.util.HashMap<>(vars)));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
            interpreter.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);
            vars.forEach((k, v) -> interpreterActor.callByActionName("putJson",
                    new org.json.JSONObject().put("path", k).put("value", v).toString()));
            system.addIIActor(new QueueBridgeActor("queue", system, events::add, MAPPER,
                    "my-title", yaml, "{\"k\":\"v\"}"));
            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            ActionResult result = interpreter.runUntilEnd(1000);
            assertTrue(result.isSuccess(), "workflow should end: " + result.getResult());
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
        return events;
    }

    private static List<ChatEvent> ofType(List<ChatEvent> events, String type) {
        List<ChatEvent> out = new ArrayList<>();
        for (ChatEvent e : events) if (type.equals(e.type())) out.add(e);
        return out;
    }

    @Test
    @DisplayName("requeue emits one queue_add carrying this workflow's yaml, input and title")
    void requeue_emitsWorkflowItem() throws Exception {
        String yaml = """
                name: t
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: queue
                        method: requeue
                """;
        List<ChatEvent> adds = ofType(runYaml(yaml, Map.of()), "queue_add");
        assertEquals(1, adds.size());
        JsonNode item = MAPPER.readTree(adds.get(0).content());
        assertEquals("workflow", item.get("kind").asText());
        assertEquals("my-title", item.get("text").asText());
        assertEquals(yaml, item.get("yaml").asText());
        assertEquals("{\"k\":\"v\"}", item.get("input").asText());
        assertTrue(item.get("auto").asBoolean());
    }

    @Test
    @DisplayName("enqueue emits a prompt item whose text is the (jexl-evaluated) argument")
    void enqueue_emitsPromptItem() throws Exception {
        String yaml = """
                name: t
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: queue
                        method: enqueue
                        arguments: "jexl: 'Deploy ' + state.getString('target') + ' now'"
                """;
        List<ChatEvent> adds = ofType(runYaml(yaml, Map.of("target", "foo.jar")), "queue_add");
        assertEquals(1, adds.size());
        JsonNode item = MAPPER.readTree(adds.get(0).content());
        assertEquals("prompt", item.get("kind").asText());
        assertEquals("Deploy foo.jar now", item.get("text").asText());
        assertTrue(item.get("auto").asBoolean());
        assertNull(item.get("yaml"));
    }

    @Test
    @DisplayName("enqueue with an empty argument fails, so the workflow falls through to the next transition")
    void enqueue_emptyArgument_fails() {
        String yaml = """
                name: t
                steps:
                  - states: ["0", "sent"]
                    actions:
                      - actor: queue
                        method: enqueue
                        arguments: ""
                  - states: ["0", "end"]
                    actions:
                      - actor: queue
                        method: requeue
                """;
        List<ChatEvent> events = runYaml(yaml, Map.of());
        List<ChatEvent> adds = ofType(events, "queue_add");
        assertEquals(1, adds.size(), "only the fall-through transition's requeue should fire");
        assertTrue(adds.get(0).content().contains("\"kind\":\"workflow\""));
    }

    @Test
    @DisplayName("probe: the engine's built-in out.print works with this actor set-up")
    void probe_outPrint() {
        String yaml = """
                name: t
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: out
                        method: print
                        arguments: {"message": "hello"}
                """;
        runYaml(yaml, Map.of());
    }
}
