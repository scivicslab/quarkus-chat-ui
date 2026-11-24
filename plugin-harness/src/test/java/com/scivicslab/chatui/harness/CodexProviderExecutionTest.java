package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexProviderExecutionTest {

    @Test
    void freshTurnThenResumeUseCodexJsonProtocol(@TempDir Path dir) throws Exception {
        Path fake = executable(dir, "fake-codex", """
                #!/bin/sh
                input=$(cat)
                case " $* " in
                  *" resume "*) answer=RESUMED ;;
                  *) answer=FIRST ;;
                esac
                printf '%s\n' '{"type":"thread.started","thread_id":"thread-1"}'
                printf '%s\n' '{"type":"turn.started"}'
                printf '{"type":"item.completed","item":{"id":"item-0","type":"agent_message","text":"%s"}}\n' "$answer"
                printf '%s\n' '{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1}}'
                """);
        CodexProvider provider = provider(dir, fake);

        List<ChatEvent> first = new ArrayList<>();
        provider.sendPrompt("first prompt", null, first::add, ProviderContext.simple(null));
        assertEquals("thread-1", provider.getSessionId());
        assertTrue(first.stream().anyMatch(e -> "delta".equals(e.type()) && "FIRST".equals(e.content())));
        assertEquals("result", first.getLast().type());

        List<ChatEvent> resumed = new ArrayList<>();
        provider.sendPrompt("second prompt", null, resumed::add, ProviderContext.simple(null));
        assertTrue(resumed.stream().anyMatch(e -> "delta".equals(e.type()) && "RESUMED".equals(e.content())));
        assertEquals("result", resumed.getLast().type());
    }

    @Test
    void processFailureSurfacesStderrAndStillEndsTheTurn(@TempDir Path dir) throws Exception {
        Path fake = executable(dir, "failing-codex", """
                #!/bin/sh
                cat >/dev/null
                echo 'login required' >&2
                exit 1
                """);
        CodexProvider provider = provider(dir, fake);
        List<ChatEvent> events = new ArrayList<>();

        provider.sendPrompt("hello", null, events::add, ProviderContext.simple(null));

        assertTrue(events.stream().anyMatch(e -> "error".equals(e.type()) && e.content().contains("login required")));
        assertEquals("result", events.getLast().type());
    }

    @Test
    void missingResumeThreadIsRetriedOnceAsAFreshConversation(@TempDir Path dir) throws Exception {
        Path fake = executable(dir, "stale-codex", """
                #!/bin/sh
                cat >/dev/null
                case " $* " in
                  *" resume "*)
                    echo 'Error: thread/resume failed: no rollout found for thread id stale-thread' >&2
                    exit 1
                    ;;
                esac
                printf '%s\n' '{"type":"thread.started","thread_id":"fresh-thread"}'
                printf '%s\n' '{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"RECOVERED"}}'
                printf '%s\n' '{"type":"turn.completed"}'
                """);
        CodexProvider provider = provider(dir, fake);
        provider.handleCommand("/session stale-thread");
        List<ChatEvent> events = new ArrayList<>();

        provider.sendPrompt("retry me", null, events::add, ProviderContext.simple(null));

        assertEquals("fresh-thread", provider.getSessionId());
        assertTrue(events.stream().anyMatch(e -> "delta".equals(e.type()) && "RECOVERED".equals(e.content())));
        assertFalse(events.stream().anyMatch(e -> "error".equals(e.type())));
    }

    @Test
    void unsupportedSavedModelFallsBackToTheCodexDefault(@TempDir Path dir) throws Exception {
        Path fake = executable(dir, "model-fallback-codex", """
                #!/bin/sh
                cat >/dev/null
                case " $* " in
                  *" -m obsolete-model "*)
                    echo "The 'obsolete-model' model is not supported when using Codex" >&2
                    exit 1
                    ;;
                esac
                printf '%s\n' '{"type":"thread.started","thread_id":"default-thread"}'
                printf '%s\n' '{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"DEFAULT_OK"}}'
                printf '%s\n' '{"type":"turn.completed"}'
                """);
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 1,
                "sonnet", "obsolete-model", dir);
        CodexProvider provider = new CodexProvider(settings, "project", "chat", dir, List.of(fake.toString()));
        List<ChatEvent> events = new ArrayList<>();

        provider.sendPrompt("retry model", null, events::add, ProviderContext.simple(null));

        assertEquals("default-thread", provider.getSessionId());
        assertEquals(null, provider.getCurrentModel());
        assertTrue(events.stream().anyMatch(e -> "delta".equals(e.type()) && "DEFAULT_OK".equals(e.content())));
        assertFalse(events.stream().anyMatch(e -> "error".equals(e.type())));
    }

    @Test
    void commandsSetEconomicalModelAndReasoningEffort(@TempDir Path dir) throws Exception {
        CodexProvider provider = provider(dir, executable(dir, "unused", "#!/bin/sh\nexit 0\n"));
        provider.handleCommand("/model gpt-6-luna");
        provider.handleCommand("/effort low");

        List<String> command = provider.buildCommand();
        assertEquals("gpt-6-luna", command.get(command.indexOf("-m") + 1));
        assertTrue(command.contains("model_reasoning_effort=\"low\""));
        assertFalse(provider.capabilities().supportsInteractivePrompts());
        assertTrue(provider.capabilities().supportsImages());
    }

    @Test
    void imageDataUrlIsPassedAsAnExistingImageFile(@TempDir Path dir) throws Exception {
        Path fake = executable(dir, "image-codex", """
                #!/bin/sh
                cat >/dev/null
                previous=
                answer=IMAGE_MISSING
                for argument in "$@"; do
                  if [ "$previous" = "-i" ] && [ -f "$argument" ]; then answer=IMAGE_OK; fi
                  previous="$argument"
                done
                printf '%s\n' '{"type":"thread.started","thread_id":"image-thread"}'
                printf '{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"%s"}}\n' "$answer"
                printf '%s\n' '{"type":"turn.completed"}'
                """);
        CodexProvider provider = provider(dir, fake);
        List<ChatEvent> events = new ArrayList<>();
        String image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

        provider.sendPrompt("inspect", null, events::add,
                new ProviderContext(null, List.of(image), false, () -> { }));

        assertTrue(events.stream().anyMatch(e -> "delta".equals(e.type()) && "IMAGE_OK".equals(e.content())));
    }

    private static CodexProvider provider(Path dir, Path executable) {
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 1,
                "sonnet", null, dir);
        return new CodexProvider(settings, "project", "chat", dir, List.of(executable.toString()));
    }

    private static Path executable(Path dir, String name, String script) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, script);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }
}
