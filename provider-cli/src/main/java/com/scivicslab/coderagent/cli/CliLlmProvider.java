package com.scivicslab.coderagent.cli;

import com.scivicslab.coderagent.cli.command.SlashCommandHandler;
import com.scivicslab.coderagent.cli.process.CliConfig;
import com.scivicslab.coderagent.cli.process.CliProcess;
import com.scivicslab.coderagent.cli.process.StreamEvent;
import com.scivicslab.coderagent.core.provider.LlmProvider;
import com.scivicslab.coderagent.core.provider.ProviderCapabilities;
import com.scivicslab.coderagent.core.provider.ProviderContext;
import com.scivicslab.coderagent.core.rest.ChatEvent;

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

    protected CliLlmProvider(String binary, String apiKeyEnvVar, String defaultModel,
                              Optional<String> allowedTools, String sessionFilePath, int httpPort) {
        CliConfig config = buildInitialConfig(defaultModel, allowedTools);
        this.sessionFile = resolveSessionFile(sessionFilePath, httpPort);
        config = restoreSession(config);
        this.cliProcess = new CliProcess(binary, apiKeyEnvVar, config);
        this.commandHandler = new SlashCommandHandler(cliProcess);
    }

    // ---- LlmProvider implementation ----

    @Override
    public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }

    @Override
    public String getCurrentModel() { return cliProcess.getConfig().model(); }

    @Override
    public void setModel(String model) { cliProcess.setConfig(cliProcess.getConfig().withModel(model)); }

    @Override
    public String getSessionId() { return cliProcess.getLastSessionId(); }

    @Override
    public boolean isCommand(String input) { return commandHandler.isCommand(input); }

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

    @Override
    public void respond(String promptId, String response) throws IOException {
        cliProcess.writeUserMessage(response);
    }

    @Override
    public void cancel() { cliProcess.cancel(); }

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
            case "prompt" -> emitter.accept(ChatEvent.prompt(
                    event.promptId(), event.content(), event.promptType(), event.options()));
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

    private static CliConfig buildInitialConfig(String defaultModel, Optional<String> allowedTools) {
        CliConfig config = CliConfig.defaults(defaultModel);
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
