package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.plugin.WorkflowActorSource;
import com.scivicslab.chatui.openaicompat.AgentLoopExtension;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.OpenAiCompatClient;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The openai-compat provider's agent loop as a per-turn Turing Workflow ({@code AgentLoopTab_260930_oo01}).
 * {@code runAgentLoop} builds an {@link AgentTurn}, runs the selected inner-loop YAML to its end, and
 * returns; the YAML's {@code finish} sends the turn's reply and terminal result to the browser.
 */
@ApplicationScoped
public class AgentLoopExtensionImpl implements AgentLoopExtension, WorkflowActorSource {

    /** The actors an inner-loop run registers, for the Actions tab ({@link AgentLoopRun#actors}). */
    @Override
    public java.util.List<WorkflowActorSource.WorkflowActor> workflowActors() {
        return AgentLoopRun.actors();
    }


    private static final Logger LOG = Logger.getLogger(AgentLoopExtensionImpl.class.getName());

    /** The inner-loop workflows this jar carries; the first is the default. */
    public static final List<String> BUNDLED = List.of("agent-loop-react");

    @ConfigProperty(name = "chat-ui.agent-loop.enabled", defaultValue = "true")
    boolean enabled;

    /** MCP servers; empty means this instance's own {@code /mcp}. */
    @ConfigProperty(name = "chat-ui.agent-loop.mcp-urls")
    Optional<String> mcpUrls;

    @ConfigProperty(name = "chat-ui.agent-loop.mcp-timeout", defaultValue = "120")
    int mcpTimeoutSeconds;

    @ConfigProperty(name = "chat-ui.agent-loop.workflow", defaultValue = "agent-loop-react")
    String configuredWorkflow;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @Inject
    IoLogStore ioLog;

    private volatile String workflow;
    private volatile LlmCall llm;
    private volatile ToolCaller tools;
    private volatile Interpreter running;
    private volatile AgentTurn currentTurn;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void initialize(List<OpenAiCompatClient> clients) {
        this.llm = new OpenAiLlmCall(clients);
        List<String> urls = mcpUrls.filter(s -> !s.isBlank())
                .map(s -> Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList())
                .orElse(List.of("http://localhost:" + httpPort + "/mcp"));
        this.tools = new McpToolCaller(urls, Duration.ofSeconds(mcpTimeoutSeconds));
        LOG.info("Agent loop initialised: workflow=" + currentWorkflow() + " mcp=" + urls);
    }

    @Override
    public void runAgentLoop(String model, LinkedList<ChatMessage> history,
                             Consumer<ChatEvent> emitter, ProviderContext ctx) {
        String name = currentWorkflow();
        String yaml = AgentLoopRun.readBundledYaml(name);
        if (yaml == null) {
            emitter.accept(ChatEvent.error("agent loop workflow not found: " + name));
            emitter.accept(ChatEvent.result(null, 0.0, 0L, model, false));
            return;
        }
        String userPrompt = lastUserText(history);
        int turnNo = (ioLog != null) ? ioLog.currentTurn() : 0;
        boolean[] finished = new boolean[1];
        Consumer<ChatEvent> watching = ev -> {
            if ("result".equals(ev.type())) finished[0] = true;
            emitter.accept(ev);
        };
        AgentTurn watchedTurn = new AgentTurn(llm, tools, model, history, watching, ctx, ioLog, turnNo);
        currentTurn = watchedTurn;
        try {
            ActionResult result = AgentLoopRun.run(yaml, watchedTurn, userPrompt, i -> running = i);
            if (!result.isSuccess()) emitter.accept(AgentLoopRun.failure(result));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "agent loop run error", e);
            emitter.accept(ChatEvent.error("agent loop error: " + e.getMessage()));
        } finally {
            running = null;
            currentTurn = null;
            if (!finished[0]) {
                // The YAML did not reach finish: the browser still needs the turn to end.
                emitter.accept(ChatEvent.result(null, 0.0, 0L, model, false));
            }
        }
    }

    @Override
    public void cancel() {
        AgentTurn t = currentTurn;
        if (t != null) t.cancel();
        Interpreter i = running;
        if (i != null) i.requestStop();
    }

    /** The inner-loop workflow the next turn runs. */
    public String currentWorkflow() {
        String w = workflow;
        return w != null ? w : configuredWorkflow;
    }

    /**
     * Selects the inner-loop workflow for the next turn.
     *
     * @return null when selected, else why not
     */
    public String selectWorkflow(String name) {
        if (AgentLoopRun.readBundledYaml(name) == null) return "unknown workflow: " + name;
        workflow = name;
        return null;
    }

    private static String lastUserText(LinkedList<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof ChatMessage.User u) return u.content();
        }
        return "";
    }
}
