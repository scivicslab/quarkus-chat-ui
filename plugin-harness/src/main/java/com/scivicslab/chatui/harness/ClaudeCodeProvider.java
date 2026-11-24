package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.kernel.StateOwner;
import com.scivicslab.chatui.plugin.ConversationMcp;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.rest.TokenUsage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Claude Code as a conversation's provider ({@code CliHarnessProvider_260912_oo01}).
 *
 * <p>One {@code claude} process per conversation, driven over stream-json on stdin/stdout and kept
 * alive between turns; the session id it reports is written to a file so a provider created again
 * for the same conversation resumes it with {@code --resume}. Ported from {@code quarkus-chat-ui}'s
 * {@code CliLlmProvider}, without its slash-command handler.</p>
 *
 * <p>Inside one {@link #sendPrompt} the harness runs its own tools as often as it likes. Each such
 * call reaches the conversation as a {@code tool_use} event and its result as a {@code tool_result}
 * event, so the conversation can record them to the I/O log; with {@link ToolSet#FULL} the
 * built-in tools are disabled ({@code --tools ""}) and the conversation's own tools serve instead.</p>
 */
public class ClaudeCodeProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(ClaudeCodeProvider.class.getName());

    public static final String ID = "claude";

    /** Aliases rather than dated ids: the CLI resolves each to the current model of that line. */
    private static final List<ModelEntry> MODELS = List.of(
        new ModelEntry("fable", ID, null),
        new ModelEntry("opus", ID, null),
        new ModelEntry("sonnet", ID, null),
        new ModelEntry("haiku", ID, null)
    );

    private final CliProcess cliProcess;
    private final SlashCommandHandler commandHandler;
    private final Path sessionFile;
    private final ClaudeTranscriptLog transcriptLog;
    private final ToolSet toolSet;
    private final ConversationMcp mcp;
    /** The ids of the CLI's calls to the conversation's own tools, whose results are not passed on. */
    /**
     * What the turn's events and the answers to its prompts share: the ids of the tool calls the conversation runs itself,
     * and of the permission and plan-approval prompts waiting for an answer. The events come from the turn and the answers
     * from the conversation, on other threads, so one owner, an actor, holds the three sets and every use is a message.
     */
    static final class Pending {
        final Set<String> ownToolUseIds = new java.util.HashSet<>();
        final Set<String> permissionIds = new java.util.HashSet<>();
        final Set<String> planApprovalIds = new java.util.HashSet<>();
    }

    private final StateOwner<Pending> pending = new StateOwner<>("claude-pending", new Pending());

    /**
     * @param settings   process-wide harness settings
     * @param projectId  owning project's id, part of the session file name
     * @param chatId     conversation id, part of the session file name
     * @param workingDir the directory the harness works in, or {@code null} for this process's own
     * @param toolSet    the conversation's tool set; {@link ToolSet#FULL} disables the built-in tools
     */
    public ClaudeCodeProvider(HarnessSettings settings, String projectId, String chatId,
                              Path workingDir, ToolSet toolSet) {
        this(settings, projectId, chatId, workingDir, toolSet, null);
    }

    /**
     * @param settings   process-wide harness settings
     * @param projectId  owning project's id, part of the session file name
     * @param chatId     conversation id, part of the session file name
     * @param workingDir the directory the harness works in, or {@code null} for this process's own
     * @param toolSet    the conversation's tool set; {@link ToolSet#FULL} disables the built-in tools
     * @param mcp        chat-ui's MCP server for this conversation: the CLI calls the conversation's tools there,
     *                   and the calls are parked into the conversation's agent loop; {@code null} leaves the
     *                   conversation's tools to the {@code <invoke>} text (tasks/todo.md, "Shrinking the composed
     *                   prompt")
     */
    public ClaudeCodeProvider(HarnessSettings settings, String projectId, String chatId,
                              Path workingDir, ToolSet toolSet, ConversationMcp mcp) {
        this.toolSet = toolSet;
        this.mcp = mcp;
        this.sessionFile = settings.sessionFile(projectId, chatId, ID);
        this.transcriptLog = new ClaudeTranscriptLog(sessionFile, settings.claudeProjectsDir(),
                ClaudeTranscriptLog.DEFAULT_TAIL_BYTES);
        CliConfig config = CliConfig.defaults(settings.claudeModel());
        if (settings.permissionMode() != null && !settings.permissionMode().isBlank()) {
            config = config.withPermissionMode(settings.permissionMode().trim());
        }
        if (workingDir != null) config = config.withWorkingDir(workingDir.toString());
        if (toolSet == ToolSet.FULL) config = config.withTools("");
        config = restoreSession(config);
        this.cliProcess = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
        this.commandHandler = new SlashCommandHandler(cliProcess);
        if (mcp != null) cliProcess.setMcpConfig(writeMcpConfig(sessionFile, mcp).toString());
    }

    /**
     * Writes Claude Code's MCP configuration for chat-ui's MCP server next to the session file, readable by this
     * user only: it holds the conversation's key, which a command line would show to every user of the machine.
     *
     * @return the file, which {@code --mcp-config} names
     */
    static Path writeMcpConfig(Path sessionFile, ConversationMcp mcp) {
        org.json.JSONObject headers = new org.json.JSONObject().put("Authorization", "Bearer " + mcp.key())
                .put(ConversationMcp.SCOPE_HEADER, mcp.scope());
        org.json.JSONObject server = new org.json.JSONObject().put("type", "http").put("url", mcp.url())
                .put("headers", headers);
        String json = new org.json.JSONObject().put("mcpServers",
                new org.json.JSONObject().put(ConversationMcp.SERVER_NAME, server)).toString();
        Path file = sessionFile.resolveSibling(sessionFile.getFileName() + ".mcp.json");
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(file);
            Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, json);
        } catch (IOException e) {
            throw new IllegalStateException("could not write the MCP configuration " + file + ": " + e.getMessage(), e);
        }
        return file;
    }

    /**
     * @param input the raw text a person typed
     * @return whether this provider answers it itself rather than sending it to {@code claude}
     *         ({@code SlashCommandsAndClearButton_260929_oo01})
     */
    @Override
    public boolean isCommand(String input) { return commandHandler.isCommand(input); }

    /** A line the CLI carries out, such as {@code /compact}, as opposed to a command of this program. */
    @Override
    public boolean isExchangeWithBackend(String input) {
        return commandHandler.isCommand(input) && !commandHandler.isLocalCommand(input);
    }

    /**
     * Carries out one command and returns what the person should see.
     *
     * <p>A command of this program ({@link SlashCommandHandler#isLocalCommand}) is carried out
     * here. {@code /clear} also deletes the file this provider keeps its session id in. Left
     * there, the id would be read back the next time a provider is created for this conversation,
     * and the conversation the command was meant to leave behind would come back.</p>
     *
     * <p>Any other line is a command of the CLI, such as {@code /compact}, and is sent to it
     * ({@link #sendToCli}).</p>
     *
     * @param input the whole line, leading slash included
     * @return one event per thing to show
     */
    @Override
    public List<ChatEvent> handleCommand(String input) {
        if (!commandHandler.isLocalCommand(input)) {
            return sendToCli(input.trim());
        }
        List<ChatEvent> responses = new ArrayList<>();
        commandHandler.handle(input, responses::add);
        if (input.trim().toLowerCase().startsWith("/clear")) {
            deleteSessionFile();
        }
        return responses;
    }

    /**
     * Sends a line beginning with a slash to {@code claude} as it is, and returns the answer to show.
     *
     * <p>The line has to reach the CLI as the whole of the message: the CLI recognises a command
     * only at the start of the message, and the prompts the conversation builds for a turn open
     * with {@link #PREFACE} and the tool list. So the line does not go through the prompt queue,
     * and it is not a turn of the conversation, so it has no turn number. The conversation writes it
     * to the I/O log as an exchange of its own ({@link #isExchangeWithBackend}), unlike the commands
     * of this program, which only change settings.</p>
     *
     * <p>Run with a turn in flight, the line would be answered in the middle of that turn's events,
     * so it is refused instead.</p>
     *
     * @param line the whole line, leading slash included
     * @return the CLI's answer as {@code info} events, or one {@code error} event
     */
    List<ChatEvent> sendToCli(String line) {
        if (cliProcess.isTurnActive()) {
            return List.of(ChatEvent.error(
                    "A turn is running. Send " + line.split("\\s+", 2)[0] + " when it has finished."));
        }
        if (!cliProcess.isAlive()) {
            String lastSessionId = cliProcess.getLastSessionId();
            if (lastSessionId != null) {
                cliProcess.setConfig(cliProcess.getConfig().withSessionId(lastSessionId));
            }
        }
        List<ChatEvent> produced = new ArrayList<>();
        List<String> printed = new ArrayList<>();
        // One pair of flags for the whole line: the answer's text arrives both as an assistant
        // event and again in the result, and the flags are how the second is recognised.
        boolean[] staleSession = {false};
        boolean[] sawAssistant = {false};
        try {
            cliProcess.sendPrompt(line, List.of(), event -> {
                if (StreamEventParser.LOCAL_COMMAND_OUTPUT.equals(event.type())) {
                    printed.add(event.content());
                } else {
                    dispatch(event, produced::add, staleSession, sawAssistant);
                }
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, "claude CLI failed on " + line, e);
            return List.of(ChatEvent.error("claude CLI error: " + e.getMessage()));
        }
        return commandAnswer(printed, produced);
    }

    /**
     * What to show for a command of the CLI. What the command itself printed ({@code Compacted}) is
     * the answer when there is any: around it the CLI replays the last lines it showed before the
     * command, and those would read as the command's answer. Otherwise the text of the turn is.
     */
    static List<ChatEvent> commandAnswer(List<String> printed, List<ChatEvent> produced) {
        if (!printed.isEmpty()) {
            return printed.stream().<ChatEvent>map(ChatEvent::info).toList();
        }
        return asCommandAnswer(produced);
    }

    /**
     * The events of a turn, reduced to what answers a command: the text becomes {@code info}
     * (the pane shows a command's answer as such), an error stays an error, and the rest of a
     * turn's events (the result, tool calls, thinking) are dropped because a command is not a turn.
     */
    static List<ChatEvent> asCommandAnswer(List<ChatEvent> produced) {
        List<ChatEvent> answer = new ArrayList<>();
        for (ChatEvent event : produced) {
            switch (event.type()) {
                case "delta" -> {
                    if (event.content() != null && !event.content().isBlank()) {
                        answer.add(ChatEvent.info(event.content()));
                    }
                }
                case "error" -> answer.add(event);
                default -> { }
            }
        }
        if (answer.isEmpty()) answer.add(ChatEvent.info("Done (the CLI printed nothing)."));
        return answer;
    }

    /** @return the conversation's tool set this provider was created for */
    public ToolSet toolSet() { return toolSet; }

    /**
     * What Claude Code must be told at the top of a turn's first prompt when it runs with its own
     * tools ({@code HarnessPrefaceAndToolSplit_260912_oo01}). Without it the harness read the
     * conversation's "Available tools" list as everything it may use and declined to search the
     * web; and its WebSearch and WebFetch are deferred tools, absent until loaded with ToolSearch,
     * which it reports as "no web tool" unless told how to load them. With the built-in tools
     * switched off ({@link ToolSet#FULL}) there is nothing to say.
     */
    static final String PREFACE = """
            You are running inside Claude Code, your own coding harness, but in this conversation you \
            are a general assistant: any topic is in scope, not only code. Your harness has its own \
            tools — reading and writing files, running shell commands, and web search and web fetch \
            (WebSearch and WebFetch). Some of your tools are deferred: if WebSearch or WebFetch is not \
            yet loaded, load it first with ToolSearch (query "select:WebSearch,WebFetch") and then \
            call it. Use your own tools directly, the way you always do, whenever they fit the task.

            The tools listed below are this conversation's own tools, NOT tools of your harness: \
            calling one of them as a harness tool fails with "No such tool available". To use one, \
            write the <invoke> block described next as text in your reply and end the reply there; \
            this conversation runs it and sends the result back. Either path is fine, and this \
            conversation records what it returns.

            Rule for the outside world: when answering needs current information — weather, news, \
            prices, schedules, anything that changes over time — you MUST get it from the web, either \
            with your WebSearch (after loading it) or with the listed web_search, before answering. \
            You do have web access here; never reply that you cannot access the web or tell the user \
            to look it up themselves.

            """;

    /**
     * What Claude Code is told about itself when the conversation's tools come over MCP: the first and the last
     * paragraph of {@link #PREFACE}, and where the conversation's tools are. It is part of the system message
     * ({@code --append-system-prompt}), sent once, not before every turn.
     */
    static final String MCP_PREFACE = """
            You are running inside Claude Code, your own coding harness, but in this conversation you \
            are a general assistant: any topic is in scope, not only code. Your harness has its own \
            tools — reading and writing files, running shell commands, and web search and web fetch \
            (WebSearch and WebFetch). Use them directly, the way you always do, whenever they fit the task.

            This conversation's own tools are the tools of the MCP server "chat-ui" (mcp__chat-ui__<name>). \
            Some of your tools, those among them, are deferred: load one with ToolSearch before you call it.

            Rule for the outside world: when answering needs current information — weather, news, \
            prices, schedules, anything that changes over time — you MUST get it from the web, either \
            with your WebSearch or with this conversation's web_search, before answering. You do have web \
            access here; never reply that you cannot access the web or tell the user to look it up themselves.

            """;

    /** The same as {@link #MCP_PREFACE}, for the case where the harness's own tools are switched off. */
    static final String MCP_PREFACE_TOOLS_OFF = """
            This conversation's tools are the tools of the MCP server "chat-ui" (mcp__chat-ui__<name>). \
            Some of them are deferred: load one with ToolSearch before you call it.

            """;

    @Override
    public String promptPreface() {
        if (mcp != null) return toolSet == ToolSet.FULL ? MCP_PREFACE_TOOLS_OFF : MCP_PREFACE;
        return toolSet == ToolSet.FULL ? "" : PREFACE;
    }

    // ---- Tools over chat-ui's MCP server, parked into the conversation's agent loop ----

    @Override public boolean callsToolsByDefinition() { return mcp != null; }
    @Override public boolean parksToolCalls() { return mcp != null; }

    @Override
    public java.util.concurrent.CompletableFuture<String> parkToolCall(String name, String argumentsJson) {
        return cliProcess.park(name, argumentsJson);
    }

    @Override
    public void addToolResults(List<ToolCallResult> results) {
        for (ToolCallResult r : results) cliProcess.answer(r.callId(), r.content());
    }

    /** How long an interrupted turn of the CLI may take to reach its result before the process is stopped. */
    static final long END_TURN_WAIT_MS = 30_000;

    @Override
    public void endTurn() {
        cliProcess.endTurn(END_TURN_WAIT_MS);
    }

    /**
     * The system message goes in {@code --append-system-prompt}: the CLI keeps it out of the session's history,
     * and its compaction never summarises it away. A change takes effect when the process starts again, which this
     * does before the next prompt, resuming the session.
     */
    @Override
    public void setSystemMessage(String text) {
        if (cliProcess.setAppendSystemPrompt(text) && cliProcess.isAlive() && !cliProcess.isSuspended()) {
            cliProcess.restartBeforeNextPrompt();
        }
    }

    /** The prefix of the names Claude Code gives the tools of chat-ui's MCP server. */
    static final String MCP_TOOL_PREFIX = "mcp__" + ConversationMcp.SERVER_NAME + "__";

    // ---- LlmProvider ----

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Claude Code"; }
    @Override public List<ModelEntry> getAvailableModels() { return MODELS; }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }
    @Override public String getCurrentModel() { return cliProcess.getConfig().model(); }
    @Override public void setModel(String model) { cliProcess.setConfig(cliProcess.getConfig().withModel(model)); }

    /** Applied to the command line the next time the process starts, as the model is. */
    @Override public void setEffort(String effort) { cliProcess.setConfig(cliProcess.getConfig().withEffort(effort)); }

    @Override public String getEffort() { return cliProcess.getConfig().effort(); }
    @Override public String getSessionId() { return cliProcess.getLastSessionId(); }
    @Override public String detectEnvApiKey() { return System.getenv("ANTHROPIC_API_KEY"); }
    @Override public void cancel() { cliProcess.cancel(); }

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        if (ctx.apiKey() != null) cliProcess.setApiKey(ctx.apiKey());
        if (model != null && !model.equals(cliProcess.getConfig().model())) {
            cliProcess.setConfig(cliProcess.getConfig().withModel(model));
        }
        if (!cliProcess.isAlive()) {
            String lastSessionId = cliProcess.getLastSessionId();
            if (lastSessionId != null) {
                cliProcess.setConfig(cliProcess.getConfig().withSessionId(lastSessionId));
            }
        }

        final boolean[] staleSession = {false};
        final boolean[] sawAssistant = {false};
        firstRequestUsage = null;
        try {
            CliProcess.StreamCallback callback = event -> {
                ctx.onActivity().run();
                dispatch(event, emitter, staleSession, sawAssistant);
            };
            // A turn parked on calls to the conversation's tools goes on where it stopped: the calls' results were
            // just given, and the blank prompt adds nothing.
            if (cliProcess.isSuspended()) cliProcess.resume(callback);
            else cliProcess.sendPrompt(prompt, ctx.imageDataUrls(), callback);
        } catch (CliProcess.EndedWithoutResult e) {
            // A session the CLI cannot find ends the process too; that one is retried below with a new session.
            // Any other end is the prompt's failure, reported with the exit code: the turn fails, and the asker is
            // told why (TurnWithoutAnAnswerTellsTheAsker_261119_oo01).
            if (!staleSession[0]) throw new IllegalStateException(e.getMessage(), e);
        } catch (IOException e) {
            logger.log(Level.WARNING, "claude CLI failed", e);
            emitter.accept(ChatEvent.error("claude CLI error: " + e.getMessage()));
            return;
        }

        if (staleSession[0]) {
            logger.warning("Stale claude session detected; clearing it and retrying once");
            cliProcess.cancel();
            deleteSessionFile();
            cliProcess.setConfig(cliProcess.getConfig().withSessionId(null));
            emitter.accept(ChatEvent.thinking("Session expired. Starting new session..."));
            try {
                final boolean[] retrySawAssistant = {false};
                cliProcess.sendPrompt(prompt, ctx.imageDataUrls(), event -> {
                    ctx.onActivity().run();
                    dispatch(event, emitter, new boolean[]{false}, retrySawAssistant);
                });
            } catch (CliProcess.EndedWithoutResult e) {
                throw new IllegalStateException(e.getMessage(), e);
            } catch (IOException e) {
                emitter.accept(ChatEvent.error("claude CLI error on retry: " + e.getMessage()));
            }
        }
    }

    /**
     * Maps one stream event to the {@link ChatEvent}s the conversation understands. Text streams as
     * {@code delta}; the harness's own tool calls and their results pass through as
     * {@code tool_use}/{@code tool_result} for the conversation to record.
     */
    void dispatch(StreamEvent event, Consumer<ChatEvent> emitter, boolean[] staleSession, boolean[] sawAssistant) {
        // The first model request of the call is the one that shows how much of the window the call starts
        // with; the later requests of a tool-using call only grow it.
        if (event.usage() != null && !"result".equals(event.type()) && firstRequestUsage == null) {
            firstRequestUsage = event.usage();
        }
        switch (event.type()) {
            case "assistant" -> {
                if (event.content() != null && !event.content().isEmpty()) {
                    sawAssistant[0] = true;
                    emitter.accept(ChatEvent.delta(event.content()));
                }
            }
            case "thinking" -> emitter.accept(ChatEvent.thinking(
                    event.hasContent() ? event.content() : "Thinking..."));
            case StreamEvent.PARKED_CALL -> emitter.accept(ChatEvent.toolCall(event.toolUseId(), event.toolName(),
                    event.content() == null ? "{}" : event.content()));
            case "tool_use" -> {
                // A call to the conversation's own tools is reported by the MCP request instead, and recorded once
                // by the conversation as its own step; its use and result here would record it twice.
                if (event.toolName() != null && event.toolName().startsWith(MCP_TOOL_PREFIX)) {
                    pending.run(p -> p.ownToolUseIds.add(event.toolUseId()));
                    return;
                }
                emitter.accept(ChatEvent.toolUse(event.toolUseId(), event.toolName(), event.content()));
            }
            case "retraction" -> emitter.accept(ChatEvent.retraction(event.content()));
            case "tool_result" -> {
                if (pending.ask(p -> p.ownToolUseIds.remove(event.toolUseId()))) return;
                // ExitPlanMode is surfaced as a prompt from its tool_use block; the CLI then
                // auto-denies the tool with this result, which is not an error of the turn.
                if (event.isError() && "Exit plan mode?".equals(
                        event.content() != null ? event.content().trim() : "")) {
                    return;
                }
                emitter.accept(ChatEvent.toolResult(event.toolUseId(),
                        event.content() == null ? "" : event.content(), event.isError()));
            }
            case "system" -> {
                if (event.sessionId() != null) saveSession(event.sessionId());
            }
            case "result" -> {
                // A session the CLI could not find is retried with a new one; its end is not the prompt's.
                if (staleSession[0]) return;
                // Newer CLIs may put the whole answer only in the result event; older ones always
                // streamed it as assistant events. Send it once either way.
                if (!sawAssistant[0] && event.content() != null && !event.content().isBlank()) {
                    emitter.accept(ChatEvent.delta(event.content()));
                }
                if (event.sessionId() != null) saveSession(event.sessionId());
                emitter.accept(ChatEvent.result(event.sessionId(), event.costUsd(), event.durationMs(),
                        cliProcess.getConfig().model(), false, callUsage(event.usage())));
            }
            case "error" -> {
                if (event.content() != null
                        && event.content().contains("No conversation found with session ID")) {
                    staleSession[0] = true;
                } else {
                    emitter.accept(ChatEvent.error(event.content()));
                }
            }
            case "prompt" -> {
                if ("permission".equals(event.promptType()) && event.promptId() != null) {
                    pending.run(p -> p.permissionIds.add(event.promptId()));
                } else if ("exit_plan_mode".equals(event.promptType()) && event.promptId() != null) {
                    pending.run(p -> p.planApprovalIds.add(event.promptId()));
                }
                emitter.accept(ChatEvent.prompt(
                        event.promptId(), event.content(), event.promptType(), event.options()));
            }
            default -> { }
        }
    }

    /** Usage of the first model request of the call in progress, or {@code null} until one is seen. */
    private TokenUsage firstRequestUsage;

    /**
     * The usage one call is recorded with: the input side of its first model request, and the output
     * the CLI summed over all of the call's requests. The {@code result} line's own input counts add up
     * every request of the call, which says nothing about the window; they are used only when no
     * assistant line carried usage.
     */
    private TokenUsage callUsage(TokenUsage resultUsage) {
        TokenUsage first = firstRequestUsage;
        firstRequestUsage = null;
        if (first == null) return resultUsage;
        int output = resultUsage != null && resultUsage.output() > 0 ? resultUsage.output() : first.output();
        return new TokenUsage(first.input(), first.cacheCreation(), first.cacheRead(), output);
    }

    // ---- Interactive prompts ----

    @Override public boolean supportsInteractivePrompts() { return true; }

    @Override
    public void respond(String promptId, String response) throws IOException {
        if (pending.ask(p -> p.permissionIds.remove(promptId))) {
            cliProcess.writePermissionResponse(promptId, response);
        } else {
            cliProcess.writeUserMessage(response);
        }
    }

    @Override
    public boolean isPlanApproval(String promptId) {
        return promptId != null && pending.ask(p -> p.planApprovalIds.contains(promptId));
    }

    @Override
    public void clearPlanApproval(String promptId) {
        pending.run(p -> p.planApprovalIds.remove(promptId));
    }

    // ---- Autonomous events ----

    private static final long AUTONOMOUS_EVENT_TIMEOUT_MS = 5_000;

    @Override public boolean supportsAutonomousEvents() { return true; }
    @Override public boolean hasAutonomousActivity() { return cliProcess.hasAutonomousEvent(); }

    @Override
    public boolean drainAutonomousActivity(Consumer<ChatEvent> emitter) {
        if (!cliProcess.hasAutonomousEvent()) return false;
        final boolean[] staleSession = {false};
        final boolean[] sawAssistant = {false};
        boolean surfaced = false;
        try {
            while (true) {
                StreamEvent event = cliProcess.pollAutonomousEvent(AUTONOMOUS_EVENT_TIMEOUT_MS);
                if (event == null) break;
                dispatch(event, emitter, staleSession, sawAssistant);
                surfaced = true;
                if ("result".equals(event.type())) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return surfaced;
    }

    /**
     * Reads the conversation's history from the transcript Claude Code keeps for the session named in
     * the session file ({@code GetChatLogReadsPersistedHistory_261119_oo01}). Uses only final
     * fields, so it may be called from any thread while a turn runs.
     */
    @Override
    public com.scivicslab.chatui.core.provider.PersistedLog persistedLog(int limit, long before) {
        return transcriptLog.read(limit, before);
    }

    // ---- Session persistence ----

    private CliConfig restoreSession(CliConfig config) {
        try {
            if (!Files.exists(sessionFile)) return config;
            String savedSessionId = null;
            String savedModel = null;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) savedSessionId = line.substring("sessionId=".length()).trim();
                else if (line.startsWith("model=")) savedModel = line.substring("model=".length()).trim();
            }
            if (savedSessionId != null && !savedSessionId.isEmpty()) {
                config = config.withSessionId(savedSessionId);
                logger.info("Restored claude session " + savedSessionId + " from " + sessionFile);
            }
            if (savedModel != null && !savedModel.isEmpty()) {
                config = config.withModel(savedModel);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file " + sessionFile, e);
        }
        return config;
    }

    private void saveSession(String sessionId) {
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile,
                    "sessionId=" + sessionId + "\nmodel=" + cliProcess.getConfig().model() + "\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file " + sessionFile, e);
        }
    }

    private void deleteSessionFile() {
        try {
            Files.deleteIfExists(sessionFile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete session file " + sessionFile, e);
        }
    }
}
