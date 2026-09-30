package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.SseActor;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;
import com.scivicslab.turingworkflow.workflow.accumulator.ConsoleAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a "leash" workflow in-process: it builds a per-run Turing Workflow {@link IIActorSystem} with
 * the engine's built-in actors plus the chat-ui actors {@code harness} ({@link HarnessLeash} wrapped by
 * {@link HarnessLeashIIAR}) and {@code queue} ({@link QueueBridge} wrapped by {@link QueueBridgeIIAR}),
 * loads the workflow YAML, and runs it to completion on
 * a virtual thread.
 *
 * <p>The YAML comes either from the classpath ({@code /workflows/<name>.yaml}, the bundled
 * templates) or as text written by the user in the Workflow tab and queued in the browser's prompt
 * queue. Top-level string fields of the input JSON are loaded into the workflow {@code vars}, so the
 * YAML can refer to them as {@code ${name}}.</p>
 *
 * <p>Whatever happens, exactly one terminal {@code result} event (busy=false) is emitted when the run
 * ends. The browser treats it like the end of a prompt turn and moves on to the next queue item.</p>
 */
@ApplicationScoped
public class ClaudeHarnessRunner {

    private static final Logger LOG = Logger.getLogger(ClaudeHarnessRunner.class.getName());
    private static final int MAX_ITERATIONS = 1_000_000;

    @Inject
    ChatUiActorSystem chatSystem;

    @Inject
    IoLogStore ioLog;

    @Inject
    ObjectMapper mapper;

    @Inject
    WorkflowApprovalRegistry approvalRegistry;

    /** Starts the named bundled workflow on a virtual thread (returns immediately). */
    public void launch(String workflowName, String inputJson) {
        String yaml = readBundledYaml(workflowName);
        if (yaml == null) {
            ActorRef<SseActor> sseRef = chatSystem.getSseActor();
            if (sseRef != null) {
                sseRef.tell(a -> a.emit(ChatEvent.error("workflow not found: " + workflowName)));
            }
            return;
        }
        launchYaml(yaml, inputJson);
    }

    /** Starts a workflow given as YAML text on a virtual thread (returns immediately). */
    public void launchYaml(String yaml, String inputJson) {
        String title = workflowTitle(yaml);
        Thread.ofVirtual().name("workflow-" + title).start(() -> run(title, yaml, inputJson));
    }

    /**
     * The display title of a workflow YAML: its {@code name:} value, else the first non-empty line,
     * else {@code "workflow"}. Also used by the browser as the queue-item label.
     */
    public static String workflowTitle(String yaml) {
        if (yaml == null) return "workflow";
        String firstNonEmpty = null;
        for (String line : yaml.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (firstNonEmpty == null) firstNonEmpty = t;
            if (t.startsWith("name:")) {
                String v = t.substring(5).strip().replaceAll("^[\"']|[\"']$", "");
                if (!v.isEmpty()) return v;
            }
        }
        return firstNonEmpty == null ? "workflow" : firstNonEmpty;
    }

