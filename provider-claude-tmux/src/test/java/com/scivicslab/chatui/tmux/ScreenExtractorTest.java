package com.scivicslab.chatui.tmux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScreenExtractor}, exercising the load-bearing extraction
 * path (chrome stripping, marker parsing, diffing, approval detection) against
 * fixtures captured from a real Claude Code TUI inside tmux.
 *
 * <p>Pure logic, no external services: this is a unit test (no {@code @QuarkusTest}).
 * It proves the codebase has reached transition {@code TmuxTuiDriver_Extract_260630_oo01}.
 */
@Tag("TmuxTuiDriver_Extract_260630_oo01")
@DisplayName("ScreenExtractor — tmux capture-pane content extraction")
class ScreenExtractorTest {

    private static String fixture(String name) {
        try (InputStream in = ScreenExtractorTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, "fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("stripChrome removes banner, input box and status, keeps assistant line")
    void stripChrome_settledScreen_dropsChromeKeepsContent() {
        List<String> content = ScreenExtractor.stripChrome(fixture("settled_hello.txt"));
        String joined = String.join("\n", content);

        assertTrue(joined.contains("● hello from claude"), "assistant line must survive");
        assertFalse(joined.contains("╭"), "banner box must be dropped");
        assertFalse(joined.contains("? for shortcuts"), "status line must be dropped");
        assertFalse(joined.contains("✻"), "thinking spinner must be dropped");
        assertFalse(joined.contains("──────────"), "horizontal rules must be dropped");
    }

    @Test
    @DisplayName("ingest of a settled screen emits one AssistantMessage")
    void ingest_settledScreen_emitsAssistantMessage() {
        List<ExtractedEvent> events = new ScreenExtractor().ingest(fixture("settled_hello.txt"));

        assertEquals(1, events.size());
        assertInstanceOf(AssistantMessage.class, events.get(0));
        assertEquals("hello from claude", ((AssistantMessage) events.get(0)).text());
    }

    @Test
    @DisplayName("ingesting the same screen twice emits nothing the second time")
    void ingest_sameScreenTwice_secondEmitsNothing() {
        ScreenExtractor extractor = new ScreenExtractor();
        String settled = fixture("settled_hello.txt");

        extractor.ingest(settled);
        List<ExtractedEvent> second = extractor.ingest(settled);

        assertTrue(second.isEmpty(), "a redrawn identical screen must not re-emit content");
    }

    @Test
    @DisplayName("detectApproval reads the trust dialog options and prompt")
    void detectApproval_trustDialog_returnsOptionsAndPrompt() {
        Optional<ApprovalRequested> approval = ScreenExtractor.detectApproval(fixture("trust_dialog.txt"));

        assertTrue(approval.isPresent());
        assertEquals(List.of("Yes, I trust this folder", "No, exit"), approval.get().options());
        assertTrue(approval.get().prompt().contains("Quick safety check"),
                "prompt should carry the question text");
    }

    @Test
    @DisplayName("detectApproval does not false-positive on a normal settled screen")
    void detectApproval_settledScreen_returnsEmpty() {
        assertTrue(ScreenExtractor.detectApproval(fixture("settled_hello.txt")).isEmpty());
    }

    @Test
    @DisplayName("detectApproval reads a tool-permission dialog (Esc-to-cancel footer)")
    void detectApproval_permissionDialog_readsOptions() {
        Optional<ApprovalRequested> approval = ScreenExtractor.detectApproval(fixture("permission_dialog.txt"));

        assertTrue(approval.isPresent(), "tool-permission dialog must be detected");
        assertEquals(
                List.of("Yes",
                        "Yes, allow all edits in tmp/ during this session (shift+tab)",
                        "No"),
                approval.get().options());
        assertTrue(approval.get().prompt().contains("Do you want to create claude_tmux_demo.txt?"),
                "prompt: " + approval.get().prompt());
    }

    @Test
    @DisplayName("ingest of a trust dialog emits an ApprovalRequested event")
    void ingest_trustDialog_emitsApprovalRequested() {
        List<ExtractedEvent> events = new ScreenExtractor().ingest(fixture("trust_dialog.txt"));

        assertEquals(1, events.size());
        assertInstanceOf(ApprovalRequested.class, events.get(0));
    }

    @Test
    @DisplayName("isInputReady is true for a settled screen and false for a dialog")
    void isInputReady_distinguishesReadyFromDialog() {
        assertTrue(ScreenExtractor.isInputReady(fixture("settled_hello.txt")));
        assertFalse(ScreenExtractor.isInputReady(fixture("trust_dialog.txt")));
    }

    @Test
    @DisplayName("a tool-result (⎿) block is extracted as a ToolResult, not absorbed into the message")
    void ingest_toolResult_extractedSeparately() {
        List<ExtractedEvent> events = new ScreenExtractor().ingest(fixture("tool_result.txt"));

        // Assistant lines (incl. the tool-use line) stay AssistantMessages; the ⎿ block is a ToolResult.
        ToolResult tr = events.stream()
                .filter(e -> e instanceof ToolResult)
                .map(e -> (ToolResult) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ToolResult extracted; got " + events));
        assertTrue(tr.text().contains("file1.txt"), "tool result text: " + tr.text());
        assertTrue(tr.text().contains("file3.txt"), "continuation lines should be included: " + tr.text());

        // The "I'll list the files." assistant line must not have swallowed the ⎿ block.
        boolean assistantHasResult = events.stream()
                .filter(e -> e instanceof AssistantMessage)
                .map(e -> ((AssistantMessage) e).text())
                .anyMatch(t -> t.contains("file1.txt"));
        assertFalse(assistantHasResult, "AssistantMessage must not absorb the tool result");
    }
}
