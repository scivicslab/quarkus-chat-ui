package com.scivicslab.chatui.cli;

import com.scivicslab.chatui.cli.command.SlashCommandHandler;
import com.scivicslab.chatui.cli.process.CliConfig;
import com.scivicslab.chatui.cli.process.CliProcess;
import com.scivicslab.chatui.cli.process.StreamEvent;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for CLI-based LLM providers (Claude, Codex).
 *
 * <p>Subclasses declare the binary name, API key env var, default model,
 * and available models. Everything else — process management, stream parsing,
 * slash commands, session persistence, stale-session retry — lives here.</p>
 */
public abstract class CliLlmProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(CliLlmProvider.class.getName());

    protected final CliProcess cliProcess;
    protected final SlashCommandHandler commandHandler;
    protected final Path sessionFile;
    private final java.util.Set<String> pendingPermissionIds =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Creates a new CLI-based LLM provider.
     *
     * @param binary          the CLI binary name (e.g. "claude", "codex")
     * @param apiKeyEnvVar    the environment variable name for the API key
     * @param defaultModel    the default model identifier to use
     * @param allowedTools    comma-separated list of allowed tool names, or empty
     * @param permissionMode  CLI permission mode (acceptEdits, default, auto, bypassPermissions)
     * @param sessionFilePath base path for the session persistence file
     * @param httpPort        HTTP port used to disambiguate session files
     */
    protected CliLlmProvider(String binary, String apiKeyEnvVar, String defaultModel,
                              Optional<String> allowedTools, Optional<String> permissionMode,
                              String sessionFilePath, int httpPort) {
        CliConfig config = buildInitialConfig(defaultModel, allowedTools, permissionMode);
        this.sessionFile = resolveSessionFile(sessionFilePath, httpPort);
        config = restoreSession(config);
        this.cliProcess = new CliProcess(binary, apiKeyEnvVar, config);
        this.commandHandler = new SlashCommandHandler(cliProcess);
    }

    // ---- LlmProvider implementation ----

    /** {@inheritDoc} */
    @Override
    public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }

    /** {@inheritDoc} */
    @Override
    public String getCurrentModel() { return cliProcess.getConfig().model(); }

    /**
     * {@inheritDoc}
     *
     * @param model the new model identifier to use for subsequent prompts
     */
    @Override
    public void setModel(String model) { cliProcess.setConfig(cliProcess.getConfig().withModel(model)); }

    /** {@inheritDoc} */
    @Override
    public String getSessionId() { return cliProcess.getLastSessionId(); }

    /**
     * {@inheritDoc}
     *
     * @param input the raw user input to check
     * @return {@code true} if the input is a slash command
     */
    @Override
    public boolean isCommand(String input) { return commandHandler.isCommand(input); }

    /**
     * Handles a slash command and returns the resulting chat events.
     *
     * <p>If the command is {@code /clear}, the persisted session file is also deleted
     * and the session ID is reset.</p>
     *
     * @param input the slash command string (e.g. "/model gpt-4")
     * @return a list of {@link ChatEvent} responses produced by the command
     */
    @Override
    public List<ChatEvent> handleCommand(String input) {
        List<ChatEvent> responses = new ArrayList<>();
        commandHandler.handle(input, responses::add);
        if (input.trim().toLowerCase().startsWith("/clear")) {
            deleteSessionFile();
            cliProcess.setConfig(cliProcess.getConfig().withSessionId(null));
        }
        return responses;
    }

    /**
     * Sends a user response back to the CLI process (e.g. answering an interactive prompt).
     *
     * @param promptId the identifier of the prompt being answered
     * @param response the user's response text
     * @throws IOException if writing to the process stdin fails
     */
    @Override
    public void respond(String promptId, String response) throws IOException {
        if (pendingPermissionIds.remove(promptId)) {
            cliProcess.writePermissionResponse(promptId, response);
        } else {
            cliProcess.writeUserMessage(response);
        }
    }

    /** Records a permission request's tool_use_id so respond() can use the right format. */
    public void registerPermissionRequest(String toolUseId) {
        pendingPermissionIds.add(toolUseId);
    }

    /** Cancels the currently running CLI process, if any. */
    @Override
    public void cancel() { cliProcess.cancel(); }

    /**
     * Sends a prompt to the CLI LLM and streams response events to the emitter.
     *
     * <p>If the process is not alive, it is restarted with the last known session ID.
     * If a stale session error is detected, the session is cleared and the prompt
     * is retried once.</p>
     *
     * @param prompt  the user prompt text
     * @param model   the model identifier to use
     * @param emitter callback that receives streamed {@link ChatEvent} instances
     * @param ctx     provider context containing the API key and activity callback
     */
    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        if (ctx.apiKey() != null) cliProcess.setApiKey(ctx.apiKey());
        if (!model.equals(cliProcess.getConfig().model())) {
            cliProcess.setConfig(cliProcess.getConfig().withModel(model));
        }
        if (!cliProcess.isAlive()) {
            String lastSessionId = cliProcess.getLastSessionId();
            if (lastSessionId != null) {
                cliProcess.setConfig(cliProcess.getConfig().withSessionId(lastSessionId));
            }
        }

        final boolean[] staleSession = {false};

        try {
            cliProcess.sendPrompt(prompt, event -> {
                ctx.onActivity().run();
                dispatch(event, emitter, staleSession);
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, id() + " CLI failed", e);
            emitter.accept(ChatEvent.error(id() + " CLI error: " + e.getMessage()));
            return;
        }

        if (staleSession[0]) {
            logger.warning("Stale session detected, clearing and retrying");
            cliProcess.cancel();
            deleteSessionFile();
            cliProcess.setConfig(cliProcess.getConfig().withSessionId(null));
            emitter.accept(ChatEvent.thinking("Session expired. Starting new session..."));
            // Retry once without stale-session handling
            try {
                cliProcess.sendPrompt(prompt, event -> {
                    ctx.onActivity().run();
                    dispatch(event, emitter, new boolean[]{false});
                });
            } catch (IOException e) {
                emitter.accept(ChatEvent.error(id() + " CLI error on retry: " + e.getMessage()));
            }
        }
    }

    private void dispatch(StreamEvent event, Consumer<ChatEvent> emitter, boolean[] staleSession) {
        switch (event.type()) {
            case "assistant" -> {
                if (event.hasContent()) emitter.accept(ChatEvent.delta(event.content()));
            }
            case "thinking" -> emitter.accept(ChatEvent.thinking("Thinking..."));
            case "tool_activity" -> emitter.accept(ChatEvent.thinking("Using " + event.content() + "..."));
            case "tool_result" -> emitter.accept(ChatEvent.thinking("Tool completed."));
            case "system" -> { if (event.sessionId() != null) saveSession(event.sessionId()); }
            case "result" -> {
                if (event.sessionId() != null) saveSession(event.sessionId());
                emitter.accept(ChatEvent.result(event.sessionId(), event.costUsd(), event.durationMs(),
                        cliProcess.getConfig().model(), false));
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
                    registerPermissionRequest(event.promptId());
                }
                emitter.accept(ChatEvent.prompt(
                        event.promptId(), event.content(), event.promptType(), event.options()));
            }
            default -> { /* ignore */ }
        }
    }

    // ---- Session persistence ----

    private CliConfig restoreSession(CliConfig config) {
        try {
            if (!Files.exists(sessionFile)) return config;
            String savedSessionId = null, savedModel = null;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) savedSessionId = line.substring("sessionId=".length()).trim();
                else if (line.startsWith("model=")) savedModel = line.substring("model=".length()).trim();
            }
            if (savedSessionId != null && !savedSessionId.isEmpty()) {
                config = config.withSessionId(savedSessionId);
                logger.info("Restored session: " + savedSessionId);
            }
            if (savedModel != null && !savedModel.isEmpty()) {
                config = config.withModel(savedModel);
                logger.info("Restored model: " + savedModel);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file", e);
        }
        return config;
    }

    private void saveSession(String sessionId) {
        try {
            Files.writeString(sessionFile,
                    "sessionId=" + sessionId + "\nmodel=" + cliProcess.getConfig().model() + "\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file", e);
        }
    }

    private void deleteSessionFile() {
        try { Files.deleteIfExists(sessionFile); }
        catch (Exception e) { logger.log(Level.WARNING, "Failed to delete session file", e); }
    }

    // ---- Helpers ----

    private static CliConfig buildInitialConfig(String defaultModel, Optional<String> allowedTools,
                                                  Optional<String> permissionMode) {
        CliConfig config = CliConfig.defaults(defaultModel);
        if (permissionMode.isPresent() && !permissionMode.get().isBlank()) {
            config = config.withPermissionMode(permissionMode.get().trim());
        }
        if (allowedTools.isPresent() && !allowedTools.get().isBlank()) {
            String[] tools = allowedTools.get().split(",");
            for (int i = 0; i < tools.length; i++) tools[i] = tools[i].trim();
            config = config.withAllowedTools(tools);
        }
        return config;
    }

    private static Path resolveSessionFile(String sessionFilePath, int httpPort) {
        String pupsPath = System.getenv("PUPS_SESSION_PATH");
        String suffix = (pupsPath != null && !pupsPath.isBlank())
                ? pupsPath.replaceAll("[^a-zA-Z0-9-]", "")
                : String.valueOf(httpPort);
        return Path.of(sessionFilePath + "-" + suffix);
    }
}
