package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.ConversationMcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Codex as a conversation's provider ({@code CliHarnessProvider_260912_oo01}).
 *
 * <p>Codex has no resident process to talk to: each turn is one {@code codex exec --json} run,
 * and the turns after the first are {@code codex exec resume <threadId> --json}, so the thread id
 * the first run reports is what this provider keeps — in memory and in its session file. The
 * prompt goes in on stdin (the {@code -} argument), the events come out on stdout as JSON Lines,
 * and the process exiting is the end of the turn.</p>
 *
 * <p>Codex runs its own tools: its command executions, file changes and MCP calls are reported
 * as {@code tool_use}/{@code tool_result} events for the conversation to record. Codex has no
 * switch that disables those tools, so this provider only exists with the harness tool
 * set.</p>
 */
public class CodexProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(CodexProvider.class.getName());

    public static final String ID = "codex";

    /** Shared because every conversation sees the same Codex installation and account. */
    private static final CodexModelCatalog MODEL_CATALOG = new CodexModelCatalog();

    /** Codex's sandbox for the commands it runs; the project's working directory is what it may write. */
    private static final String SANDBOX_MODE = "workspace-write";

    private final Path sessionFile;
    private final Path workingDir;
    private final CodexEventParser parser = new CodexEventParser();
    private volatile String model;
    private volatile String threadId;
    private volatile Process currentProcess;
    private final ConversationMcp mcp;
    private final String binary;
    /** The run's events and the calls it parks, in the order they happen. */
    private final LinkedBlockingQueue<StreamEvent> events = new LinkedBlockingQueue<>();
    /** Calls to chat-ui's tools the run waits on, by the id of their {@link StreamEvent#parkedCall} event. */
    private final Map<String, CompletableFuture<String>> parked = new ConcurrentHashMap<>();
    private final AtomicLong parkedSeq = new AtomicLong();
    /** The ids of the run's calls to the conversation's own tools, whose results are not passed on. */
    private final Set<String> ownToolUseIds = ConcurrentHashMap.newKeySet();
    /** The run is parked on calls: {@link #sendPrompt} returned before its end. */
    private volatile boolean suspended;
    private volatile String developerInstructions;
    private boolean sawResult;
    private boolean sawAssistant;

    /**
     * @param settings   process-wide harness settings
     * @param projectId  owning project's id, part of the session file name
     * @param chatId     conversation id, part of the session file name
     * @param workingDir the directory Codex works in, or {@code null} for this process's own
     */
    public CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir) {
        this(settings, projectId, chatId, workingDir, null);
    }

    /**
     * @param settings   process-wide harness settings
     * @param projectId  owning project's id, part of the session file name
     * @param chatId     conversation id, part of the session file name
     * @param workingDir the directory Codex works in, or {@code null} for this process's own
     * @param mcp        chat-ui's MCP server for this conversation: Codex calls the conversation's tools there, and
     *                   the calls are parked into the conversation's agent loop; {@code null} leaves the
     *                   conversation's tools to the {@code <invoke>} text (tasks/todo.md, "Shrinking the composed
     *                   prompt")
     */
    public CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir,
                         ConversationMcp mcp) {
        this(settings, projectId, chatId, workingDir, mcp, "codex");
    }

    /** As the public constructor, running {@code binary} instead of {@code codex}: a test's fake CLI. */
    CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir,
                  ConversationMcp mcp, String binary) {
        this.binary = binary;
        this.sessionFile = settings.sessionFile(projectId, chatId, ID);
        this.workingDir = workingDir;
        this.model = settings.codexModel();
        this.mcp = mcp;
        restoreSession();
    }

    /**
     * What Codex is told at the top of a turn's first prompt
     * ({@code HarnessPrefaceAndToolSplit_260912_oo01}): its own shell and file tools stay usable,
     * and the conversation's listed tools — web search among them, which Codex may lack — are
     * called with the {@code <invoke>} format.
     */
    static final String PREFACE = """
            You are running inside Codex, your own coding harness, which runs shell commands and \
            edits files itself. Use those directly, the way you always do, whenever they fit the \
            task. The tools listed below are this conversation's own tools, called only with the \
            <invoke> format described next; web_search and fetch among them reach the web even when \
            your harness cannot, and this conversation records what they return.

            """;

    /**
     * What Codex is told about itself when the conversation's tools come over MCP. It is part of the system
     * message, given as {@code developer_instructions} on every run and kept out of the thread's history.
     */
    static final String MCP_PREFACE = """
            You are running inside Codex, your own coding harness, which runs shell commands and \
            edits files itself. Use those directly, the way you always do, whenever they fit the \
            task. This conversation's own tools are the tools of the MCP server "chat-ui"; web_search \
            and fetch among them reach the web even when your harness cannot, and this conversation \
            records what they return.

            """;

    @Override public String promptPreface() { return mcp != null ? MCP_PREFACE : PREFACE; }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Codex"; }
    @Override
    public List<ModelEntry> getAvailableModels() {
        CodexModelCatalog.Snapshot snapshot = MODEL_CATALOG.snapshot();
        if ((model == null || model.isBlank()) && snapshot.defaultModel() != null) {
            model = snapshot.defaultModel();
        }
        return snapshot.models();
    }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }
    @Override public String getCurrentModel() { return model; }
    @Override public void setModel(String model) { this.model = model; }
    @Override public String getSessionId() { return threadId; }
    @Override public String detectEnvApiKey() { return System.getenv("OPENAI_API_KEY"); }

    @Override
    public void cancel() {
        suspended = false;
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            logger.info("codex process cancelled");
        }
        answerAllParked();
    }

    // ---- Tools over chat-ui's MCP server, parked into the conversation's agent loop ----

    @Override public boolean callsToolsByDefinition() { return mcp != null; }
    @Override public boolean parksToolCalls() { return mcp != null; }

    @Override
    public CompletableFuture<String> parkToolCall(String name, String argumentsJson) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Process p = currentProcess;
        if (p == null || !p.isAlive()) {
            result.complete("error: no turn of this conversation is running, so the call was not run");
            return result;
        }
        String id = "chat-ui-call-" + parkedSeq.incrementAndGet();
        parked.put(id, result);
        events.offer(StreamEvent.parkedCall(id, name, argumentsJson));
        return result;
    }

    @Override
    public void addToolResults(List<ToolCallResult> results) {
        for (ToolCallResult r : results) answer(r.callId(), r.content());
    }

    /**
     * The conversation's turn has ended. Calls still parked receive {@link CliProcess#TURN_ENDED}, and a run still
     * going is stopped; the thread is kept, and the next prompt resumes it.
     */
    @Override
    public void endTurn() {
        suspended = false;
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            logger.info("codex run stopped at the end of the conversation's turn");
        }
        answerAllParked();
    }

    /** The system message goes in {@code developer_instructions} on every run: it is not part of the history. */
    @Override
    public void setSystemMessage(String text) {
        this.developerInstructions = text;
    }

    private void answer(String callId, String text) {
        CompletableFuture<String> call = parked.remove(callId);
        if (call != null) call.complete(text);
    }

    private void answerAllParked() {
        for (String id : List.copyOf(parked.keySet())) answer(id, CliProcess.TURN_ENDED);
    }

    /** The type of the event the output reader leaves when the run's output closes. */
    private static final String OUTPUT_CLOSED = "output_closed";

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        if (model != null && !model.isBlank()) this.model = model;
        if (suspended) {
            // A run parked on calls to the conversation's tools goes on where it stopped: the calls' results were
            // just given, and the blank prompt adds nothing.
            readRun(emitter, ctx);
            return;
        }
        List<String> cmd = buildCommand();
        logger.fine(() -> "Starting codex: " + String.join(" ", cmd));
        ProcessBuilder pb = launch(cmd, ctx.apiKey()).processBuilder();

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex could not be started", e);
            emitter.accept(ChatEvent.error("codex could not be started: " + e.getMessage()));
            return;
        }
        events.clear();
        sawResult = false;
        sawAssistant = false;
        currentProcess = process;

        Thread.ofVirtual().start(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    String msg = line;
                    logger.fine(() -> "codex stderr: " + msg);
                }
            } catch (IOException ignored) { }
        });
        // The run's output, and the calls it parks, reach the turn through one queue, in the order they happen.
        Thread.ofVirtual().start(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    for (StreamEvent event : parser.parse(line)) events.offer(event);
                }
            } catch (IOException e) {
                logger.log(Level.FINE, "codex stdout closed", e);
            }
            events.offer(StreamEvent.text(OUTPUT_CLOSED, null));
        });

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex stdin could not be written", e);
        }
        readRun(emitter, ctx);
    }

    /** Reads the run's events until it ends, or until it is parked on calls to the conversation's tools. */
    private void readRun(Consumer<ChatEvent> emitter, ProviderContext ctx) {
        suspended = false;
        Process process = currentProcess;
        try {
            while (true) {
                StreamEvent event = events.poll(60, TimeUnit.SECONDS);
                if (event == null) {
                    if (process != null && process.isAlive()) continue;
                    break;
                }
                if (OUTPUT_CLOSED.equals(event.type())) break;
                ctx.onActivity().run();
                handle(event, emitter);
                if (StreamEvent.PARKED_CALL.equals(event.type())) {
                    // The calls Codex makes alongside this one arrive within a moment; they are handed over
                    // together, as one step's calls.
                    StreamEvent more;
                    while ((more = events.poll(CliProcess.PARK_SETTLE_MS, TimeUnit.MILLISECONDS)) != null) {
                        if (OUTPUT_CLOSED.equals(more.type())) {
                            events.offer(more);
                            break;
                        }
                        handle(more, emitter);
                    }
                    if (!parked.isEmpty()) {
                        suspended = true;
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int exit;
        try {
            exit = process == null ? -1 : process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        } finally {
            currentProcess = null;
        }
        answerAllParked();
        if (!sawResult && !sawAssistant && exit != 0) {
            emitter.accept(ChatEvent.error("codex exited with status " + exit + " before completing the turn"));
        }
        emitter.accept(ChatEvent.result(threadId, -1, -1, this.model, false));
    }

    /** Passes one event of the run on to the conversation. */
    private void handle(StreamEvent event, Consumer<ChatEvent> emitter) {
        switch (event.type()) {
            case "system" -> {
                if (event.sessionId() != null) saveSession(event.sessionId());
            }
            case "assistant" -> {
                if (event.hasContent()) {
                    sawAssistant = true;
                    emitter.accept(ChatEvent.delta(event.content()));
                }
            }
            case "thinking" -> emitter.accept(ChatEvent.thinking(event.hasContent() ? event.content() : "Thinking..."));
            case StreamEvent.PARKED_CALL -> emitter.accept(ChatEvent.toolCall(event.toolUseId(), event.toolName(),
                    event.content() == null ? "{}" : event.content()));
            case "tool_use" -> {
                // A call to the conversation's own tools is reported by the MCP request instead, and recorded once
                // by the conversation as its own step.
                if (event.toolName() != null && event.toolName().startsWith(MCP_TOOL_PREFIX)) {
                    ownToolUseIds.add(event.toolUseId());
                    return;
                }
                emitter.accept(ChatEvent.toolUse(event.toolUseId(), event.toolName(), event.content()));
            }
            case "tool_result" -> {
                if (ownToolUseIds.remove(event.toolUseId())) return;
                emitter.accept(ChatEvent.toolResult(event.toolUseId(),
                        event.content() == null ? "" : event.content(), event.isError()));
            }
            case "error" -> emitter.accept(ChatEvent.error(event.content()));
            case "result" -> sawResult = true;
            default -> { }
        }
    }

    /** The prefix of the names {@link CodexEventParser} gives Codex's calls to chat-ui's MCP server. */
    static final String MCP_TOOL_PREFIX = ConversationMcp.SERVER_NAME + "/";

    /** The variable the conversation's MCP key reaches Codex in; the command line would show it to every user. */
    static final String MCP_KEY_VARIABLE = "CHAT_UI_MCP_KEY";

    /**
     * @param cmd    the command line {@link #buildCommand()} made
     * @param apiKey the conversation's API key, or {@code null}
     * @return how one turn's codex process is started: {@code OPENAI_API_KEY} when a key is set, the conversation's
     *         MCP key when it has chat-ui's MCP server, and the conversation's working directory
     */
    HarnessLaunch launch(List<String> cmd, String apiKey) {
        java.util.Map<String, String> added = new java.util.HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) added.put("OPENAI_API_KEY", apiKey);
        if (mcp != null) added.put(MCP_KEY_VARIABLE, mcp.key());
        return new HarnessLaunch(cmd, List.of(), added, workingDir);
    }

    /** The command of one turn: a fresh thread, or a resume of the one this conversation holds. */
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add("exec");
        if (threadId != null) {
            cmd.add("resume");
            cmd.add(threadId);
        }
        cmd.add("--json");
        cmd.add("--skip-git-repo-check");
        cmd.add("-c");
        cmd.add("sandbox_mode=\"" + SANDBOX_MODE + "\"");
        if (model != null && !model.isBlank()) { cmd.add("-m"); cmd.add(model); }
        if (mcp != null) {
            // https://developers.openai.com/codex/mcp: a streamable HTTP server, its bearer token from an
            // environment variable, its tools run without asking, and a call waits as long as the conversation runs it.
            String server = "mcp_servers." + ConversationMcp.SERVER_NAME + ".";
            cmd.add("-c"); cmd.add(server + "url=" + tomlString(mcp.url()));
            cmd.add("-c"); cmd.add(server + "bearer_token_env_var=" + tomlString(MCP_KEY_VARIABLE));
            cmd.add("-c"); cmd.add(server + "http_headers={" + tomlString(ConversationMcp.SCOPE_HEADER) + "="
                    + tomlString(mcp.scope()) + "}");
            cmd.add("-c"); cmd.add(server + "default_tools_approval_mode=\"approve\"");
            cmd.add("-c"); cmd.add(server + "tool_timeout_sec=86400");
        }
        String instructions = developerInstructions;
        if (instructions != null && !instructions.isBlank()) {
            cmd.add("-c");
            cmd.add("developer_instructions=" + tomlString(instructions));
        }
        cmd.add("-");
        return cmd;
    }

    /** @return {@code text} as a TOML basic string, which {@code -c} parses */
    static String tomlString(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---- Session persistence ----

    private void restoreSession() {
        try {
            if (!Files.exists(sessionFile)) return;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) {
                    String id = line.substring("sessionId=".length()).trim();
                    if (!id.isEmpty()) threadId = id;
                } else if (line.startsWith("model=")) {
                    String m = line.substring("model=".length()).trim();
                    if (!m.isEmpty()) model = m;
                }
            }
            if (threadId != null) logger.info("Restored codex thread " + threadId + " from " + sessionFile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file " + sessionFile, e);
        }
    }

    private void saveSession(String id) {
        threadId = id;
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile, "sessionId=" + id + "\nmodel=" + (model == null ? "" : model) + "\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file " + sessionFile, e);
        }
    }
}
