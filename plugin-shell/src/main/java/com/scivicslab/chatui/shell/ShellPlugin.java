package com.scivicslab.chatui.shell;

import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.ConversationTool;

import org.json.JSONObject;

import java.nio.file.Path;
import java.util.List;

/**
 * The plugin that gives a conversation a shell ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * <p>Which start-up configurations have one is decided on the command line, like every other
 * capability: the configuration with no way out to the web passes no plugin jar and so has no
 * {@code bash}, while the two that already reach the web pass this jar as well.</p>
 *
 * <p>The conversation's own file tools stay as they are. They are bounded by a
 * {@code FileAccessScope}, and this is not — a shell cannot be made to do less than a shell. What
 * it is bounded by is the record: every command and its whole output go into the I/O log, which is
 * the reason for running it through this program rather than in a terminal.</p>
 */
public class ShellPlugin implements ChatUiPlugin {

    /** The property the body reads for the directory the conversation calls its own. */
    private static final String WRITE_ROOT = "chat-ui.write-root";

    @Override public String id() { return "shell"; }

    @Override
    public List<ConversationTool> tools() {
        return List.of(new Bash());
    }

    /** Where a command starts: the conversation's working directory, or the process's own. */
    static Path workingDirectory() {
        String configured = System.getProperty(WRITE_ROOT);
        return (configured == null || configured.isBlank())
                ? Path.of("").toAbsolutePath()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    /** {@code bash(command)}: one command through {@code /bin/sh -c}, output and status returned. */
    static final class Bash implements ConversationTool {
        @Override public String name() { return "bash"; }

        @Override
        public String description() {
            return """
                    - bash(command): run one shell command in the working directory and get back its exit
                      status and output (stdout and stderr together). Pipes and redirection work. Each call
                      is a new shell, so a cd does not carry over. Killed after 120 seconds; output past
                      30,000 characters is cut with a note. Every command and its output are recorded.
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            String command;
            try {
                command = new JSONObject(argumentsJson == null ? "{}" : argumentsJson)
                        .optString("command", "");
            } catch (Exception e) {
                return "error: could not read the command from the call's arguments";
            }
            return ShellTool.run(workingDirectory(), command);
        }
    }
}
