package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.kernel.BlockingJob;
import com.scivicslab.chatui.kernel.EventQueue;
import com.scivicslab.chatui.kernel.StateOwner;
import com.scivicslab.chatui.plugin.ConversationMcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>A process that fails shows its stderr and still ends the turn. A resume whose thread Codex no longer has
 * is retried once as a fresh conversation, and a model Codex does not accept is dropped for Codex's own default,
 * once. The {@code /model} and {@code /effort} commands set the model and the reasoning effort of the turns that
 * follow; an image data URL is written to a temporary file and passed with {@code -i}.</p>
 *
 * <p>Codex runs its own tools: its command executions, file changes and MCP calls are reported
 * as {@code tool_use}/{@code tool_result} events for the conversation to record. Codex has no
 * switch that disables those tools, so this provider only exists with the harness tool
 * set.</p>
 */
public class CodexProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(CodexProvider.class.getName());

    /** How much of a failed run's stderr is kept to show. */
    private static final int STDERR_LIMIT = 16_384;

    public static final String ID = "codex";

    /** Shared because every conversation sees the same Codex installation and account. */
    private static final CodexModelCatalog MODEL_CATALOG = new CodexModelCatalog();

    /** Codex's sandbox for the commands it runs; the project's working directory is what it may write. */
    private static final String SANDBOX_MODE = "workspace-write";

    private final Path sessionFile;
    private final Path workingDir;
    private final CodexEventParser parser = new CodexEventParser();
    private String model;
    private String effort;
    private String threadId;
    private final ConversationMcp mcp;
    private final List<String> commandPrefix;
    /**
     * What several threads share of a run: the events of the run and the calls it parks, the process, what was said on
     * stderr, the flags the cancel and the turn read, and a copy of the session's settings that other threads read. A plain
     * object for one owner, an actor: the readers of the process's output tell it, the turn waits on it, and the
     * conversation's cancel and answers go through it.
     */
    static final class CodexState {
        /** The run's events and the calls it parks, in the order they happen. */
        final EventQueue<StreamEvent> events = new EventQueue<>();
        /** Calls to chat-ui's tools the run waits on, by the id of their {@link StreamEvent#parkedCall} event. */
        final Map<String, CompletableFuture<String>> parked = new java.util.LinkedHashMap<>();
        long seq;
        /** The ids of the run's calls to the conversation's own tools, whose results are not passed on. */
        final Set<String> ownToolUseIds = new java.util.HashSet<>();
        /** The run is parked on calls: {@link #sendPrompt} returned before its end. */
        boolean suspended;
        /** Set by {@link #cancel}: a turn the person stopped shows no failure of its own. */
        boolean cancelled;
        Process process;
        StringBuilder stderr = new StringBuilder();
        /** The session's settings as the turn last told them; {@link #getCurrentModel} and the like read these. */
        String model;
        String effort;
        String threadId;
        /** The turn's image files, kept while its run goes on, also while it is parked on calls; the cancel closes them too. */
        PreparedImages heldImages;

        CompletableFuture<String> park(String name, String argumentsJson) {
            CompletableFuture<String> result = new CompletableFuture<>();
            if (process == null || !process.isAlive()) {
                result.complete("error: no turn of this conversation is running, so the call was not run");
                return result;
            }
            String id = "chat-ui-call-" + (++seq);
            parked.put(id, result);
            events.offer(StreamEvent.parkedCall(id, name, argumentsJson));
            return result;
        }

        void appendStderr(String line) {
            if (stderr.length() < STDERR_LIMIT) {
                if (!stderr.isEmpty()) stderr.append('\n');
                stderr.append(line, 0, Math.min(line.length(), STDERR_LIMIT - stderr.length()));
            }
        }
    }

    private final StateOwner<CodexState> state = new StateOwner<>("codex-run", new CodexState());

    private String developerInstructions;
    private boolean sawResult;
    /** The errors the run reported, shown when the turn ends unless the run is retried; the turn's own thread alone uses it. */
    private final List<String> runErrors = new ArrayList<>();
    private BlockingJob<Void> stderrReader;
    private long turnStartedAt;

    private void putModel(String value) {
        model = value;
        publishSession();
    }

    private void putEffort(String value) {
        effort = value;
        publishSession();
    }

    private void putThreadId(String value) {
        threadId = value;
        publishSession();
    }

    private void publishSession() {
        String m = model, e = effort, id = threadId;
        state.tell(s -> {
            s.model = m;
            s.effort = e;
            s.threadId = id;
        });
    }

    private boolean isCancelled() {
        return state.ask(s -> s.cancelled);
    }

    /**
     * @param settings   process-wide harness settings
     * @param projectId  owning project's id, part of the session file name
     * @param chatId     conversation id, part of the session file name
     * @param workingDir the directory Codex works in, or {@code null} for this process's own
     */
    public CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir) {
        this(settings, projectId, chatId, workingDir, (ConversationMcp) null);
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
        this(settings, projectId, chatId, workingDir, mcp, List.of("codex"));
    }

    /** As the public constructor, running {@code binary} instead of {@code codex}: a test's fake CLI. */
    CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir,
                  ConversationMcp mcp, String binary) {
        this(settings, projectId, chatId, workingDir, mcp, List.of(binary));
    }

    /** As the public constructor, running {@code commandPrefix} instead of {@code codex}: a test's fake CLI. */
    CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir,
                  List<String> commandPrefix) {
        this(settings, projectId, chatId, workingDir, null, commandPrefix);
    }

    private CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir,
                          ConversationMcp mcp, List<String> commandPrefix) {
        this.commandPrefix = List.copyOf(commandPrefix);
        this.sessionFile = settings.sessionFile(projectId, chatId, ID);
        this.workingDir = workingDir;
        putModel(blankToNull(settings.codexModel()));
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
        if (model == null && snapshot.defaultModel() != null) {
            putModel(snapshot.defaultModel());
        }
        return snapshot.models();
    }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.CODEX_CLI; }
    @Override public String getCurrentModel() { return state.ask(s -> s.model); }
    @Override public void setModel(String model) { putModel(blankToNull(model)); }
    @Override public String getSessionId() { return state.ask(s -> s.threadId); }
    @Override public String detectEnvApiKey() { return System.getenv("OPENAI_API_KEY"); }

    @Override
    public void cancel() {
        state.run(s -> {
            s.cancelled = true;
            s.suspended = false;
        });
        closeImages();
        Process p = state.ask(s -> s.process);
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
        return state.ask(s -> s.park(name, argumentsJson));
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
        state.run(s -> s.suspended = false);
        closeImages();
        Process p = state.ask(s -> s.process);
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
        CompletableFuture<String> call = state.ask(s -> s.parked.remove(callId));
        if (call != null) call.complete(text);
    }

    private void answerAllParked() {
        for (String id : state.ask(s -> List.copyOf(s.parked.keySet()))) answer(id, CliProcess.TURN_ENDED);
    }

    /** The type of the event the output reader leaves when the run's output closes. */
    private static final String OUTPUT_CLOSED = "output_closed";

    @Override
    public void sendPrompt(String prompt, String requestedModel, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        if (requestedModel != null && !requestedModel.isBlank()) putModel(requestedModel);
        if (state.ask(s -> s.suspended)) {
            // A run parked on calls to the conversation's tools goes on where it stopped: the calls' results were
            // just given, and the blank prompt adds nothing. Only a run's start can be retried, so there is no
            // retry here.
            RunEnd end = readRun(emitter, ctx);
            if (end != null) finishTurn(end, emitter);
            return;
        }
        state.run(s -> s.cancelled = false);
        turnStartedAt = System.nanoTime();
        boolean mayRetrySession = threadId != null;
        boolean mayRetryModel = model != null;
        closeImages();
        try {
            PreparedImages prepared = PreparedImages.from(ctx.imageDataUrls());
            state.run(s -> s.heldImages = prepared);
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex image could not be prepared", e);
            finishTurn(RunEnd.failed("Codex image could not be prepared: " + e.getMessage()), emitter);
            return;
        }
        while (true) {
            RunEnd end = startRun(prompt, ctx, emitter);
            if (end == null) return; // parked: the turn goes on in a later call of this method
            if (!isCancelled() && mayRetryModel && end.isUnsupportedModel()) {
                logger.warning("Selected Codex model is unavailable; retrying with Codex's default model");
                putModel(null);
                mayRetryModel = false;
                emitter.accept(ChatEvent.thinking("Selected model is unavailable. Using the Codex default..."));
                continue;
            }
            if (!isCancelled() && mayRetrySession && end.isStaleSession()) {
                logger.warning("Codex thread is no longer available; retrying as a new thread");
                clearSession();
                mayRetrySession = false;
                emitter.accept(ChatEvent.thinking("Session expired. Starting a new Codex session..."));
                continue;
            }
            finishTurn(end, emitter);
            return;
        }
    }

    /**
     * Starts one codex process for the turn and reads its run.
     *
     * @return how the run ended, or {@code null} when it is parked on calls to the conversation's tools
     */
    private RunEnd startRun(String prompt, ProviderContext ctx, Consumer<ChatEvent> emitter) {
        List<String> cmd = buildCommand(state.ask(s -> s.heldImages == null ? List.<Path>of() : s.heldImages.paths()));
        logger.fine(() -> "Starting codex: " + String.join(" ", cmd));
        ProcessBuilder pb = launch(cmd, ctx.apiKey()).processBuilder();

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex could not be started", e);
            return RunEnd.failed("codex could not be started: " + e.getMessage());
        }
        sawResult = false;
        runErrors.clear();
        state.run(s -> {
            s.events.clear();
            s.stderr = new StringBuilder();
            s.process = process;
        });

        stderrReader = BlockingJob.start("codex-stderr", () -> { readStderr(process, state); return null; }, process::destroy);
        // The run's output, and the calls it parks, reach the turn through one queue, in the order they happen.
        BlockingJob.start("codex-stdout", () -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    for (StreamEvent event : parser.parse(line)) state.tell(s -> s.events.offer(event));
                }
            } catch (IOException e) {
                logger.log(Level.FINE, "codex stdout closed", e);
            }
            state.tell(s -> s.events.offer(StreamEvent.text(OUTPUT_CLOSED, null)));
            return null;
        }, process::destroy);

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex stdin could not be written", e);
        }
        return readRun(emitter, ctx);
    }

    /**
     * Reads the run's events until it ends, or until it is parked on calls to the conversation's tools.
     *
     * @return how the run ended, or {@code null} when it is parked
     */
    private RunEnd readRun(Consumer<ChatEvent> emitter, ProviderContext ctx) {
        state.run(s -> s.suspended = false);
        Process process = state.ask(s -> s.process);
        try {
            while (true) {
                StreamEvent event = state.poll(s -> s.events, 60_000);
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
                    while ((more = state.poll(s -> s.events, CliProcess.PARK_SETTLE_MS)) != null) {
                        if (OUTPUT_CLOSED.equals(more.type())) {
                            StreamEvent closed = more;
                            state.run(s -> s.events.offer(closed));
                            break;
                        }
                        handle(more, emitter);
                    }
                    if (state.ask(s -> !s.parked.isEmpty())) {
                        state.run(s -> s.suspended = true);
                        return null;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int exit;
        try {
            exit = process == null ? -1 : process.waitFor();
            BlockingJob<Void> reader = stderrReader;
            if (reader != null) {
                try {
                    reader.result().get(1_000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException ignored) {
                    // The run's stderr is only what is shown with its failure; what was read so far is used.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroy();
            exit = -1;
        } finally {
            state.run(s -> s.process = null);
        }
        answerAllParked();
        List<String> errors = List.copyOf(runErrors);
        String stderr = state.ask(s -> s.stderr.toString().trim());
        return new RunEnd(sawResult, exit, stderr, errors);
    }

    /** Shows what the run reported and ends the turn: the errors, then the result. */
    private void finishTurn(RunEnd end, Consumer<ChatEvent> emitter) {
        closeImages();
        if (!isCancelled()) {
            Set<String> errors = new LinkedHashSet<>(end.errors());
            if (!end.completed() && errors.isEmpty()) {
                errors.add(end.stderr().isBlank()
                        ? "Codex exited before completing the turn (status " + end.exitCode() + ")"
                        : "Codex failed: " + end.stderr());
            }
            errors.forEach(message -> emitter.accept(ChatEvent.error(message)));
        }
        long durationMs = (System.nanoTime() - turnStartedAt) / 1_000_000;
        emitter.accept(ChatEvent.result(threadId, -1, durationMs, this.model, false));
    }

    private void closeImages() {
        PreparedImages images = state.ask(s -> {
            PreparedImages held = s.heldImages;
            s.heldImages = null;
            return held;
        });
        if (images != null) images.close();
    }

    /** Passes one event of the run on to the conversation. */
    private void handle(StreamEvent event, Consumer<ChatEvent> emitter) {
        switch (event.type()) {
            case "system" -> {
                if (event.sessionId() != null) saveSession(event.sessionId());
            }
            case "assistant" -> {
                if (event.hasContent()) emitter.accept(ChatEvent.delta(event.content()));
            }
            case "thinking" -> emitter.accept(ChatEvent.thinking(event.hasContent() ? event.content() : "Thinking..."));
            case StreamEvent.PARKED_CALL -> emitter.accept(ChatEvent.toolCall(event.toolUseId(), event.toolName(),
                    event.content() == null ? "{}" : event.content()));
            case "tool_use" -> {
                // A call to the conversation's own tools is reported by the MCP request instead, and recorded once
                // by the conversation as its own step.
                if (event.toolName() != null && event.toolName().startsWith(MCP_TOOL_PREFIX)) {
                    state.run(s -> s.ownToolUseIds.add(event.toolUseId()));
                    return;
                }
                emitter.accept(ChatEvent.toolUse(event.toolUseId(), event.toolName(), event.content()));
            }
            case "tool_result" -> {
                if (state.ask(s -> s.ownToolUseIds.remove(event.toolUseId()))) return;
                emitter.accept(ChatEvent.toolResult(event.toolUseId(),
                        event.content() == null ? "" : event.content(), event.isError()));
            }
            case "error" -> {
                runErrors.add(event.content());
            }
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
        return buildCommand(List.of());
    }

    /** As {@link #buildCommand()}, with each of {@code images} passed as {@code -i <file>}. */
    List<String> buildCommand(List<Path> images) {
        List<String> cmd = new ArrayList<>(commandPrefix);
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
        if (effort != null) {
            cmd.add("-c");
            cmd.add("model_reasoning_effort=\"" + effort + "\"");
        }
        for (Path image : images) {
            cmd.add("-i");
            cmd.add(image.toString());
        }
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

    // ---- Commands typed into the conversation ----

    @Override public boolean isCommand(String input) { return input != null && input.startsWith("/"); }

    /**
     * {@code /model [name]}, {@code /effort [level]}, {@code /session [id]}, {@code /clear} and {@code /help}. A model
     * named here is used as given: the catalog of {@link #getAvailableModels} only lists what Codex offers, and a
     * name Codex refuses is dropped for its default on the next turn.
     */
    @Override
    public List<ChatEvent> handleCommand(String input) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length == 2 ? parts[1].trim() : "";
        return switch (command) {
            case "/model" -> {
                if (!arg.isEmpty()) putModel(arg);
                yield List.of(ChatEvent.info(arg.isEmpty() ? "Current model: " + shown(model) : "Model changed to: " + model));
            }
            case "/effort" -> handleEffort(arg);
            case "/session" -> {
                if (!arg.isEmpty()) saveSession(arg);
                yield List.of(ChatEvent.info(arg.isEmpty()
                        ? (threadId == null ? "No active session." : "Current session: " + threadId)
                        : "Session set to: " + threadId));
            }
            case "/clear" -> {
                cancel();
                clearSession();
                yield List.of(ChatEvent.info("Session cleared. Starting fresh conversation."));
            }
            case "/help", "/?" -> List.of(ChatEvent.info("""
                    Available commands:
                      /help, /?          Show this help
                      /model [name]      Show or change the model
                      /effort [level]    Show or change reasoning effort
                      /session [id]      Show or set the Codex thread ID
                      /clear             Start a fresh Codex conversation"""));
            default -> List.of(ChatEvent.error("Unknown command: " + command + " (type /help for available commands)"));
        };
    }

    private List<ChatEvent> handleEffort(String arg) {
        if (arg.isEmpty()) return List.of(ChatEvent.info("Current effort: " + shown(effort)));
        String value = arg.toLowerCase(Locale.ROOT);
        if (!List.of("low", "medium", "high", "xhigh", "max").contains(value)) {
            return List.of(ChatEvent.error("Unknown effort level: " + arg));
        }
        putEffort(value);
        return List.of(ChatEvent.info("Effort changed to: " + effort));
    }

    // ---- Session persistence ----

    private void restoreSession() {
        try {
            if (!Files.exists(sessionFile)) return;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) putThreadId(blankToNull(line.substring("sessionId=".length()).trim()));
                else if (line.startsWith("model=")) putModel(blankToNull(line.substring("model=".length()).trim()));
                else if (line.startsWith("effort=")) putEffort(blankToNull(line.substring("effort=".length()).trim()));
            }
            if (threadId != null) logger.info("Restored codex thread " + threadId + " from " + sessionFile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file " + sessionFile, e);
        }
    }

    private void saveSession(String id) {
        if (id == null || id.isBlank()) return;
        putThreadId(id);
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile, "sessionId=" + id + "\nmodel=" + value(model)
                    + "\neffort=" + value(effort) + "\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file " + sessionFile, e);
        }
    }

    private void clearSession() {
        putThreadId(null);
        try {
            Files.deleteIfExists(sessionFile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete session file " + sessionFile, e);
        }
    }

    private static void readStderr(Process process, StateOwner<CodexState> state) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String message = line;
                logger.fine(() -> "codex stderr: " + message);
                state.tell(s -> s.appendStderr(message));
            }
        } catch (IOException ignored) { }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String shown(String value) { return value == null ? "unset (Codex default)" : value; }

    private static String value(String value) { return value == null ? "" : value; }

    /** How one codex process ended: whether it reported the turn complete, its exit status, its stderr and its errors. */
    private record RunEnd(boolean completed, int exitCode, String stderr, List<String> errors) {
        static RunEnd failed(String message) { return new RunEnd(false, -1, "", List.of(message)); }

        boolean isStaleSession() {
            String text = (stderr + "\n" + String.join("\n", errors)).toLowerCase(Locale.ROOT);
            return text.contains("no conversation found") || text.contains("session not found")
                    || text.contains("unable to find session") || text.contains("thread not found")
                    || text.contains("no rollout found for thread id");
        }

        boolean isUnsupportedModel() {
            String text = (stderr + "\n" + String.join("\n", errors)).toLowerCase(Locale.ROOT);
            return text.contains("model") && (text.contains("not supported")
                    || text.contains("unknown model") || text.contains("model is unavailable"));
        }
    }

    /** The turn's images as files: a data URL is written to a temporary file, which codex takes with {@code -i}. */
    private record PreparedImages(Path directory, List<Path> paths) implements AutoCloseable {
        static PreparedImages from(List<String> dataUrls) throws IOException {
            if (dataUrls == null || dataUrls.isEmpty()) return new PreparedImages(null, List.of());
            Path dir = Files.createTempDirectory("chat-ui-codex-images-");
            List<Path> paths = new ArrayList<>();
            try {
                for (int i = 0; i < dataUrls.size(); i++) {
                    String dataUrl = dataUrls.get(i);
                    int comma = dataUrl.indexOf(',');
                    if (!dataUrl.startsWith("data:image/") || comma < 0
                            || !dataUrl.substring(0, comma).contains(";base64")) {
                        throw new IOException("Codex image must be a base64 image data URL");
                    }
                    String media = dataUrl.substring(5, dataUrl.indexOf(';'));
                    String extension = switch (media) {
                        case "image/jpeg" -> ".jpg";
                        case "image/gif" -> ".gif";
                        case "image/webp" -> ".webp";
                        default -> ".png";
                    };
                    Path path = dir.resolve("image-" + i + extension);
                    Files.write(path, Base64.getDecoder().decode(dataUrl.substring(comma + 1)));
                    paths.add(path);
                }
                return new PreparedImages(dir, List.copyOf(paths));
            } catch (Exception e) {
                for (Path path : paths) Files.deleteIfExists(path);
                Files.deleteIfExists(dir);
                if (e instanceof IOException io) throw io;
                throw new IOException("Invalid Codex image data", e);
            }
        }

        @Override public void close() {
            try {
                for (Path path : paths) Files.deleteIfExists(path);
                if (directory != null) Files.deleteIfExists(directory);
            } catch (IOException e) {
                logger.log(Level.FINE, "Failed to remove temporary Codex image", e);
            }
        }
    }
}
