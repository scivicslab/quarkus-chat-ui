package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure JUnit 5 tests for the runner's helpers (no actor system, no provider). */
class ClaudeHarnessRunnerTest {

    @Test
    @DisplayName("workflowTitle prefers name:, strips quotes, falls back to the first line")
    void workflowTitle() {
        assertEquals("check-then-act", ClaudeHarnessRunner.workflowTitle("name: check-then-act\nsteps: []"));
        assertEquals("quoted", ClaudeHarnessRunner.workflowTitle("\n\nname: \"quoted\"\n"));
        assertEquals("# a comment", ClaudeHarnessRunner.workflowTitle("# a comment\nsteps: []"));
        assertEquals("workflow", ClaudeHarnessRunner.workflowTitle("   \n"));
        assertEquals("workflow", ClaudeHarnessRunner.workflowTitle(null));
    }

    @Test
    @DisplayName("varsFromInput flattens top-level fields to strings; non-object input yields no vars")
    void varsFromInput() {
        ClaudeHarnessRunner r = new ClaudeHarnessRunner();
        r.mapper = new ObjectMapper();
        Map<String, String> vars = r.varsFromInput("{\"target\":\"x y\",\"n\":3,\"b\":true,\"o\":{\"a\":1}}");
        assertEquals("x y", vars.get("target"));
        assertEquals("3", vars.get("n"));
        assertEquals("true", vars.get("b"));
        assertEquals("{\"a\":1}", vars.get("o"));
        assertTrue(r.varsFromInput("").isEmpty());
        assertTrue(r.varsFromInput("[1,2]").isEmpty());
        assertTrue(r.varsFromInput("not json").isEmpty());
    }

    @Test
    @DisplayName("readBundledYaml serves the templates and rejects bad names")
    void readBundledYaml() {
        assertNotNull(ClaudeHarnessRunner.readBundledYaml("check-then-act"));
        assertNull(ClaudeHarnessRunner.readBundledYaml("../secret"));
        assertNull(ClaudeHarnessRunner.readBundledYaml("no-such-workflow"));
    }

    @Test
    @DisplayName("every bundled workflow YAML parses and its params defaults reach the interpreter state")
    void bundledYamlsParse() {
        for (String name : List.of("doc-check", "explain-judge-implement", "explain-approve-implement", "check-then-act")) {
            String yaml = ClaudeHarnessRunner.readBundledYaml(name);
            assertNotNull(yaml, name);
            IIActorSystem system = new IIActorSystem("parse-" + name);
            try {
                Interpreter interpreter = new Interpreter.Builder().loggerName("interpreter").team(system).build();
                InterpreterIIAR actor = new InterpreterIIAR("interpreter", interpreter, system);
                interpreter.setSelfActorRef(actor);
                system.addIIActor(actor);
                interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
                assertTrue(interpreter.hasCodeLoaded(), name);
                ClaudeHarnessRunner.putParamDefaults(actor, yaml, Map.of());
            } finally {
                system.terminateIIActors();
                system.terminate();
            }
        }
    }

    @Test
    @DisplayName("params defaults are put into the interpreter state unless the input set them")
    void paramDefaults() {
        String yaml = """
                name: t
                params:
                  a: {default: "x"}
                  b: {default: "y"}
                  c: {description: "no default"}
                steps: []
                """;
        IIActorSystem system = new IIActorSystem("defaults");
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("interpreter").team(system).build();
            InterpreterIIAR actor = new InterpreterIIAR("interpreter", interpreter, system);
            interpreter.setSelfActorRef(actor);
            system.addIIActor(actor);
            ClaudeHarnessRunner.putParamDefaults(actor, yaml, Map.of("b", "from-input"));
            assertEquals("x", actor.getJsonString("a"));
            assertNull(actor.getJsonString("b"), "input-set variable must not be overwritten");
            assertNull(actor.getJsonString("c"));
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
    }

    @Test
    @DisplayName("check: only a first line starting with YES counts, markdown emphasis ignored")
    void firstLineStartsWithYes() {
        assertTrue(HarnessLeash.firstLineStartsWith("YES\nbecause", "YES"));
        assertTrue(HarnessLeash.firstLineStartsWith("\n**Yes** — the jar exists", "YES"));
        assertTrue(HarnessLeash.firstLineStartsWith("yes.", "YES"));
        assertFalse(HarnessLeash.firstLineStartsWith("NO\nYES later", "YES"));
        assertFalse(HarnessLeash.firstLineStartsWith("The answer is YES", "YES"));
        assertFalse(HarnessLeash.firstLineStartsWith("", "YES"));
        assertFalse(HarnessLeash.firstLineStartsWith(null, "YES"));
    }
}
