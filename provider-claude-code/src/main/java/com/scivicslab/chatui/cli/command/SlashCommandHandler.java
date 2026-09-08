package com.scivicslab.chatui.cli.command;

import com.scivicslab.chatui.cli.process.CliProcess;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Handles slash commands from the Web UI for CLI-based providers.
 *
 * <p>Supported commands: /model, /effort, /session, /clear, /help</p>
 */
public class SlashCommandHandler {

    private final CliProcess cliProcess;

    /**
     * Creates a handler backed by the given CLI process.
     *
     * @param cliProcess the CLI process whose configuration is modified by commands
     */
    public SlashCommandHandler(CliProcess cliProcess) {
        this.cliProcess = cliProcess;
    }

    /**
     * Checks whether the given input is a slash command.
     *
     * @param input the raw user input
     * @return {@code true} if the input starts with "/"
     */
    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    /**
     * Dispatches a slash command and sends the resulting events to the given consumer.
     *
     * <p>Supported commands: {@code /model}, {@code /effort}, {@code /session},
     * {@code /clear}, {@code /help} (or {@code /?}). Unknown commands produce an error event.</p>
     *
     * @param input  the full command string including the leading slash
     * @param sender callback that receives {@link ChatEvent} responses
     */
    public void handle(String input, Consumer<ChatEvent> sender) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/model" -> handleModel(args, sender);
            case "/effort" -> handleEffort(args, sender);
            case "/clear" -> handleClear(sender);
            case "/session" -> handleSession(args, sender);
            case "/help", "/?" -> handleHelp(sender);
            default -> sender.accept(ChatEvent.error(
                    "Unknown command: " + command + " (type /help for available commands)"));
        }
    }

    /** The effort levels the CLI accepts, for rejecting a typo before it reaches the command line. */
    private static final java.util.List<String> EFFORT_LEVELS =
            java.util.List.of("low", "medium", "high", "xhigh", "max");

    /**
     * Shows or changes how deeply the model thinks.
     *
     * <p>An unset level is reported as the CLI's own default rather than as a value this program
     * chose, because that is what an unset level means: no {@code --effort} on the command line.</p>
     */
    private void handleEffort(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            String current = cliProcess.getConfig().effort();
            sender.accept(ChatEvent.info("Current effort: "
                    + (current == null ? "unset (the CLI's own default)" : current)));
            return;
        }
        String level = args.toLowerCase();
        if (!EFFORT_LEVELS.contains(level)) {
            sender.accept(ChatEvent.error("Unknown effort level: " + args
                    + " (choose one of " + String.join(", ", EFFORT_LEVELS) + ")"));
            return;
        }
        cliProcess.setConfig(cliProcess.getConfig().withEffort(level));
        sender.accept(ChatEvent.info("Effort changed to: " + level));
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
        cliProcess.cancel();
        cliProcess.setConfig(cliProcess.getConfig().withSessionId(null));
        cliProcess.clearLastSessionId();
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
              /effort [level]    Show or change the effort level
                                 (low, medium, high, xhigh, max)
              /session [id]      Show or set session ID
              /clear             Clear session (start fresh)"""));
    }
}
