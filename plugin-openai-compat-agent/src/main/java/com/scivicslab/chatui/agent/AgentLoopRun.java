package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.chatui.core.plugin.WorkflowActorSource.WorkflowActor;
import com.scivicslab.chatui.core.workflow.WorkflowActorCatalog;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import java.util.ArrayList;
import java.util.List;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.VarsActor;
import com.scivicslab.turingworkflow.workflow.accumulator.ConsoleAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Runs one turn's workflow: builds a per-turn {@link IIActorSystem} with the engine's built-in actors
 * and the {@code agent} actor, loads the YAML, and runs it to its end. The system is thrown away
 * when the turn ends.
 */
public final class AgentLoopRun {

    private static final int MAX_ITERATIONS = 100_000;

    private AgentLoopRun() {}

    /** The bundled inner-loop workflows, by name; the classpath resource is {@code /agent-loop-workflows/<name>.yaml}. */
    public static String readBundledYaml(String name) {
        if (name == null || !name.matches("[a-z0-9-]{1,64}")) return null;
        try (InputStream in = AgentLoopRun.class.getResourceAsStream("/agent-loop-workflows/" + name + ".yaml")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param yaml      the inner-loop workflow
     * @param turn      this turn's state and work
     * @param userPrompt this turn's prompt, put into the interpreter's JSON state as {@code user.prompt}
     * @param onInterpreter receives the interpreter so a cancel can call {@code requestStop}; may be null
     * @param catalog   told that this run's actor system exists, so the Actions tab lists it; may be null
     * @return the interpreter's final result
     */
    public static ActionResult run(String yaml, AgentTurn turn, String userPrompt,
                                   Consumer<Interpreter> onInterpreter,
                                   WorkflowActorCatalog catalog) throws Exception {
        IIActorSystem system = new IIActorSystem("agent-loop");
        WorkflowActorCatalog.Run run = catalog == null ? null : catalog.runStarted("agent-loop", system);
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("interpreter").team(system).build();
            interpreter.setWorkflowBaseDir(".");
            if (onInterpreter != null) onInterpreter.accept(interpreter);
            registerRunActors(system, interpreter, turn);
            InterpreterIIAR interpreterActor = (InterpreterIIAR) (IIActorRef<?>) system.getIIActor("interpreter");
            interpreterActor.callByActionName("putJson", new org.json.JSONObject()
                    .put("path", "user.prompt").put("value", userPrompt == null ? "" : userPrompt).toString());
            try (InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
                interpreter.readYaml(in);
            }
            return interpreter.runUntilEnd(MAX_ITERATIONS);
        } finally {
            if (catalog != null) catalog.runEnded(run);
            system.terminateIIActors();
            system.terminate();
        }
    }

    /** Emits the error the browser sees when a run did not reach its end. */
    static ChatEvent failure(ActionResult result) {
        return ChatEvent.error("agent loop failed: " + result.getResult());
    }

    /**
     * Registers the actors of one inner-loop run and says what was registered — the same list the
     * Actions tab shows, so it cannot drift from what a run has
     * ({@code ActionCatalogWithJavadoc_260930_oo01}).
     *
     * @param system      the run's own actor system
     * @param interpreter the run's interpreter, which becomes {@code interpreter} (and {@code this})
     * @param turn        what {@code agent} wraps; null when only the list is wanted
     * @return the actors in registration order, with the class whose {@code @Action} methods answer
     */
    static List<WorkflowActor> registerRunActors(IIActorSystem system, Interpreter interpreter, AgentTurn turn) {
        List<WorkflowActor> actors = new ArrayList<>();
        actors.add(register(system, new DynamicActorLoaderIIAR("loader", system)));
        MultiplexerAccumulator mux = new MultiplexerAccumulator();
        mux.addTarget(new ConsoleAccumulator());
        actors.add(register(system, new MultiplexerAccumulatorIIAR("log", mux, system)));
        actors.add(register(system, new VarsActor(system, new HashMap<>())));
        InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
        interpreter.setSelfActorRef(interpreterActor);
        actors.add(register(system, interpreterActor));
        actors.add(register(system, new AgentTurnIIAR("agent", turn, system)));
        return actors;
    }

    private static WorkflowActor register(IIActorSystem system, IIActorRef<?> actor) {
        system.addIIActor(actor);
        return new WorkflowActor(actor.getName(), actor.getClass(), "agent-loop");
    }

    /** The actors an inner-loop run may name, answered by registering them on a throwaway system. */
    public static List<WorkflowActor> actors() {
        IIActorSystem system = new IIActorSystem("agent-loop-actors");
        try {
            Interpreter interpreter = new Interpreter.Builder().loggerName("interpreter").team(system).build();
            List<WorkflowActor> actors = new ArrayList<>();
            for (WorkflowActor a : registerRunActors(system, interpreter, null)) {
                actors.add(a);
                if (a.name().equals("interpreter")) actors.add(new WorkflowActor("this", a.type(), a.origin()));
            }
            return actors;
        } finally {
            system.terminateIIActors();
            system.terminate();
        }
    }
}
