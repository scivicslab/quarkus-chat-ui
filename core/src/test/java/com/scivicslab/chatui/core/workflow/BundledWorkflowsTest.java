package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit 5 tests that run the bundled workflow YAMLs through the same assembly the app uses
 * ({@link ClaudeHarnessRunner#runWorkflow}) over a scripted provider. The loops and gates are
 * written in the YAML as {@code onlyIf} conditions on stored answers, so these tests are what shows
 * that each YAML takes the branch its stored answer says.
 */
class BundledWorkflowsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ActionResult run(String name, String input, ScriptedProvider provider, List<ChatEvent> events)
            throws Exception {
        String yaml = ClaudeHarnessRunner.readBundledYaml(name);
        assertNotNull(yaml, name);
        return ClaudeHarnessRunner.runWorkflow(name, yaml, input, provider, events::add, null, MAPPER, null);
    }

    private static List<ChatEvent> ofType(List<ChatEvent> events, String type) {
        List<ChatEvent> out = new ArrayList<>();
        for (ChatEvent e : events) if (type.equals(e.type())) out.add(e);
        return out;
    }

    @Test
    @DisplayName("doc-check: one turn per checklist item, then finish; the loop ends by its onlyIf, not by a failure")
    void docCheck_sendsEachItemOnce(@TempDir Path dir) throws Exception {
        Path checklist = dir.resolve("checklist.md");
        Files.writeString(checklist, "item one\n---\nitem two\n---\nitem three\n");
        ScriptedProvider p = new ScriptedProvider("ok1", "ok2", "ok3");
        List<ChatEvent> events = new ArrayList<>();

        ActionResult result = run("doc-check", "{\"path\":\"" + checklist + "\"}", p, events);

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(3, p.prompts.size(), "exactly one turn per item");
        assertTrue(p.prompts.get(0).contains("item one"));
        assertTrue(p.prompts.get(2).contains("item three"));
        assertTrue(events.stream().anyMatch(e -> "info".equals(e.type()) && e.content().startsWith("✅ Workflow complete")));
    }

    @Test
    @DisplayName("doc-check with a target: one framing turn first, then one turn per item")
    void docCheck_framesTheTarget(@TempDir Path dir) throws Exception {
        Path checklist = dir.resolve("checklist.md");
        Files.writeString(checklist, "only item\n");
        ScriptedProvider p = new ScriptedProvider("準備OK", "ok");
        ActionResult result = run("doc-check",
                "{\"path\":\"" + checklist + "\",\"target\":\"the README\"}", p, new ArrayList<>());
        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(2, p.prompts.size());
        assertTrue(p.prompts.get(0).contains("the README"), "the framing turn carries the target");
        assertTrue(p.prompts.get(1).contains("only item"));
    }

    @Test
    @DisplayName("doc-check with no checklist: no item turns, still finishes")
    void docCheck_noChecklist() throws Exception {
        ScriptedProvider p = new ScriptedProvider();
        ActionResult result = run("doc-check", "{}", p, new ArrayList<>());
        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(0, p.prompts.size());
    }

    @Test
    @DisplayName("explain-judge-implement: FAIL stores the feedback and re-explains; PASS implements")
    void explainJudgeImplement_failThenPass() throws Exception {
        ScriptedProvider p = new ScriptedProvider(
                "plan v1",                 // explain
                "FAIL\nmissing tests",     // judge -> refine
                "plan v2",                 // explain again, with the feedback
                "PASS\nfine",              // judge
                "implemented");            // implement
        List<ChatEvent> events = new ArrayList<>();

        ActionResult result = run("explain-judge-implement", "{\"target\":\"do X\",\"maxRefines\":2}", p, events);

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(5, p.prompts.size());
        assertTrue(p.prompts.get(2).contains("missing tests"), "the re-explanation carries the judge's feedback");
        assertTrue(p.prompts.get(4).contains("plan v2"), "the implementation turn carries the passed plan");
    }

    @Test
    @DisplayName("explain-judge-implement: after maxRefines FAILs the pipeline proceeds without another judge turn")
    void explainJudgeImplement_refineBudget() throws Exception {
        ScriptedProvider p = new ScriptedProvider(
                "plan v1", "FAIL\na",      // round 1
                "plan v2",                 // explain after refine 1; judge answers PASS without a turn (budget 1)
                "implemented");
        ActionResult result = run("explain-judge-implement", "{\"target\":\"do X\",\"maxRefines\":1}", p, new ArrayList<>());
        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(4, p.prompts.size(), "explain, judge, explain, implement");
    }

    @Test
    @DisplayName("check-then-act: YES sends the action; NO re-enqueues the workflow and sends nothing")
    void checkThenAct_yesAndNo() throws Exception {
        String input = "{\"condition\":\"Is it ready?\",\"action\":\"Deploy it\"}";
        String yaml = ClaudeHarnessRunner.readBundledYaml("check-then-act")
                .replace("delay: 60000", "delay: 0");   // the template waits a minute before requeue

        ScriptedProvider yes = new ScriptedProvider("YES\nready", "deployed");
        List<ChatEvent> yesEvents = new ArrayList<>();
        assertTrue(ClaudeHarnessRunner.runWorkflow("t", yaml, input, yes, yesEvents::add, null, MAPPER, null).isSuccess());
        assertEquals(2, yes.prompts.size());
        assertTrue(yes.prompts.get(0).startsWith("Is it ready?"));
        assertTrue(yes.prompts.get(0).contains("YES or NO"), "the answer format is appended to the question");
        assertEquals("Deploy it", yes.prompts.get(1));
        assertEquals(0, ofType(yesEvents, "queue_add").size());

        ScriptedProvider no = new ScriptedProvider("NO\nnot yet");
        List<ChatEvent> noEvents = new ArrayList<>();
        assertTrue(ClaudeHarnessRunner.runWorkflow("t", yaml, input, no, noEvents::add, null, MAPPER, null).isSuccess());
        assertEquals(1, no.prompts.size(), "only the check turn ran");
        List<ChatEvent> adds = ofType(noEvents, "queue_add");
        assertEquals(1, adds.size(), "the workflow put itself back into the queue");
        assertTrue(adds.get(0).content().contains("\"kind\":\"workflow\""));
        assertTrue(MAPPER.readTree(adds.get(0).content()).get("input").asText().contains("Is it ready?"));
    }

    @Test
    @DisplayName("a turn's result is forwarded without busy=false, so the browser stays busy until the run ends")
    void turnResultIsForwardedWithoutBusy() throws Exception {
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
        ScriptedProvider p = new ScriptedProvider("first", "second");
        List<ChatEvent> events = new ArrayList<>();
        assertTrue(ClaudeHarnessRunner.runWorkflow("t", yaml, "{}", p, events::add, null, MAPPER, null).isSuccess());
        List<ChatEvent> results = ofType(events, "result");
        assertEquals(2, results.size(), "one result per turn is still forwarded (it closes the bubble)");
        for (ChatEvent r : results) assertNull(r.busy(), "the turn's busy=false must not reach the browser");
        assertEquals(List.of("do one", "do two"), p.prompts);
    }

    @Test
    @DisplayName("queue.enqueue puts a prompt item with the (jexl-evaluated) text; an empty text is a failure")
    void queueEnqueue() throws Exception {
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
                        method: enqueue
                        arguments: "jexl: 'Deploy ' + state.getString('target') + ' now'"
                """;
        List<ChatEvent> events = new ArrayList<>();
        assertTrue(ClaudeHarnessRunner.runWorkflow("t", yaml, "{\"target\":\"foo.jar\"}", new ScriptedProvider(),
                events::add, null, MAPPER, null).isSuccess());
        List<ChatEvent> adds = ofType(events, "queue_add");
        assertEquals(1, adds.size(), "the empty enqueue failed and the second transition was taken");
        var item = MAPPER.readTree(adds.get(0).content());
        assertEquals("prompt", item.get("kind").asText());
        assertEquals("Deploy foo.jar now", item.get("text").asText());
        assertTrue(item.get("auto").asBoolean());
    }
}
