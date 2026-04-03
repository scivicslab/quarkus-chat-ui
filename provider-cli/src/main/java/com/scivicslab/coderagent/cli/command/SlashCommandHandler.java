package com.scivicslab.coderagent.cli.command;

import com.scivicslab.coderagent.cli.process.CliProcess;
import com.scivicslab.coderagent.core.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Handles slash commands from the Web UI for CLI-based providers.
 *
 * <p>Supported commands: /model, /session, /clear, /help</p>
 */
public class SlashCommandHandler {

    private final CliProcess cliProcess;

    public SlashCommandHandler(CliProcess cliProcess) {
        this.cliProcess = cliProcess;
    }

    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    public void handle(String input, Consumer<ChatEvent> sender) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/model" -> handleModel(args, sender);
            case "/clear" -> handleClear(sender);
            case "/session" -> handleSession(args, sender);
            case "/help", "/?" -> handleHelp(sender);
            default -> sender.accept(ChatEvent.error(
                    "Unknown command: " + command + " (type /help for available commands)"));
        }
    }

    private void handleModel(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            sender.accept(ChatEvent.info("Current model: " + cliProcess.getConfig().model()));
        } else {
            cliProcess.setConfig(cliProcess.getConfig().withModel(args));
            sender.accept(ChatEvent.info("Model changed to: " + args));
        }
    }

    private void handleClear(Consumer<ChatEvent> sender) {
        cliProcess.setConfig(cliProcess.getConfig().withSessionId(null));
        sender.accept(ChatEvent.info("Session cleared. Starting fresh conversation."));
    }

    private void handleSession(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            String sessionId = cliProcess.getLastSessionId();
            sender.accept(ChatEvent.info(sessionId != null
                    ? "Current session: " + sessionId : "No active session."));
        } else {
            cliProcess.setConfig(cliProcess.getConfig().withSessionId(args));
            sender.accept(ChatEvent.info("Session set to: " + args));
        }
    }

    private void handleHelp(Consumer<ChatEvent> sender) {
        sender.accept(ChatEvent.info("""
            Available commands:
              /help, /?          Show this help
              /model [name]      Show or change the model
              /session [id]      Show or set session ID
              /clear             Clear session (start fresh)"""));
    }
}
