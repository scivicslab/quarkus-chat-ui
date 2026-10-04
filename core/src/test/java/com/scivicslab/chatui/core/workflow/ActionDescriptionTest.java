package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.pojoactor.action.schema.ActionCatalog;
import com.scivicslab.pojoactor.action.schema.ActionManifest;
import com.scivicslab.pojoactor.action.schema.ActionSchemaRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 tests: the build's two generated artefacts (action-schemas/*.schema.json from the
 * records, META-INF/turing-plugin.json from the Javadoc) are on the classpath and combine into one
 * description per action, by class, without a running actor (ActionCatalogWithJavadoc_260930_oo01).
 */
class ActionDescriptionTest {

    private static final ActionSchemaRegistry SCHEMAS = new ActionSchemaRegistry();
    private static final ActionManifest MANIFEST = new ActionManifest();

    @Test
    @DisplayName("harness.check: the record's schema, the @param prose on the field, the method's first sentence")
    void check() {
        ObjectNode d = ActionCatalog.describe(HarnessLeashIIAR.class, "check", SCHEMAS, MANIFEST);
        assertTrue(d.get("description").asText().startsWith("Sends one question to be answered YES or NO"), d.toString());
        assertEquals("string", d.get("schema").get("properties").get("question").get("type").asText());
        assertEquals("question", d.get("schema").get("required").get(0).asText());
        assertTrue(d.get("schema").get("properties").get("question").get("description").asText().startsWith("the question"), d.toString());
    }

    @Test
    @DisplayName("harness.start takes no record: no schema, the note, and still the first sentence")
    void start() {
        ObjectNode d = ActionCatalog.describe(HarnessLeashIIAR.class, "start", SCHEMAS, MANIFEST);
        assertTrue(d.get("schema").isNull());
        assertTrue(d.get("note").asText().contains("String"));
        assertTrue(d.get("description").asText().startsWith("Reads the run input"), d.toString());
    }

    @Test
    @DisplayName("harness.explain: the String it receives is declared ignored, the body says where the task comes from, the <pre> is the step")
    void explain() {
        ObjectNode d = ActionCatalog.describe(HarnessLeashIIAR.class, "explain", SCHEMAS, MANIFEST);
        assertEquals("args", d.get("argument").get("name").asText(), d.toString());
        assertEquals("ignored", d.get("argument").get("description").asText());
        assertTrue(d.get("details").asText().contains("run input's target"), d.toString());
        assertEquals("- actor: harness\n  method: explain", d.get("example").asText());
        assertEquals(d.get("example").asText(), ActionStepYaml.of("harness", "explain", d),
                "the Javadoc's own example is the step shown");
    }

    @Test
    @DisplayName("harness.check: the Javadoc example keeps its braces and jexl; the details tell the onlyIf pattern")
    void checkExample() {
        ObjectNode d = ActionCatalog.describe(HarnessLeashIIAR.class, "check", SCHEMAS, MANIFEST);
        String example = d.get("example").asText();
        assertTrue(example.startsWith("- actor: harness\n  method: check\n  arguments: {question: \"jexl: state.getString('condition')\"}"), example);
        assertTrue(example.contains("method: onlyIf"), example);
        assertTrue(d.get("details").asText().contains("onlyIf"), d.toString());
    }

    @Test
    @DisplayName("queue.enqueue: the record's field and the example agree on the key")
    void enqueue() {
        ObjectNode d = ActionCatalog.describe(QueueBridgeIIAR.class, "enqueue", SCHEMAS, MANIFEST);
        assertTrue(d.get("schema").get("properties").has("text"));
        assertTrue(d.get("example").asText().contains("arguments: {text:"), d.toString());
    }

    @Test
    @DisplayName("the actor table names the classes whose @Action methods the YAML calls")
    void actorTable() {
        assertTrue(ActionCatalog.actionNamesOf(ClaudeHarnessRunner.ACTOR_CLASSES.get("harness")).contains("check"));
        assertTrue(ActionCatalog.actionNamesOf(ClaudeHarnessRunner.ACTOR_CLASSES.get("queue")).containsAll(java.util.List.of("enqueue", "requeue")));
        assertTrue(ActionCatalog.actionNamesOf(ClaudeHarnessRunner.ACTOR_CLASSES.get("out")).contains("print"));
        assertTrue(ClaudeHarnessRunner.INTERPRETER_ACTIONS.contains("onlyIf"));
    }
}
