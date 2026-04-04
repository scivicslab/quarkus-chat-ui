package com.scivicslab.chatui.cli.command;

import com.scivicslab.chatui.cli.process.CliConfig;
import com.scivicslab.chatui.cli.process.CliProcess;
import com.scivicslab.chatui.core.rest.ChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlashCommandHandlerTest {

    /**
     * Minimal CliProcess stub that avoids spawning real OS processes.
     * Only config and lastSessionId are relevant to SlashCommandHandler.
     */
    private static class StubCliProcess extends CliProcess {
        private final String lastSessionId;

        StubCliProcess(CliConfig config, String lastSessionId) {
            super("stub-binary", "STUB_API_KEY", config);
            this.lastSessionId = lastSessionId;
        }

        StubCliProcess(CliConfig config) {
            this(config, null);
        }

        @Override
        public String getLastSessionId() {
            return lastSessionId;
        }
    }

    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    private CliConfig config;
    private StubCliProcess cliProcess;
    private SlashCommandHandler handler;
    private List<ChatEvent> events;

    @BeforeEach
    void setUp() {
        config = CliConfig.defaults(DEFAULT_MODEL);
        cliProcess = new StubCliProcess(config);
        handler = new SlashCommandHandler(cliProcess);
        events = new ArrayList<>();
    }

    // ---------------------------------------------------------------
    // isCommand()
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("isCommand()")
    class IsCommand {

        @Test
        @DisplayName("returns true for strings starting with /")
        void returnsTrue_forSlashPrefix() {
            assertTrue(handler.isCommand("/help"));
            assertTrue(handler.isCommand("/model"));
            assertTrue(handler.isCommand("/clear"));
            assertTrue(handler.isCommand("/session"));
            assertTrue(handler.isCommand("/?"));
            assertTrue(handler.isCommand("/unknown"));
        }

        @Test
        @DisplayName("returns true for / alone")
        void returnsTrue_forSlashAlone() {
            assertTrue(handler.isCommand("/"));
        }

        @Test
        @DisplayName("returns false for normal text")
        void returnsFalse_forNormalText() {
            assertFalse(handler.isCommand("hello world"));
            assertFalse(handler.isCommand("what is /help?"));
            assertFalse(handler.isCommand("model sonnet"));
        }

        @Test
        @DisplayName("returns false for null input")
        void returnsFalse_forNull() {
            assertFalse(handler.isCommand(null));
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalse_forEmptyString() {
            assertFalse(handler.isCommand(""));
        }

        @Test
        @DisplayName("returns false for whitespace-only string")
        void returnsFalse_forWhitespace() {
            assertFalse(handler.isCommand("   "));
            assertFalse(handler.isCommand("\t"));
            assertFalse(handler.isCommand("\n"));
        }

        @Test
        @DisplayName("returns false when slash is not the first character")
        void returnsFalse_forSlashNotAtStart() {
            assertFalse(handler.isCommand(" /help"));
            assertFalse(handler.isCommand("hello /help"));
        }
    }

    // ---------------------------------------------------------------
    // handle() - /help and /?
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handle() /help command")
    class HandleHelp {

        @Test
        @DisplayName("/help sends info event containing available commands")
        void help_sendsInfoWithAvailableCommands() {
            handler.handle("/help", events::add);

            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains("/help"));
            assertTrue(event.content().contains("/model"));
            assertTrue(event.content().contains("/session"));
            assertTrue(event.content().contains("/clear"));
        }

        @Test
        @DisplayName("/? is an alias for /help")
        void questionMark_isAliasForHelp() {
            handler.handle("/?", events::add);

            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains("/help"));
        }

        @Test
        @DisplayName("/HELP works case-insensitively")
        void help_caseInsensitive() {
            handler.handle("/HELP", events::add);

            assertEquals(1, events.size());
            assertEquals("info", events.getFirst().type());
            assertTrue(events.getFirst().content().contains("/help"));
        }
    }

    // ---------------------------------------------------------------
    // handle() - /clear
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handle() /clear command")
    class HandleClear {

        @Test
        @DisplayName("/clear resets sessionId to null in config")
        void clear_resetsSessionId() {
            cliProcess.setConfig(config.withSessionId("existing-session"));

            handler.handle("/clear", events::add);

            assertNull(cliProcess.getConfig().sessionId());
        }

        @Test
        @DisplayName("/clear sends info event about fresh conversation")
        void clear_sendsInfoEvent() {
            handler.handle("/clear", events::add);

            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains("Session cleared"));
        }

        @Test
        @DisplayName("/CLEAR works case-insensitively")
        void clear_caseInsensitive() {
            handler.handle("/CLEAR", events::add);

            assertEquals(1, events.size());
            assertEquals("info", events.getFirst().type());
        }
    }

    // ---------------------------------------------------------------
    // handle() - /model
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handle() /model command")
    class HandleModel {

        @Test
        @DisplayName("/model without argument shows current model")
        void model_noArgs_showsCurrentModel() {
            handler.handle("/model", events::add);

            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains(DEFAULT_MODEL));
            assertTrue(event.content().contains("Current model"));
        }

        @Test
        @DisplayName("/model with argument changes the model")
        void model_withArg_changesModel() {
            handler.handle("/model claude-opus-4", events::add);

            assertEquals("claude-opus-4", cliProcess.getConfig().model());
            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains("claude-opus-4"));
            assertTrue(event.content().contains("Model changed"));
        }

        @Test
        @DisplayName("/model preserves other config fields when changing model")
        void model_preservesOtherConfigFields() {
            cliProcess.setConfig(config.withSessionId("s-123").withMaxTurns(5));

            handler.handle("/model claude-opus-4", events::add);

            CliConfig updated = cliProcess.getConfig();
            assertEquals("claude-opus-4", updated.model());
            assertEquals("s-123", updated.sessionId());
            assertEquals(5, updated.maxTurns());
        }

        @Test
        @DisplayName("/MODEL works case-insensitively")
        void model_caseInsensitive() {
            handler.handle("/MODEL claude-opus-4", events::add);

            assertEquals("claude-opus-4", cliProcess.getConfig().model());
            assertEquals(1, events.size());
        }

        @Test
        @DisplayName("/model trims whitespace around argument")
        void model_trimsWhitespace() {
            handler.handle("/model   claude-opus-4  ", events::add);

            assertEquals("claude-opus-4", cliProcess.getConfig().model());
        }
    }

    // ---------------------------------------------------------------
    // handle() - /session
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handle() /session command")
    class HandleSession {

        @Test
        @DisplayName("/session without argument shows current session when present")
        void session_noArgs_showsCurrent() {
            StubCliProcess processWithSession = new StubCliProcess(config, "sess-xyz-789");
            SlashCommandHandler handlerWithSession = new SlashCommandHandler(processWithSession);

            handlerWithSession.handle("/session", events::add);

            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains("sess-xyz-789"));
            assertTrue(event.content().contains("Current session"));
        }

        @Test
        @DisplayName("/session without argument shows 'No active session' when null")
        void session_noArgs_showsNoActiveSession() {
            handler.handle("/session", events::add);

            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains("No active session"));
        }

        @Test
        @DisplayName("/session with argument sets session ID in config")
        void session_withArg_setsSessionId() {
            handler.handle("/session new-session-id", events::add);

            assertEquals("new-session-id", cliProcess.getConfig().sessionId());
            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("info", event.type());
            assertTrue(event.content().contains("new-session-id"));
            assertTrue(event.content().contains("Session set to"));
        }

        @Test
        @DisplayName("/SESSION works case-insensitively")
        void session_caseInsensitive() {
            handler.handle("/SESSION my-session", events::add);

            assertEquals("my-session", cliProcess.getConfig().sessionId());
        }
    }

    // ---------------------------------------------------------------
    // handle() - unknown commands
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handle() unknown commands")
    class HandleUnknown {

        @Test
        @DisplayName("unknown command sends error event")
        void unknown_sendsErrorEvent() {
            handler.handle("/foo", events::add);

            assertEquals(1, events.size());
            ChatEvent event = events.getFirst();
            assertEquals("error", event.type());
            assertTrue(event.content().contains("Unknown command"));
            assertTrue(event.content().contains("/foo"));
        }

        @Test
        @DisplayName("unknown command error suggests /help")
        void unknown_suggestsHelp() {
            handler.handle("/bar", events::add);

            assertTrue(events.getFirst().content().contains("/help"));
        }

        @Test
        @DisplayName("/ alone is treated as unknown command")
        void slashAlone_isUnknown() {
            handler.handle("/", events::add);

            assertEquals(1, events.size());
            assertEquals("error", events.getFirst().type());
            assertTrue(events.getFirst().content().contains("Unknown command"));
        }
    }

    // ---------------------------------------------------------------
    // handle() - case insensitivity
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handle() case insensitivity")
    class CaseInsensitivity {

        @Test
        @DisplayName("mixed case /HeLp is handled correctly")
        void mixedCase_help() {
            handler.handle("/HeLp", events::add);

            assertEquals(1, events.size());
            assertEquals("info", events.getFirst().type());
            assertTrue(events.getFirst().content().contains("/help"));
        }

        @Test
        @DisplayName("mixed case /MoDeL with argument works")
        void mixedCase_modelWithArg() {
            handler.handle("/MoDeL gpt-4", events::add);

            assertEquals("gpt-4", cliProcess.getConfig().model());
        }

        @Test
        @DisplayName("mixed case /ClEaR works")
        void mixedCase_clear() {
            cliProcess.setConfig(config.withSessionId("s-1"));

            handler.handle("/ClEaR", events::add);

            assertNull(cliProcess.getConfig().sessionId());
        }

        @Test
        @DisplayName("mixed case /SeSsIoN with argument works")
        void mixedCase_sessionWithArg() {
            handler.handle("/SeSsIoN my-sess", events::add);

            assertEquals("my-sess", cliProcess.getConfig().sessionId());
        }
    }

    // ---------------------------------------------------------------
    // handle() - whitespace handling
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("handle() whitespace handling")
    class WhitespaceHandling {

        @Test
        @DisplayName("leading/trailing whitespace in input is trimmed")
        void leadingTrailingWhitespace_isTrimmed() {
            handler.handle("  /help  ", events::add);

            assertEquals(1, events.size());
            assertEquals("info", events.getFirst().type());
        }

        @Test
        @DisplayName("multiple spaces between command and argument are handled")
        void multipleSpaces_betweenCommandAndArg() {
            handler.handle("/model     claude-opus-4", events::add);

            assertEquals("claude-opus-4", cliProcess.getConfig().model());
        }
    }
}
