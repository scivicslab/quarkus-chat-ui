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
    @DisplayName("the actor table names the classes whose @Action methods the YAML calls")
    void actorTable() {
        assertTrue(ActionCatalog.actionNamesOf(ClaudeHarnessRunner.ACTOR_CLASSES.get("harness")).contains("check"));
        assertTrue(ActionCatalog.actionNamesOf(ClaudeHarnessRunner.ACTOR_CLASSES.get("queue")).containsAll(java.util.List.of("enqueue", "requeue")));
        assertTrue(ActionCatalog.actionNamesOf(ClaudeHarnessRunner.ACTOR_CLASSES.get("out")).contains("print"));
        assertTrue(ClaudeHarnessRunner.INTERPRETER_ACTIONS.contains("onlyIf"));
    }
}
