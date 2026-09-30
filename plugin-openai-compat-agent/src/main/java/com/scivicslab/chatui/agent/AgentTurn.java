package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.openaicompat.ToolDefinition;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One turn of the Local LLM agent loop: the state a turn carries between the workflow's steps and
 * the work each step does. A step is one model call; the model either asks for tools with
 * {@code <invoke>} blocks in its reply or gives the final answer. This is a plain object;
 * {@link AgentTurnIIAR} wraps it as the workflow actor {@code agent}.
 *
 * <p>A method returns a fact about the data ({@code TOOL} or {@code ANSWER}, a count) or throws when
 * it could not do the work; the workflow YAML decides on the fact with {@code onlyIf}
 * ({@code AgentLoopTab_260930_oo01}).</p>
 */
public class AgentTurn {

    private static final Logger LOG = Logger.getLogger(AgentTurn.class.getName());

    private static final String PROMPT_HEAD = """
            You are a helpful assistant with access to tools. To call a tool, write EXACTLY this format \
            in your reply (nothing else on those lines). Every parameter uses a <parameter name="..."> tag.

            Example:
            <invoke name="list_directory">
            <parameter name="path">/home/user/works</parameter>
            <reason>one concise sentence on why you need this now</reason>
            </invoke>

            Available tools:
            """;

    private static final String PROMPT_TAIL = """

            Call at most one tool per reply. After a tool result comes back, either call another tool \
            or give your final answer. When you have enough information, answer in plain text with NO \
            <invoke> block: that plain text is taken as your final answer to the user.""";

    private final LlmCall llm;
    private final ToolCaller tools;
    private final String model;
    private final LinkedList<ChatMessage> history;
    private final Consumer<ChatEvent> emitter;
    private final ProviderContext ctx;
    private final IoLogStore ioLog;
    private final long ioSession;
    private final int turnNo;

    // Per-turn state
    private final List<ToolCall> pendingCalls = new ArrayList<>();
    private String finalAnswer = "";
    private int stepCount = 0;
    private long startedAt;
    private volatile boolean cancelled;

    /**
     * @param llm      how the model is called
     * @param tools    the tools the model may call
     * @param model    the model name
     * @param history  the provider's conversation history; this turn's user message is already its last
     *                 entry, and this turn's assistant and tool messages are appended to it
     * @param emitter  where the browser-bound events go
     * @param ctx      the provider context (noThink, activity heartbeat)
     * @param ioLog    the I/O log, or null
     * @param turnNo   this turn's number in the I/O log
     */
    public AgentTurn(LlmCall llm, ToolCaller tools, String model, LinkedList<ChatMessage> history,
                     Consumer<ChatEvent> emitter, ProviderContext ctx, IoLogStore ioLog, int turnNo) {
        this.llm = llm;
        this.tools = tools;
        this.model = model;
        this.history = history;
        this.emitter = emitter;
        this.ctx = ctx;
        this.ioLog = ioLog;
        this.ioSession = (ioLog != null) ? ioLog.ensureSession() : -1;
        this.turnNo = turnNo;
    }

    /** Resets the per-turn state. */
    public void start() {
        pendingCalls.clear();
        finalAnswer = "";
        stepCount = 0;
        startedAt = System.currentTimeMillis();
    }

    /**
     * One model call. The reply is appended to the history as the assistant's message; intermediate
     * replies are shown to the browser as thinking.
     *
     * @return {@code "TOOL"} when the reply asks for tools, {@code "ANSWER"} when it is the final answer
     * @throws IllegalStateException when the model could not be called or the turn was cancelled
     */
    public String step() {
        if (cancelled) throw new IllegalStateException("cancelled");
        stepCount++;
        List<ChatMessage> request = new ArrayList<>();
        request.add(new ChatMessage.System(systemPrompt()));
        request.addAll(history);
        String reply = llm.complete(model, request, ctx.noThink(), chunk -> {
            if (ctx.onActivity() != null) ctx.onActivity().run();
        });
        history.addLast(new ChatMessage.Assistant(reply));
        List<ToolCall> calls = TextToolCallParser.parse(reply);
        if (calls.isEmpty()) {
            finalAnswer = TextToolCallParser.stripToolCallBlocks(reply);
            return "ANSWER";
        }
        pendingCalls.clear();
        pendingCalls.addAll(calls);
        emitter.accept(ChatEvent.thinking(reply + "\n"));
        return "TOOL";
    }

    /**
     * Runs every pending tool call and appends the observations to the history as one user message.
     *
     * @param maxObservationChars how many characters of each observation the model may see; the
     *                            whole observation goes to the I/O log
     * @return the number of tools run
     */
    public int runTools(int maxObservationChars) {
        if (cancelled) throw new IllegalStateException("cancelled");
        StringBuilder observation = new StringBuilder();
        int n = 0;
        for (ToolCall tc : pendingCalls) {
            emitter.accept(ChatEvent.thinking("→ " + tc.name() + "(" + shorten(tc.argumentsJson(), 200) + ")\n"));
            if (ctx.onActivity() != null) ctx.onActivity().run();
            String result;
            try {
                result = tools.call(tc.name(), tc.argumentsJson());
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
            }
            recordToolIo(tc, result);
            emitter.accept(ChatEvent.thinking("← " + shorten(result, 300) + "\n"));
            if (observation.length() > 0) observation.append("\n\n");
            observation.append("Result of ").append(tc.name()).append(":\n").append(shorten(result, maxObservationChars));
            n++;
        }
        pendingCalls.clear();
        history.addLast(new ChatMessage.User(observation.toString()));
        return n;
    }

    /** Sends the final answer to the browser as this turn's reply and its terminal result. */
    public void finish() {
        String answer = finalAnswer;
        if (answer == null || answer.isBlank()) {
            answer = cancelled ? "(cancelled)" : "(no answer: the step limit was reached before a final answer)";
            history.addLast(new ChatMessage.Assistant(answer));
        }
        emitter.accept(ChatEvent.delta(answer));
        emitter.accept(ChatEvent.result(null, 0.0, System.currentTimeMillis() - startedAt, model, false));
    }

    /** @return the number of model calls made so far in this turn */
    public int stepCount() {
        return stepCount;
    }

    /** Makes the next step or tool run fail; the thread interrupt that stops the HTTP call is the provider's. */
    public void cancel() {
        cancelled = true;
    }

    String systemPrompt() {
        StringBuilder sb = new StringBuilder(PROMPT_HEAD);
        List<ToolDefinition> defs;
        try {
            defs = tools.listTools();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "tools/list failed", e);
            defs = List.of();
        }
        if (defs.isEmpty()) sb.append("(none)\n");
        for (ToolDefinition d : defs) {
            sb.append("- ").append(d.name()).append("(").append(parameterNames(d.parametersJson())).append("): ")
              .append(d.description() == null ? "" : d.description().strip()).append("\n");
        }
        sb.append(PROMPT_TAIL);
        return sb.toString();
    }

    static String parameterNames(String schemaJson) {
        try {
            JSONObject props = new JSONObject(schemaJson).optJSONObject("properties");
            if (props == null) return "";
            return String.join(", ", props.keySet());
        } catch (Exception e) {
            return "";
        }
    }

    private void recordToolIo(ToolCall tc, String observation) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String m = "TOOL: " + tc.name() + "\nINPUT:\n" + tc.argumentsJson() + "\nOBSERVATION:\n" + observation;
            ioLog.record(ioSession, "agent", "turn" + turnNo + "/step" + stepCount + "/tool", m);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "I/O log tool record failed", e);
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
