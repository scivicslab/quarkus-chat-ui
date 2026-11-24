package com.scivicslab.chatui.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model, effort, thread id and developer instructions of a Codex conversation belong to its owner: any thread writes and reads them by messages. */
class CodexSettingsOwnerTest {

    private static CodexProvider provider(Path dir) throws Exception {
        Path fake = dir.resolve("unused");
        Files.writeString(fake, "#!/bin/sh\nexit 0\n");
        fake.toFile().setExecutable(true);
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 1, "sonnet", null, dir);
        return new CodexProvider(settings, "project", "chat", dir, List.of(fake.toString()));
    }

    @Test
    void aWriteFromAnotherThreadIsReadByTheNextCommandAndByTheModelShownToAPage(@TempDir Path dir) throws Exception {
        CodexProvider provider = provider(dir);
        CompletableFuture.runAsync(() -> {
            provider.setModel("gpt-6-luna");
            provider.handleCommand("/effort low");
            provider.setSystemMessage("be brief");
        }, command -> Thread.ofVirtual().start(command)).get(5, TimeUnit.SECONDS);

        assertEquals("gpt-6-luna", provider.getCurrentModel());
        List<String> command = provider.buildCommand();
        assertEquals("gpt-6-luna", command.get(command.indexOf("-m") + 1));
        assertTrue(command.contains("model_reasoning_effort=\"low\""));
        assertTrue(String.join(" ", command).contains("be brief"), "the developer instructions are in the command: " + command);
    }

    @Test
    void manyThreadsWritingTheModelNeverShowAPageAnythingButAWrittenValue(@TempDir Path dir) throws Exception {
        CodexProvider provider = provider(dir);
        CompletableFuture<Void> writers = CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> { for (int i = 0; i < 300; i++) provider.setModel("a" + i); },
                        command -> Thread.ofVirtual().start(command)),
                CompletableFuture.runAsync(() -> { for (int i = 0; i < 300; i++) provider.setModel("b" + i); },
                        command -> Thread.ofVirtual().start(command)));
        for (int i = 0; i < 300; i++) {
            String shown = provider.getCurrentModel();
            assertTrue(shown == null || shown.matches("[ab]\\d+"), "a page saw " + shown);
        }
        writers.get(10, TimeUnit.SECONDS);
    }
}
