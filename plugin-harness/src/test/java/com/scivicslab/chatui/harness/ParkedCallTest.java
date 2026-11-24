package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.ConversationMcp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness CLI's call to a tool of chat-ui's MCP server is parked into the conversation's agent loop (tasks/todo.md,
 * "Shrinking the composed prompt"): the running prompt returns with the call, the CLI waits, the call's result goes back
 * as the MCP response, and the prompt goes on to the CLI's result. When the conversation's turn ends first, the call
 * is told so and the CLI's turn is stopped. The fake CLIs stand for the MCP request with a file: the test writes the
 * result the CLI would have received into it.
 */
class ParkedCallTest {

    private static Path script(Path dir, String name, String body) throws Exception {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + body);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private static String waitForFile(Path file) {
        return "while [ ! -f '" + file + "' ]; do sleep 0.05; done\n";
    }

    private static final String INIT = "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\"}'\n";
    private static final String RESULT = "echo '{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"\",\"session_id\":\"s1\"}'\n";

    private static String assistant(String textExpression) {
        return "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":{\\\"role\\\":\\\"assistant\\\",\\\"content\\\":"
                + "[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"" + textExpression + "\\\"}]}}\"\n";
    }

    private static void awaitEvent(List<StreamEvent> events, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (events.stream().noneMatch(e -> type.equals(e.type()))) {
            assertTrue(System.currentTimeMillis() < deadline, "no " + type + " event: " + events);
            Thread.sleep(20);
        }
    }

    @Test
    void aParkedCallSuspendsThePromptAndItsResultResumesIt(@TempDir Path dir) throws Exception {
        Path answer = dir.resolve("answer");
        Path cli = script(dir, "fake-claude", "read line\n" + INIT + waitForFile(answer)
                + assistant("got $(cat '" + answer + "')") + RESULT + "read line\n");
        CliProcess process = new CliProcess(cli.toString(), "UNUSED_KEY", CliConfig.defaults(null));
        List<StreamEvent> events = new CopyOnWriteArrayList<>();

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                return process.sendPrompt("compute", events::add);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, r -> Thread.ofVirtual().start(r));
        awaitEvent(events, "system");
        CompletableFuture<String> call = process.park("calc", "{\"expression\":\"23*47\"}");

        assertEquals(CliProcess.SUSPENDED, first.get(10, TimeUnit.SECONDS));
        assertTrue(process.isSuspended());
        StreamEvent parked = events.stream().filter(e -> StreamEvent.PARKED_CALL.equals(e.type())).findFirst().orElseThrow();
        assertEquals("calc", parked.toolName());
        assertEquals("{\"expression\":\"23*47\"}", parked.content());
        assertFalse(call.isDone(), "the CLI waits until the conversation has run the call");

        process.answer(parked.toolUseId(), "1081");
        assertEquals("1081", call.get(1, TimeUnit.SECONDS));
        Files.writeString(answer, call.get());
        assertEquals(0, process.resume(events::add));
        assertFalse(process.isSuspended());
        assertTrue(events.stream().anyMatch(e -> "assistant".equals(e.type()) && "got 1081".equals(e.content())),
                events.toString());
        process.cancel();
    }

    @Test
    void theEndOfTheTurnAnswersTheParkedCallAndInterruptsTheCli(@TempDir Path dir) throws Exception {
        Path control = dir.resolve("control");
        Path cli = script(dir, "fake-claude", "read line\n" + INIT
                + "read ctl\necho \"$ctl\" > '" + control + "'\n" + RESULT
                + "read line\n" + assistant("second") + RESULT + "read line\n");
        CliProcess process = new CliProcess(cli.toString(), "UNUSED_KEY", CliConfig.defaults(null));
        List<StreamEvent> events = new CopyOnWriteArrayList<>();

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                return process.sendPrompt("ask the person", events::add);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, r -> Thread.ofVirtual().start(r));
        awaitEvent(events, "system");
        CompletableFuture<String> call = process.park("ask_person", "{\"question\":\"which?\"}");
        assertEquals(CliProcess.SUSPENDED, first.get(10, TimeUnit.SECONDS));

        process.endTurn(5_000);

        assertEquals(CliProcess.TURN_ENDED, call.get(1, TimeUnit.SECONDS));
        assertTrue(Files.readString(control).contains("\"subtype\":\"interrupt\""), Files.readString(control));
        assertFalse(process.isSuspended());
        assertTrue(process.isAlive(), "the CLI reached its result and keeps its session");

