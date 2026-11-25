package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.kernel.BlockingJob;
import com.scivicslab.chatui.kernel.EventQueue;
import com.scivicslab.chatui.kernel.StateOwner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Manages a CLI LLM subprocess (claude, codex, etc.) using stream-json I/O.
 *
 * <p>The binary name and API key environment variable are supplied at construction
 * time, making this class usable by both Claude and Codex providers without
 * duplication.</p>
 *
 * <p>With {@code --input-format stream-json}, the process stays alive across turns.
 * {@link #sendPrompt} writes to stdin and reads stdout until a result event arrives.</p>
 */
public class CliProcess {

    private static final Logger logger = Logger.getLogger(CliProcess.class.getName());

    private static final Pattern ANSI_PATTERN = Pattern.compile(
        "\\x1b\\[[0-9;?]*[a-zA-Z]|\\x1b\\][^\u0007]*\u0007|\\x1b[^\\[\\]].?"
    );

    private final String binary;
    private final String apiKeyEnvVar;
    private final StreamEventParser parser = new StreamEventParser();

    /**
     * What several threads share of the process: the events of the prompt's turn and the autonomous ones, the calls
     * the CLI is parked on, and the flags that route them. It is a plain object for one owner, an actor: the reader of the
     * process's output tells it each event, the turn waits on it, and a call to chat-ui's tools parks through it.
     *
     * <p>Events produced while a prompt turn is in flight (between writeUserMessage and the turn's result event) go to
     * {@code turn} and are consumed by sendPrompt. Events produced while no prompt is active (the CLI emitting by itself:
     * a background job finishing, a ScheduleWakeup firing) go to {@code autonomous} and are consumed by
     * pollAutonomousEvent. Routing by {@code turnActive} keeps autonomous output from being mis-delivered as the response
     * to the next prompt.</p>
     */
    static final class CliState {
        final EventQueue<StreamEvent> turn = new EventQueue<>();
        final EventQueue<StreamEvent> autonomous = new EventQueue<>();
        /** Calls to chat-ui's tools the CLI waits on, by the id of their {@link StreamEvent#parkedCall} event. */
        final java.util.Map<String, java.util.concurrent.CompletableFuture<String>> parked = new java.util.LinkedHashMap<>();
        long seq;
        /** The turn is parked: {@link CliProcess#sendPrompt} returned {@link CliProcess#SUSPENDED} and has not been resumed. */
        boolean suspended;
        boolean turnActive;
        /** True from the first event of a turn the CLI started by itself until that turn's result. */
        boolean autonomousTurnOpen;
        /** The process's options (model, effort, session id ...): set by the conversation's setters and by the turn. */
        CliConfig config;
        /** The job that reads the process's stdout; it ends when the output closes. */
        BlockingJob<Void> readerJob;
        /** The CLI process, or {@code null} when none runs, and its standard input. */
        Process process;
        OutputStream stdin;
        /** Counted up by each {@link CliProcess#cancel()}: a start that began before a cancel sees it and gives up. */
        long cancels;
        /** The session id of the latest event that carried one: written by the output reader, read by the provider. */
        String lastSessionId;
        /** The model value the running process was started with, so a changed model or window can be seen between turns. */
        String launchedModel;

        void route(StreamEvent event) {
            boolean isResult = "result".equals(event.type());
            if (!turnActive) {
                autonomous.offer(event);
                autonomousTurnOpen = !isResult;
            } else if (autonomousTurnOpen) {
                // A prompt was written while the CLI was part-way through a turn it started by itself.
                // The CLI answers the two turns in order, so the events up to the first result still
                // belong to the autonomous turn; the prompt's own events follow.
                autonomous.offer(event);
                if (isResult) autonomousTurnOpen = false;
            } else {
                turn.offer(event);
                if (isResult) turnActive = false;
            }
        }

        /** @return the future of the parked call, complete at once when no turn of the conversation is running */
        java.util.concurrent.CompletableFuture<String> park(String tool, String argumentsJson) {
            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            if (!turnActive) {
                // A turn the CLI started by itself (a background job, a wake-up) is no turn of the conversation.
                result.complete("error: no turn of this conversation is running, so the call was not run");
                return result;
            }
            String id = "chat-ui-call-" + (++seq);
            parked.put(id, result);
            turn.offer(StreamEvent.parkedCall(id, tool, argumentsJson));
            return result;
        }

        /** Forgets the prompt's turn. */
        void forgetTurn() {
            turnActive = false;
            autonomousTurnOpen = false;
            turn.clear();
            autonomous.clear();
        }
    }

    private final StateOwner<CliState> state = new StateOwner<>("cli-process", new CliState());

    /** For a test: run when a start has counted the cancels so far, so a cancel sent after it is one that came during the start. */
    Runnable startBegan;

    private Process currentProcess() {
        return state.ask(s -> s.process);
    }

    private OutputStream stdin() throws IOException {
        OutputStream out = state.ask(s -> s.stdin);
        if (out == null) throw new IOException("No active process stdin");
        return out;
    }

    /** {@code --mcp-config}'s value, or {@code null} for none. Set and read inside the provider's own messages. */
    private String mcpConfigJson;
    /** {@code --append-system-prompt}'s value, or {@code null} for none. */
    private String appendSystemPrompt;
    private String apiKey;

    /**
     * Creates a new CLI process manager.
     *
     * @param binary       the CLI binary name (e.g. "claude", "codex")
     * @param apiKeyEnvVar the environment variable name used to pass the API key to the process
     * @param config       the initial configuration for the subprocess
     */
    public CliProcess(String binary, String apiKeyEnvVar, CliConfig config) {
        this.binary = binary;
        this.apiKeyEnvVar = apiKeyEnvVar;
        state.run(s -> s.config = config);
    }

    /**
     * Returns the current configuration.
     *
     * @return the active {@link CliConfig}
     */
    public CliConfig getConfig() { return state.ask(s -> s.config); }

    /**
     * Replaces the current configuration. Takes effect on the next process start.
     *
     * @param config the new configuration
     */
    public void setConfig(CliConfig config) { state.run(s -> s.config = config); }

    /**
     * Changes the options as one step of the owner: a setter on any thread and the turn's own update of the session id
     * cannot overwrite each other.
     */
    public void updateConfig(java.util.function.UnaryOperator<CliConfig> change) { state.run(s -> s.config = change.apply(s.config)); }

    /**
     * Sets the API key to be passed to the subprocess via its environment variable.
     *
     * @param key the API key value
     */
    public void setApiKey(String key) { this.apiKey = key; }

    /**
     * Returns the session ID from the most recent result event, or {@code null} if none.
     *
     * @return the last known session ID
     */
    public String getLastSessionId() { return state.ask(s -> s.lastSessionId); }

    /**
     * Clears the last known session ID. Called on /clear so the UI shows no session
     * until the next prompt establishes a new one.
     */
    public void clearLastSessionId() { state.run(s -> s.lastSessionId = null); }

    /**
     * Checks whether the underlying OS process is still running.
     *
     * @return {@code true} if the process is alive
     */
    public boolean isAlive() {
        Process p = currentProcess();
        return p != null && p.isAlive();
    }

    private boolean startProcess(long cancelsAtStart) throws IOException {
        String launchedModel = getConfig().model();
        List<String> cmd = buildCommand();
        logger.fine(() -> "Starting CLI: " + String.join(" ", cmd));

        Process started = launch(cmd).processBuilder().start();
        // The process is the owner's from now on. A cancel that came while it was being started is not lost: the owner
        // counts cancels, sees one since this start began, and the process is stopped here instead of being kept.
        boolean kept = state.ask(s -> {
            if (s.cancels != cancelsAtStart) return false;
            s.process = started;
            s.stdin = started.getOutputStream();
            s.launchedModel = launchedModel;
            return true;
        });
        if (!kept) {
            started.destroy();
            logger.info(binary + " CLI process cancelled while it started");
            return false;
        }
        BufferedReader stdoutReader =
                new BufferedReader(new InputStreamReader(started.getInputStream()));

        // Background thread: read stdout continuously and route parsed events into the turn or
        // autonomous queue. Keeps running while the process is alive, picking up both prompted and
        // autonomous (background-job / ScheduleWakeup) turns.
        BlockingJob<Void> stdoutJob = BlockingJob.start("cli-stdout", () -> {
            try {
                String line;
                while ((line = stdoutReader.readLine()) != null) {
                    line = stripAnsi(line).trim();
                    if (line.isEmpty() || !line.startsWith("{")) continue;
                    String logLine = line;
                    logger.info(() -> "stdout: " + logLine);
                    for (StreamEvent event : parser.parse(line)) {
                        // Kept from the init event too, not only from a turn's result: a turn that is
                        // interrupted before its result would otherwise leave no session to resume, and
                        // the next prompt would start a new session without the interrupted steps
                        // (TimekeeperPausesATurnForAnUrgentRequest_261107_oo01).
                        if (("result".equals(event.type()) || "system".equals(event.type()))
                                && event.sessionId() != null) {
                            String sessionId = event.sessionId();
                            state.tell(s -> s.lastSessionId = sessionId);
                        }
                        routeEvent(event);
                    }
                }
            } catch (IOException ignored) {}
            // The output closed: the process is ending. A prompt still waiting for its result is told at once
            // rather than at its next 60-second poll (TurnWithoutAnAnswerTellsTheAsker_261119_oo01).
            // Only for the process this reader reads: a process cancelled earlier must not end a later prompt.
            state.tell(s -> { if (s.process == started && s.turnActive) s.turn.offer(StreamEvent.text(OUTPUT_CLOSED, null)); });
            return null;
        }, started::destroy);
        state.run(s -> s.readerJob = stdoutJob);

        // stderr is only logged; its job ends with the process and nobody waits for it.
        Process proc = started;
        BlockingJob.start("cli-stderr", () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String msg = line;
                    logger.fine(() -> "stderr: " + msg);
                }
            } catch (IOException ignored) {}
            return null;
        }, proc::destroy);
        return true;
    }

    /**
     * Sends a prompt and streams response events to the callback.
     * The process stays alive after a turn completes.
     *
     * <p>Writes the user message to stdin, then reads events from {@code turnQueue}
     * (filled by the background reader thread while the turn is active) until a
     * {@code result} event is received.</p>
     *
     * @throws EndedWithoutResult when the process ended before the prompt's {@code result}
     */
    public int sendPrompt(String prompt, StreamCallback callback) throws IOException {
        return sendPrompt(prompt, List.of(), callback);
    }

    /**
     * Sends a prompt with attached images and streams response events to the callback.
     * See {@link #sendPrompt(String, StreamCallback)} for the turn protocol; {@code imageDataUrls}
     * are data URLs (e.g. {@code data:image/png;base64,...}) rendered as Claude image content
     * blocks alongside the text.
     */
    public int sendPrompt(String prompt, List<String> imageDataUrls, StreamCallback callback) throws IOException {
        // A model or window chosen since the process started reaches it only by starting it again (--model and the 1M switch
        // are read at start), so between turns the process is stopped here and started below, resuming the session.
        boolean changed = state.ask(s -> s.process != null && s.process.isAlive() && !s.suspended && !s.turnActive
                && ClaudeModelChoice.needsRestart(s.launchedModel, s.config.model()));
        if (changed) restartBeforeNextPrompt();
        Process running = currentProcess();
        if (running == null || !running.isAlive()) {
            long cancelsAtStart = state.ask(s -> s.cancels);
            if (startBegan != null) startBegan.run();
            state.run(s -> {
                s.turn.clear();
                s.autonomous.clear();
                s.autonomousTurnOpen = false;
            });
            if (!startProcess(cancelsAtStart)) {
                if (callback != null) callback.onComplete(-1);
                return -1;
            }
        }

        // Mark the turn active before writing so the reader routes the response to turnQueue.
        beginTurn();
        writeUserMessage(prompt, imageDataUrls);
        return readTurn(callback);
    }

    /**
     * {@link #sendPrompt} returned this: the CLI is parked on calls to chat-ui's tools, which the callback was given
     * as {@link StreamEvent#parkedCall} events. Its turn goes on after {@link #answer} and {@link #resume}.
     */
    public static final int SUSPENDED = 1;

    /** How long Claude Code waits for one call to chat-ui's tools: a day, as long as any step of a turn. */
    static final long MCP_TOOL_TIMEOUT_MS = 24L * 60 * 60 * 1000;

    /** Claude Code's variable for the prompt cache lifetime of the main conversation: {@code 5m} or {@code 1h}. */
    static final String PROMPT_CACHE_TTL_ENV = "CLAUDE_CODE_PROMPT_CACHE_TTL";

    /** How long, after one parked call, the calls the CLI makes alongside it are waited for. */
    static final long PARK_SETTLE_MS = 300;

    /** What a call still parked when the conversation's turn ends receives as its result. */
    static final String TURN_ENDED = "error: this conversation's turn has ended before the call ran (it asked the "
            + "person, reached its step limit, or was paused for an urgent request). Do not go on; the conversation "
            + "sends a new message when the work continues.";

    /**
     * Reads the turn's events after {@link #sendPrompt} returned {@link #SUSPENDED} and the parked calls were
     * answered, until its result or the next parked call.
     *
     * @return 0 at the turn's result, {@link #SUSPENDED} when the CLI is parked on calls again, -1 when interrupted
     * @throws EndedWithoutResult when the process ended before the turn's {@code result}
     */
    public int resume(StreamCallback callback) throws IOException {
        if (!isSuspended()) throw new IllegalStateException("no turn of the CLI is parked");
        return readTurn(callback);
    }

    /** @return whether the CLI's turn is parked on calls to chat-ui's tools ({@link #SUSPENDED}) */
    public boolean isSuspended() { return state.ask(s -> s.suspended); }

    /**
     * Parks a call the CLI made to a tool of chat-ui's MCP server, from the thread that took the MCP request. The
     * running turn reports it as a {@link StreamEvent#parkedCall} event; the future completes with what
     * {@link #answer} gives, or with {@link #TURN_ENDED} when {@link #endTurn} comes first.
     *
     * @param tool          the tool's name
     * @param argumentsJson the arguments as a JSON object text
     * @return the observation the CLI receives
     */
    public java.util.concurrent.CompletableFuture<String> park(String tool, String argumentsJson) {
        return state.ask(s -> s.park(tool, argumentsJson));
    }

    /**
     * Gives a parked call its result; the CLI receives it as the MCP response.
     *
     * @param callId the id of the {@link StreamEvent#parkedCall} event
     * @param text   the observation
     */
    public void answer(String callId, String text) {
        java.util.concurrent.CompletableFuture<String> call = state.ask(s -> s.parked.remove(callId));
        if (call != null) call.complete(text);
    }

    /**
     * The conversation's turn has ended. Calls still parked receive {@link #TURN_ENDED}; a CLI turn not yet at its
     * result is interrupted and its events up to the result dropped. A CLI that does not reach its result within
     * {@code waitMs} is stopped; its session is kept, and the next prompt resumes it.
     *
     * @param waitMs how long to wait for the interrupted turn's result
     */
    public void endTurn(long waitMs) {
        state.run(s -> s.suspended = false);
        Process turnProcess = currentProcess();
        if (isTurnActive() && turnProcess != null && turnProcess.isAlive()) {
            try {
                writeInterrupt();
            } catch (IOException e) {
                logger.log(Level.WARNING, binary + " CLI: could not send the interrupt", e);
            }
        }
        answerAllParked();
        long deadline = System.currentTimeMillis() + waitMs;
        try {
            while (isTurnActive()) {
                long left = deadline - System.currentTimeMillis();
                StreamEvent event = left <= 0 ? null : pollTurnEvent(left);
                if (event == null || OUTPUT_CLOSED.equals(event.type())) break;
                if (StreamEvent.PARKED_CALL.equals(event.type())) answer(event.toolUseId(), TURN_ENDED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (isTurnActive()) {
            logger.warning(binary + " CLI did not end its interrupted turn in " + waitMs + " ms; stopping it");
            cancel();
        } else {
            // The interrupted turn's last events, its result included, belong to no prompt.
            state.run(s -> s.turn.clear());
        }
        answerAllParked();
    }

    private void answerAllParked() {
        for (String id : state.ask(s -> List.copyOf(s.parked.keySet()))) answer(id, TURN_ENDED);
    }

    /** Writes Claude Code's stream-json interrupt, which ends the running turn with a result. */
    void writeInterrupt() throws IOException {
        OutputStream stdinStream = stdin();
        String json = "{\"type\":\"control_request\",\"request_id\":\"interrupt-" + state.ask(s -> ++s.seq)
                + "\",\"request\":{\"subtype\":\"interrupt\"}}\n";
        stdinStream.write(json.getBytes(StandardCharsets.UTF_8));
        stdinStream.flush();
    }

    /** Reads the in-flight turn's events until its result, or until it is parked on calls. */
    private int readTurn(StreamCallback callback) throws IOException {
        state.run(s -> s.suspended = false);
        try {
            while (true) {
                StreamEvent event = pollTurnEvent(60_000);
                if (event == null) {
                    Process alive = currentProcess();
                    if (alive != null && alive.isAlive()) {
                        // Process is alive but slow (e.g. 529 retries in progress) — keep waiting.
                        // Do NOT return here: the response will eventually arrive and must reach the callback.
                        logger.info(binary + " CLI: no event in 60s but process is alive — continuing to wait...");
                        continue;
                    }
                    if (callback != null) callback.onComplete(-1);
                    throw endedWithoutResult();
                }
                if (OUTPUT_CLOSED.equals(event.type())) {
                    if (callback != null) callback.onComplete(-1);
                    throw endedWithoutResult();
                }
                if (callback != null) callback.onEvent(event);
                if ("result".equals(event.type())) return 0;
                if (StreamEvent.PARKED_CALL.equals(event.type())) {
                    // The calls the CLI makes alongside this one arrive within a moment; they are handed over
                    // together, as one step's calls.
                    StreamEvent more;
                    while ((more = pollTurnEvent(PARK_SETTLE_MS)) != null) {
                        if (OUTPUT_CLOSED.equals(more.type())) {
                            if (callback != null) callback.onComplete(-1);
                            throw endedWithoutResult();
                        }
                        if (callback != null) callback.onEvent(more);
                        if ("result".equals(more.type())) return 0;
                    }
                    state.run(s -> s.suspended = true);
                    return SUSPENDED;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (callback != null) callback.onComplete(-1);
            return -1;
        }
    }

    /** The type of the event the output reader leaves for a waiting prompt when the process's output closes. */
    static final String OUTPUT_CLOSED = "output_closed";

    /**
     * The process ended in the middle of a prompt, before it wrote the prompt's {@code result}. Whatever it was
     * doing for the prompt did not finish; the caller treats the prompt as failed
     * ({@code TurnWithoutAnAnswerTellsTheAsker_261119_oo01}).
     */
    public static final class EndedWithoutResult extends IOException {
        private final Integer exitCode;

        EndedWithoutResult(String binary, Integer exitCode) {
            super("the " + Path.of(binary).getFileName() + " CLI process ended without a result ("
                    + (exitCode == null ? "exit code unknown" : "exit code " + exitCode) + ")");
            this.exitCode = exitCode;
        }

        /** @return the process's exit code, or {@code null} when it had not exited within a few seconds */
        public Integer exitCode() { return exitCode; }
    }

    /** Forgets the prompt's turn and says how the process ended. */
    private EndedWithoutResult endedWithoutResult() {
        state.run(CliState::forgetTurn);
        Process p = currentProcess();
        Integer code = null;
        if (p != null) {
            try {
                if (p.waitFor(5, TimeUnit.SECONDS)) code = p.exitValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.warning(binary + " CLI ended in the middle of a prompt, exit code " + code);
        return new EndedWithoutResult(binary, code);
    }

    /**
     * Polls for the next autonomous event (one produced while no {@link #sendPrompt} turn was
     * active — e.g. a background job finishing or a ScheduleWakeup firing). Consumed by the
     * ChatActor's idle monitor to surface such output as its own assistant turn.
     *
     * @param timeoutMs maximum wait in milliseconds; 0 means non-blocking
     * @return the next autonomous event, or {@code null} if none arrived within the timeout
     */
    public StreamEvent pollAutonomousEvent(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) return state.ask(s -> s.autonomous.pollNow());
        return state.poll(s -> s.autonomous, timeoutMs);
    }

    /**
     * Returns whether any autonomous event is currently buffered. Non-blocking; safe to call
     * from any thread. The idle monitor uses this cheap check before committing to a drain.
     *
     * @return {@code true} if at least one autonomous event is waiting
     */
    public boolean hasAutonomousEvent() {
        return !state.ask(s -> s.autonomous.isEmpty());
    }

    /** Marks a prompt turn as started so subsequent events route to the turn queue. */
    void beginTurn() {
        state.run(s -> {
            s.turn.resume();
            s.turnActive = true;
        });
    }

    /** Returns whether a prompt turn is currently in flight (visible for testing). */
    boolean isTurnActive() { return state.ask(s -> s.turnActive); }

    /**
     * Routes one parsed event to the turn queue or the autonomous queue depending on whether a
     * prompt turn is in flight. The {@code result} event is the turn boundary: it belongs to the
     * active turn, and everything after it (until the next prompt) is autonomous. Flipping
     * {@code turnActive} here — in the single reader thread — closes the window in which a
     * post-result event could leak into the turn stream and be mis-delivered as the next prompt's
     * response. Uses {@code offer} on unbounded queues, so it never blocks.
     *
     * @param event the parsed stream event to route
     */
    void routeEvent(StreamEvent event) {
        state.tell(s -> s.route(event));
    }

    /**
     * Polls the turn queue for the next event of the in-flight prompt.
     *
     * @param timeoutMs maximum wait in milliseconds
     * @return the next turn event, or {@code null} if none arrived within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    StreamEvent pollTurnEvent(long timeoutMs) throws InterruptedException {
        return state.poll(s -> s.turn, timeoutMs);
    }

    /**
     * Destroys the running CLI process and resets all I/O handles.
     */
    public void cancel() {
        // The process goes first, and with it the stdin: a prompt that has not written yet then fails at once, and one that
        // has is ended by the interrupt below, which a later start of a turn cannot take back (it can only clear one that was
        // made before it).
        Process p = state.ask(s -> {
            s.cancels++;
            Process x = s.process;
            s.process = null;
            s.stdin = null;
            return x;
        });
        BlockingJob<Void> reader = state.ask(s -> {
            BlockingJob<Void> job = s.readerJob;
            s.readerJob = null;
            return job;
        });
        if (reader != null) reader.cancel();
        // Ends the wait of a turn that is reading events, as the interrupt of its thread did.
        state.run(s -> {
            s.turn.interrupt();
            s.autonomous.cancelWaiting();
            s.suspended = false;
        });
        answerAllParked();
        state.run(CliState::forgetTurn);
        if (p != null && p.isAlive()) {
            p.destroy();
            logger.info(binary + " CLI process cancelled");
        }
    }

    /**
     * Writes a user message to the process stdin in stream-json format.
     *
     * @param text the message text to send
     * @throws IOException if the process stdin is not available or writing fails
     */
    public void writeUserMessage(String text) throws IOException {
        writeUserMessage(text, List.of());
    }

    /**
     * Writes a user message with attached images to the process stdin in stream-json format.
     *
     * @param text          the message text to send
     * @param imageDataUrls data URLs (e.g. {@code data:image/png;base64,...}); when empty, the
     *                      content is sent as a plain string, identical to {@link #writeUserMessage(String)}
     * @throws IOException if the process stdin is not available or writing fails
     */
    public void writeUserMessage(String text, List<String> imageDataUrls) throws IOException {
        OutputStream stdinStream = stdin();
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + buildContentJson(text, imageDataUrls) + "}}\n";
        stdinStream.write(json.getBytes(StandardCharsets.UTF_8));
        stdinStream.flush();
    }

    static String buildContentJson(String text, List<String> imageDataUrls) {
        if (imageDataUrls == null || imageDataUrls.isEmpty()) {
            return escapeJsonString(text);
        }
        StringBuilder sb = new StringBuilder("[{\"type\":\"text\",\"text\":")
            .append(escapeJsonString(text)).append("}");
        for (String dataUrl : imageDataUrls) {
            sb.append(",{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":")
              .append(escapeJsonString(mediaTypeOf(dataUrl)))
              .append(",\"data\":")
              .append(escapeJsonString(base64DataOf(dataUrl)))
              .append("}}");
        }
        return sb.append("]").toString();
    }

    private static String mediaTypeOf(String dataUrl) {
        int colon = dataUrl.indexOf(':');
        int semicolon = dataUrl.indexOf(';');
        if (colon < 0 || semicolon < 0 || semicolon < colon) return "image/png";
        return dataUrl.substring(colon + 1, semicolon);
    }

    private static String base64DataOf(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        return comma < 0 ? dataUrl : dataUrl.substring(comma + 1);
    }

    /**
     * Sends a permission response back to the CLI process.
     *
     * <p>Claude Code CLI expects permission responses as a tool result in stream-json format.
     * The {@code response} should be one of: "yes", "yes-dont-ask-again", "no".</p>
     *
     * @param toolUseId the tool_use_id from the permission request
     * @param response  the user's answer ("yes", "yes-dont-ask-again", or "no")
     * @throws IOException if writing to the process stdin fails
     */
    public void writePermissionResponse(String toolUseId, String response) throws IOException {
        OutputStream stdinStream = stdin();
        String normalised = normalisePermissionResponse(response);
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + "[{\"type\":\"tool_result\",\"tool_use_id\":"
            + escapeJsonString(toolUseId) + ",\"content\":"
            + escapeJsonString(normalised) + "}]}}\n";
        stdinStream.write(json.getBytes(StandardCharsets.UTF_8));
        stdinStream.flush();
        logger.info("Permission response sent: " + normalised + " for tool_use_id=" + toolUseId);
    }

    static String normalisePermissionResponse(String raw) {
        if (raw == null) return "no";
        return switch (raw.toLowerCase().trim()) {
            case "yes", "y", "1", "ok", "allow" -> "yes";
            case "yes-dont-ask-again", "yes, don't ask again",
                 "yes don't ask again", "always" -> "yes-dont-ask-again";
            default -> "no";
        };
    }

    /**
     * @param cmd the command line {@link #buildCommand()} made
     * @return how the CLI process is started: Claude Code's own nesting variables removed, the API key under its
     *         variable when one is set, and the configured working directory
     */
    HarnessLaunch launch(List<String> cmd) {
        java.util.Map<String, String> added = new java.util.HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) added.put(apiKeyEnvVar, apiKey);
        // A call to chat-ui's tools waits while the conversation runs it as a step of its own; Claude Code must not
        // give up on it first (https://code.claude.com/docs/en/mcp, MCP_TOOL_TIMEOUT).
        if (mcpConfigJson != null) added.put("MCP_TOOL_TIMEOUT", String.valueOf(MCP_TOOL_TIMEOUT_MS));
        // Without this, a Claude Code process whose account is using extra usage writes every request after its first with a
        // 5-minute cache lifetime, so a conversation that waits longer than that between turns re-creates its whole context.
        // The variable takes precedence over that rule in the CLI.
        added.put(PROMPT_CACHE_TTL_ENV, "1h");
        CliConfig config = getConfig();
        // The conversation's window choice, as the process's own environment: 200K sets the CLI's switch for this process
        // only; any other choice removes it, so a variable this program inherited cannot decide a conversation's window.
        List<String> removed = new java.util.ArrayList<>(List.of("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT"));
        if (ClaudeModelChoice.parse(config.model()).standardWindow()) added.put(ClaudeModelChoice.DISABLE_1M_ENV, "1");
        else removed.add(ClaudeModelChoice.DISABLE_1M_ENV);
        return new HarnessLaunch(cmd, removed, added,
                config.workingDir() == null ? null : java.nio.file.Path.of(config.workingDir()));
    }

    /**
     * Hands the CLI agent-connector-kernel's MCP server from the next start of the process on (tasks/todo.md,
     * Decisions 5). chat-ui never calls this: its conversations keep the {@code <invoke>} path (Decisions 3).
     *
     * @param entry the server, or {@code null} for none
     */
    public void setMcpEntry(HarnessLaunch.McpEntry entry) { this.mcpConfigJson = entry == null ? null : entry.claudeMcpConfig(); }

    /**
     * Hands the CLI an MCP configuration from the next start of the process on.
     *
     * @param json {@code --mcp-config}'s value, or {@code null} for none
     */
    public void setMcpConfig(String json) { this.mcpConfigJson = json; }

    /**
     * Sets {@code --append-system-prompt} for the next start of the process.
     *
     * @param text the text, or {@code null} for none
     * @return whether it differs from the text set before, so a running process must be started again to see it
     */
    public boolean setAppendSystemPrompt(String text) {
        boolean changed = !java.util.Objects.equals(text, appendSystemPrompt);
        this.appendSystemPrompt = text;
        return changed;
    }

    /**
     * Stops the process between turns, keeping its session, so the next prompt starts it again with the current
     * options and {@code --resume}.
     */
    public void restartBeforeNextPrompt() {
        if (currentProcess() == null) return;
        String lastSessionId = getLastSessionId();
        if (lastSessionId != null) updateConfig(c -> c.withSessionId(lastSessionId));
        cancel();
    }

    List<String> buildCommand() {
        CliConfig config = getConfig();   // one set of options for the whole command
        List<String> cmd = new ArrayList<>();
        // The CLI writes to a pipe here, not a TTY, so libc switches its stdout from line
        // buffering to 4KB block buffering. Events then sit in the buffer instead of reaching
        // us as they are produced, and the whole turn arrives at once when the process flushes.
        // stdbuf -oL forces line buffering so each JSON event is delivered as it is written.
        cmd.add("stdbuf");
        cmd.add("-oL");
        cmd.add(binary);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        // The window choice ("sonnet[200k]") is chat-ui's own and is not a model name: --model gets the model only.
        String cliModel = ClaudeModelChoice.parse(config.model()).cliModel();
        if (cliModel != null) { cmd.add("--model"); cmd.add(cliModel); }
        // Left off entirely when unset, so the CLI applies its own default effort rather than
        // this program picking one and freezing it as the CLI's default changes.
        if (config.effort() != null && !config.effort().isBlank()) {
            cmd.add("--effort"); cmd.add(config.effort());
        }
        if (config.systemPrompt() != null) { cmd.add("--system-prompt"); cmd.add(config.systemPrompt()); }
        if (config.maxTurns() > 0) { cmd.add("--max-turns"); cmd.add(String.valueOf(config.maxTurns())); }
        if (config.sessionId() != null) { cmd.add("--resume"); cmd.add(config.sessionId()); }
        if (config.continueSession()) cmd.add("-c");
        if (config.permissionMode() != null && !config.permissionMode().isBlank()) {
            cmd.add("--permission-mode");
            cmd.add(config.permissionMode());
        }
        if (config.allowedTools() != null) {
            for (String tool : config.allowedTools()) { cmd.add("--allowedTools"); cmd.add(tool); }
        }
        // "" is a real value here: it disables every built-in tool, which is how a conversation
        // uses Claude as a bare model and runs this program's own tools instead
        // (CliHarnessProvider_260912_oo01).
        if (config.tools() != null) { cmd.add("--tools"); cmd.add(config.tools()); }
        if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(appendSystemPrompt);
        }
        if (mcpConfigJson != null) { cmd.add("--mcp-config"); cmd.add(mcpConfigJson); }
        return cmd;
    }

    static String stripAnsi(String s) {
        return ANSI_PATTERN.matcher(s).replaceAll("");
    }

    static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    public interface StreamCallback {
        void onEvent(StreamEvent event);
        default void onComplete(int exitCode) {}
    }
}
