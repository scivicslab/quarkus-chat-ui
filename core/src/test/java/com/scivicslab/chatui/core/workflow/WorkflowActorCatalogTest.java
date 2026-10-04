package com.scivicslab.chatui.core.workflow;

import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit 5: a run in progress is listed from its actor system itself, whoever registered the
 * actors, and disappears when the run ends.
 */
class WorkflowActorCatalogTest {

    @Test
    @DisplayName("a running system's actors are rows under running:<title> #<n>, and resolve by that origin")
    void runningSystemIsListed() {
        WorkflowActorCatalog catalog = new WorkflowActorCatalog();   // no sources, no application system
        IIActorSystem system = new IIActorSystem("test-run");
        try {
            system.addIIActor(new HarnessLeashIIAR("harness", new HarnessLeash(null, e -> { }, null, "", null), system));
            system.addIIActor(new QueueBridgeIIAR("queue", new QueueBridge(null, e -> { }, "", "", ""), system));

            WorkflowActorCatalog.Run run = catalog.runStarted("doc-check", system);
            assertEquals("running:doc-check #1", run.origin());
            Map<String, String> harness = catalog.rows().stream()
                    .filter(r -> r.get("name").equals("harness")).findFirst().orElseThrow();
            assertEquals("HarnessLeashIIAR", harness.get("type"));
            assertEquals(run.origin(), harness.get("origin"));
            assertEquals(QueueBridgeIIAR.class, catalog.classOf(run.origin(), "queue"));
            assertTrue(catalog.rows().stream().anyMatch(r -> r.get("name").equals("ROOT")),
                    "the engine's own root actor is in the system, so it is listed too");

            catalog.runEnded(run);
            assertFalse(catalog.rows().stream().anyMatch(r -> r.get("origin").equals(run.origin())));
            assertEquals(null, catalog.classOf(run.origin(), "queue"));
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
    }
}