        List<StreamEvent> next = new CopyOnWriteArrayList<>();
        assertEquals(0, process.sendPrompt("go on", next::add));
        assertEquals(List.of("second"), next.stream().filter(e -> "assistant".equals(e.type()))
                .map(StreamEvent::content).toList(), "nothing of the interrupted turn reaches the next prompt");
        process.cancel();
    }

    @Test
    void aCallWithNoTurnRunningIsRefused(@TempDir Path dir) throws Exception {
        CliProcess process = new CliProcess(script(dir, "fake-claude", "read line\n").toString(), "UNUSED_KEY",
                CliConfig.defaults(null));
        assertTrue(process.park("calc", "{}").get(1, TimeUnit.SECONDS).startsWith("error: no turn"));
    }

    @Test
    void codexParksACallAndGoesOnWithItsResult(@TempDir Path dir) throws Exception {
        Path answer = dir.resolve("answer");
        Path cli = script(dir, "fake-codex", "cat > /dev/null\n"
                + "echo '{\"type\":\"thread.started\",\"thread_id\":\"t1\"}'\n"
                + waitForFile(answer)
                + "echo \"{\\\"type\\\":\\\"item.completed\\\",\\\"item\\\":{\\\"id\\\":\\\"i1\\\",\\\"type\\\":\\\"agent_message\\\","
                + "\\\"text\\\":\\\"got $(cat '" + answer + "')\\\"}}\"\n"
                + "echo '{\"type\":\"turn.completed\"}'\n");
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 18090, "sonnet",
                null, dir.resolve("claude-projects"));
        ConversationMcp mcp = new ConversationMcp("http://127.0.0.1:9/mcp", "project1/chat-01", "k");
        CodexProvider codex = new CodexProvider(settings, "project1", "01", dir, mcp, cli.toString());
        assertTrue(codex.parksToolCalls() && codex.callsToolsByDefinition());
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        ProviderContext ctx = new ProviderContext(null, List.of(), false, () -> {}, body -> {}, true);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> codex.sendPrompt("compute", null, events::add, ctx),
                r -> Thread.ofVirtual().start(r));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!"t1".equals(codex.getSessionId())) {
            assertTrue(System.currentTimeMillis() < deadline, "codex never started its thread");
            Thread.sleep(20);
        }
        CompletableFuture<String> call = codex.parkToolCall("calc", "{\"expression\":\"23*47\"}");
        first.get(10, TimeUnit.SECONDS);
        ChatEvent parked = events.stream().filter(e -> "tool_call".equals(e.type())).findFirst().orElseThrow();
        assertEquals("calc", parked.toolName());
        assertTrue(events.stream().noneMatch(e -> "result".equals(e.type())), "the run has not ended: " + events);

        codex.addToolResults(List.of(new LlmProvider.ToolCallResult(parked.toolUseId(), "calc", "1081")));
        Files.writeString(answer, call.get(1, TimeUnit.SECONDS));
        codex.sendPrompt("", null, events::add, ctx);
        assertTrue(events.stream().anyMatch(e -> "delta".equals(e.type()) && "got 1081".equals(e.content())), events.toString());
        assertTrue(events.stream().anyMatch(e -> "result".equals(e.type())));
    }

    @Test
    void codexIsHandedTheMcpServerAndTheSystemMessage(@TempDir Path dir) {
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 18090, "sonnet",
                "gpt-5.5", dir.resolve("claude-projects"));
        CodexProvider codex = new CodexProvider(settings, "project1", "01", dir,
                new ConversationMcp("http://127.0.0.1:28030/mcp", "project1/chat-01", "secret-key"));
        codex.setSystemMessage("line \"one\"\nline two");
        List<String> cmd = codex.buildCommand();
        assertTrue(cmd.contains("mcp_servers.chat-ui.url=\"http://127.0.0.1:28030/mcp\""), cmd.toString());
        assertTrue(cmd.contains("mcp_servers.chat-ui.bearer_token_env_var=\"CHAT_UI_MCP_KEY\""), cmd.toString());
        assertTrue(cmd.contains("mcp_servers.chat-ui.http_headers={\"X-Agent-Connector-Scope\"=\"project1/chat-01\"}"),
                cmd.toString());
        assertTrue(cmd.contains("developer_instructions=\"line \\\"one\\\"\\nline two\""), cmd.toString());
        assertFalse(String.join(" ", cmd).contains("secret-key"), "the key is not on the command line");
        assertEquals("secret-key", codex.launch(cmd, null).addedEnv().get("CHAT_UI_MCP_KEY"));
    }

    @Test
    void claudeIsHandedTheMcpServerInAFileOnlyItsUserReads(@TempDir Path dir) throws Exception {
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 18090, "sonnet",
                null, dir.resolve("claude-projects"));
        ClaudeCodeProvider claude = new ClaudeCodeProvider(settings, "project1", "01", dir,
                com.scivicslab.chatui.agent.ToolSet.HARNESS,
                new ConversationMcp("http://127.0.0.1:28030/mcp", "project1/chat-01", "secret-key"));
        assertTrue(claude.parksToolCalls() && claude.callsToolsByDefinition());
        assertFalse(claude.promptPreface().contains("<invoke>"), claude.promptPreface());
        Path file = settings.sessionFile("project1", "01", "claude").resolveSibling(
                settings.sessionFile("project1", "01", "claude").getFileName() + ".mcp.json");
        org.json.JSONObject server = new org.json.JSONObject(Files.readString(file))
                .getJSONObject("mcpServers").getJSONObject("chat-ui");
        assertEquals("http://127.0.0.1:28030/mcp", server.getString("url"));
        assertEquals("Bearer secret-key", server.getJSONObject("headers").getString("Authorization"));
        assertEquals("project1/chat-01", server.getJSONObject("headers").getString("X-Agent-Connector-Scope"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(file)));
    }
}