    private void run(String title, String yaml, String inputJson) {
        ActorRef<SseActor> sseRef = chatSystem.getSseActor();
        LlmProvider provider = chatSystem.getProvider();
        if (sseRef == null || provider == null) {
            LOG.warning("Cannot run workflow: actor system not ready");
            return;
        }
        Consumer<ChatEvent> emitter = ev -> sseRef.tell(a -> a.emit(ev));
        try {
            ActionResult result = runWorkflow(title, yaml, inputJson, provider, emitter, ioLog, mapper,
                    approvalRegistry);
            if (!result.isSuccess()) {
                emitter.accept(ChatEvent.error("workflow failed: " + result.getResult()));
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Workflow run error", e);
            emitter.accept(ChatEvent.error("workflow error: " + e.getMessage()));
        } finally {
            // The single terminal event: the browser ends its "busy" turn and advances its queue.
            emitter.accept(ChatEvent.result(provider.getSessionId(), 0.0, 0L, provider.getCurrentModel(), false));
        }
    }

    /**
     * Assembles the per-run actor system and runs the YAML to its end. This is the whole run except
     * the terminal result event, so tests drive the bundled YAMLs through it with a scripted provider.
     *
     * @return the interpreter's final result
     */
    static ActionResult runWorkflow(String title, String yaml, String inputJson, LlmProvider provider,
                                    Consumer<ChatEvent> emitter, IoLogStore ioLog, ObjectMapper mapper,
                                    WorkflowApprovalRegistry approvalRegistry) throws Exception {
        String input = inputJson == null ? "" : inputJson;
        IIActorSystem system = new IIActorSystem("workflow-" + title);
        try {
            Interpreter interpreter = new Interpreter.Builder()
                    .loggerName("interpreter")
                    .team(system)
                    .build();
            interpreter.setWorkflowBaseDir(".");

            system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
            MultiplexerAccumulator mux = new MultiplexerAccumulator();
            mux.addTarget(new ConsoleAccumulator());
            system.addIIActor(new MultiplexerAccumulatorIIAR("log", mux, system));
            Map<String, String> vars = varsFromInput(mapper, input);
            system.addIIActor(new VarsActor(system, vars));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
            interpreter.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);
            // ${name} in action arguments expands from the interpreter's JSON state (as the CLI's -P does).
            putVariables(interpreterActor, vars);
            putParamDefaults(interpreterActor, yaml, vars);

            system.addIIActor(new HarnessLeashIIAR("harness",
                    new HarnessLeash(provider, emitter, ioLog, mapper, input, approvalRegistry), system));
            system.addIIActor(new QueueBridgeIIAR("queue",
                    new QueueBridge(emitter, mapper, title, yaml, input), system));

            try (InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
                interpreter.readYaml(in);
            }
            return interpreter.runUntilEnd(MAX_ITERATIONS);
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
    }

    /** Puts each variable into the interpreter's JSON state so {@code ${name}} expands in arguments. */
    private static void putVariables(InterpreterIIAR interpreterActor, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String jsonArg = new org.json.JSONObject()
                    .put("path", e.getKey())
                    .put("value", e.getValue())
                    .toString();
            interpreterActor.callByActionName("putJson", jsonArg);
        }
    }

    /** Applies {@code params.<name>.default} from the YAML for every variable the input did not set. */
    static void putParamDefaults(InterpreterIIAR interpreterActor, String yaml, Map<String, String> vars) {
        try {
            JsonNode params = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(yaml).path("params");
            if (!params.isObject()) return;
            params.fields().forEachRemaining(e -> {
                if (vars.containsKey(e.getKey())) return;
                JsonNode def = e.getValue().get("default");
                if (def == null || def.isNull()) return;
                String jsonArg = new org.json.JSONObject()
                        .put("path", e.getKey())
                        .put("value", def.asText())
                        .toString();
                interpreterActor.callByActionName("putJson", jsonArg);
            });
        } catch (Exception e) {
            LOG.fine("no usable params section: " + e.getMessage());
        }
    }

    /** Top-level string/number/boolean fields of the input JSON become workflow variables. */
    Map<String, String> varsFromInput(String inputJson) {
        return varsFromInput(mapper, inputJson);
    }

    static Map<String, String> varsFromInput(ObjectMapper mapper, String inputJson) {
        Map<String, String> vars = new HashMap<>();
        if (inputJson == null || inputJson.isBlank()) return vars;
        try {
            JsonNode root = mapper.readTree(inputJson);
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    vars.put(e.getKey(), v.isValueNode() ? v.asText() : v.toString());
                });
            }
        } catch (Exception e) {
            LOG.warning("workflow input is not a JSON object; no vars set: " + e.getMessage());
        }
        return vars;
    }

    /** Reads {@code /workflows/<name>.yaml} from the classpath; null if the name is invalid or absent. */
    public static String readBundledYaml(String name) {
        if (name == null || !name.matches("[a-z0-9-]{1,64}")) return null;
        try (InputStream in = ClaudeHarnessRunner.class.getResourceAsStream("/workflows/" + name + ".yaml")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
