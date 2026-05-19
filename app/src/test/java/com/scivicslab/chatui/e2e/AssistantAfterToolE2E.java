package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E reproducer for the "assistant goes silent after a tool call" bug.
 *
 * <p>Observed scenario: the assistant streams an opening text, makes a tool
 * call, the tool completes, the assistant streams a continuation text, and a
 * result event closes the turn. The continuation text was reported as not
 * visible in the chat area — only the "Tool completed." indicator and footer
 * remained. The user perceived this as the assistant going silent.
 *
 * <p>The bug can live in several places (CLI stream parser, dispatcher,
 * ChatActor accumulator, frontend state machine). This test pins the
 * <em>user-visible contract</em>: when the SSE stream delivers the canonical
 * sequence below, the post-tool delta text must end up in the assistant
 * bubble of the chat area.
 *
 * <p>To remove dependence on a real LLM we replace {@code window.EventSource}
 * with a mock and dispatch a deterministic sequence of SSE events from the
 * test. The page still boots against the running quarkus-chat-ui (init
 * fetches such as {@code /api/config} go to the real backend), but the chat
 * stream itself is driven entirely by the test.
 */
class AssistantAfterToolE2E extends E2eTestBase {

    /**
     * Mock {@code EventSource} installed before app.js runs. The page can
     * call {@code window.__chatTestDispatch(obj)} to deliver an event as if
     * it had arrived from the SSE stream.
     */
    private static final String MOCK_SSE_INIT = ""
            + "(function () {"
            + "  var inst = null;"
            + "  window.__chatTestDispatch = function (obj) {"
            + "    if (!inst) return false;"
            + "    var msg = { data: JSON.stringify(obj) };"
            + "    if (inst.onmessage) inst.onmessage(msg);"
            + "    inst.listeners.message.forEach(function (fn) { fn(msg); });"
            + "    return true;"
            + "  };"
            + "  window.EventSource = function (url) {"
            + "    inst = {"
            + "      url: url, readyState: 0,"
            + "      listeners: { message: [], open: [], error: [] },"
            + "      onopen: null, onmessage: null, onerror: null,"
            + "      addEventListener: function (t, fn) {"
            + "        if (!this.listeners[t]) this.listeners[t] = [];"
            + "        this.listeners[t].push(fn);"
            + "      },"
            + "      removeEventListener: function (t, fn) {"
            + "        if (!this.listeners[t]) return;"
            + "        this.listeners[t] = this.listeners[t].filter(function (f) { return f !== fn; });"
            + "      },"
            + "      close: function () { this.readyState = 2; }"
            + "    };"
            + "    setTimeout(function () {"
            + "      inst.readyState = 1;"
            + "      if (inst.onopen) inst.onopen({});"
            + "      inst.listeners.open.forEach(function (fn) { fn({}); });"
            + "    }, 0);"
            + "    return inst;"
            + "  };"
            + "})();";

