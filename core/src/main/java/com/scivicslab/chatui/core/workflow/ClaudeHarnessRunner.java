package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.SseActor;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActionResult;
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

import java.io.InputStream;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a "leash" workflow in-process: it builds a per-run Turing Workflow {@link IIActorSystem} with
 * the engine's built-in actors plus a {@link ClaudeHarnessActor}, loads the named workflow YAML from
 * the classpath ({@code /workflows/<name>.yaml}), and runs it to completion on a virtual thread.
 *
 * <p>This mirrors quarkus-chat-ui3's {@code AgentLoopRunner}, but the driven actor wraps the Claude
 * CLI harness (one constrained instruction per step) rather than a bare-LLM tool loop.</p>
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

    /** Starts the named workflow on a virtual thread (returns immediately). */
    public void launch(String workflowName, String inputJson) {
        Thread.ofVirtual().name("workflow-" + workflowName).start(() -> run(workflowName, inputJson));
    }

    private void run(String workflowName, String inputJson) {
        ActorRef<SseActor> sseRef = chatSystem.getSseActor();
        LlmProvider provider = chatSystem.getProvider();
        if (sseRef == null || provider == null) {
            LOG.warning("Cannot run workflow: actor system not ready");
            return;
        }
        IIActorSystem system = new IIActorSystem("workflow-" + workflowName);
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
            system.addIIActor(new VarsActor(system, new HashMap<>()));
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
            interpreter.setSelfActorRef(interpreterActor);
            system.addIIActor(interpreterActor);

            ClaudeHarnessActor harness = new ClaudeHarnessActor(
                    "harness", provider, sseRef, ioLog, system, mapper, inputJson, approvalRegistry);
            system.addIIActor(harness);

            String resource = "/workflows/" + workflowName + ".yaml";
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) {
                    sseRef.tell(a -> a.emit(ChatEvent.error("workflow not found: " + workflowName)));
                    return;
                }
                interpreter.readYaml(in);
            }

            ActionResult result = interpreter.runUntilEnd(MAX_ITERATIONS);
            if (!result.isSuccess()) {
                sseRef.tell(a -> a.emit(ChatEvent.error("workflow failed: " + result.getResult())));
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Workflow run error", e);
            sseRef.tell(a -> a.emit(ChatEvent.error("workflow error: " + e.getMessage())));
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
    }
}