    /** Installs the mock, navigates to the app, and waits until SSE is ready. */
    private void setupAndWaitForReady() {
        page.addInitScript(MOCK_SSE_INIT);
        page.navigate(baseUrl());
        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));
        dismissAuthOverlay();
    }

    /** Delivers one SSE event to the mock. The argument is a JSON string. */
    private void dispatch(String json) {
        Object ok = page.evaluate("(j) => window.__chatTestDispatch(JSON.parse(j))", json);
        assertTrue(Boolean.TRUE.equals(ok),
                "Mock SSE was not initialized — addInitScript may not have run.");
    }

    /** Waits for the assistant bubble's {@code .streaming} class to drop, signaling end of turn. */
    private void waitForTurnEnd() {
        page.waitForFunction(
                "() => { var m = document.querySelector('#chat-area .message.assistant');"
                        + " return m && !m.classList.contains('streaming'); }",
                null,
                new Page.WaitForFunctionOptions().setTimeout(5000));
    }

    @Test
    @DisplayName("Bug repro: assistant text emitted AFTER a tool call must remain visible")
    void assistantText_afterToolCall_remainsVisible() {
        setupAndWaitForReady();

        // Canonical "text → tool → text" turn that surfaced the silence.
        // Markers are unique strings so contains() failures are unambiguous.
        dispatch("{\"type\":\"delta\",\"content\":\"BEFORE_TOOL_TEXT_MARKER reading the file now.\\n\\n\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Using Read...\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Tool completed.\"}");
        dispatch("{\"type\":\"delta\",\"content\":\"AFTER_TOOL_TEXT_MARKER this line must be visible to the user.\"}");
        dispatch("{\"type\":\"result\",\"busy\":false,\"sessionId\":\"e2e-mock-session\"}");

        waitForTurnEnd();

        Locator assistant = page.locator("#chat-area .message.assistant").last();
        String html = assistant.innerHTML();
        String text = assistant.textContent();

        // The continuation text MUST be visible. If this fails, the bug is reproduced.
        assertTrue(text.contains("AFTER_TOOL_TEXT_MARKER"),
                "Post-tool assistant text was not rendered.\n"
                        + "--- assistant bubble HTML ---\n" + html + "\n"
                        + "--- assistant bubble text ---\n" + text);

        // Sanity: the opening text should still be there as well.
        assertTrue(text.contains("BEFORE_TOOL_TEXT_MARKER"),
                "Pre-tool assistant text was lost when continuation arrived.\n"
                        + "--- assistant bubble text ---\n" + text);
    }

    @Test
    @DisplayName("No opening text, tool then text — final assistant text must appear")
    void assistantText_onlyAfterTool_remainsVisible() {
        setupAndWaitForReady();

        // Common pattern when the model jumps straight to a tool call.
        dispatch("{\"type\":\"thinking\",\"content\":\"Using Bash...\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Tool completed.\"}");
        dispatch("{\"type\":\"delta\",\"content\":\"DELTA_AFTER_ONLY_TOOL is the entire response body.\"}");
        dispatch("{\"type\":\"result\",\"busy\":false,\"sessionId\":\"e2e-mock-session\"}");

        waitForTurnEnd();

        Locator assistant = page.locator("#chat-area .message.assistant").last();
        String html = assistant.innerHTML();
        String text = assistant.textContent();

        assertTrue(text.contains("DELTA_AFTER_ONLY_TOOL"),
                "Assistant turn ended with only the tool indicator visible.\n"
                        + "--- assistant bubble HTML ---\n" + html + "\n"
                        + "--- assistant bubble text ---\n" + text);
    }

    @Test
    @DisplayName("Multiple tools followed by final text — final text must be visible")
    void multipleTools_thenFinalText_visible() {
        setupAndWaitForReady();

        dispatch("{\"type\":\"thinking\",\"content\":\"Using Read...\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Tool completed.\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Using Bash...\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Tool completed.\"}");
        dispatch("{\"type\":\"delta\",\"content\":\"FINAL_AFTER_TWO_TOOLS summary of what the tools produced.\"}");
        dispatch("{\"type\":\"result\",\"busy\":false,\"sessionId\":\"e2e-mock-session\"}");

        waitForTurnEnd();

        Locator assistant = page.locator("#chat-area .message.assistant").last();
        String text = assistant.textContent();

        assertTrue(text.contains("FINAL_AFTER_TWO_TOOLS"),
                "Final text after multiple tool calls was not rendered. Bubble text:\n" + text);
    }

    @Test
    @DisplayName("End of turn must not leave a bubble that shows only the tool indicator")
    void assistantBubble_atTurnEnd_mustNotBeIndicatorOnly() {
        setupAndWaitForReady();

        // Same shape as the user-reported scenario, observed via the *whole* bubble.
        dispatch("{\"type\":\"delta\",\"content\":\"OPENING_TEXT first I'll check the file.\\n\\n\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Using Bash...\"}");
        dispatch("{\"type\":\"thinking\",\"content\":\"Tool completed.\"}");
        dispatch("{\"type\":\"delta\",\"content\":\"CLOSING_TEXT done — the file is correct.\"}");
        dispatch("{\"type\":\"result\",\"busy\":false,\"sessionId\":\"e2e-mock-session\"}");

        waitForTurnEnd();

        Locator assistant = page.locator("#chat-area .message.assistant").last();
        String text = assistant.textContent();

        // The bubble at end-of-turn must not be effectively empty of LLM text.
        // "Effectively empty" = only contains tool/footer chrome.
        // We check both deltas survive; if not the bubble is degenerate.
        boolean hasOpening = text.contains("OPENING_TEXT");
        boolean hasClosing = text.contains("CLOSING_TEXT");
        assertTrue(hasOpening && hasClosing,
                "Assistant bubble at end-of-turn is missing LLM text.\n"
                        + "  hasOpening=" + hasOpening + " hasClosing=" + hasClosing + "\n"
                        + "--- assistant bubble text ---\n" + text);
    }
}
