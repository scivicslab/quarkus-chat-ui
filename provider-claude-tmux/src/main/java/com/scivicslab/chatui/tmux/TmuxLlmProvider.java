package com.scivicslab.chatui.tmux;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link LlmProvider} that drives the Claude Code interactive TUI inside a tmux
 * session and extracts content from the rendered screen (the case-B design).
 *
 * <p>One instance owns one long-lived tmux session (one conversation), matching
 * the "one chat-ui instance = one provider" model. {@code sendPrompt} types the
 * prompt with {@code send-keys}, drives the {@link OutputWatcher} until the byte
 * stream goes idle, and streams the extracted content as {@link ChatEvent}s.
 *
 * <p>Approval handling uses "turn returns" (method 2): when a numbered dialog is
 * detected, a {@code prompt} event is emitted and {@code sendPrompt} returns,
 * recording the pending approval. The continuation after the answer is wired as a
 * fresh turn in a later step (see spec {@code TmuxTuiDriver_260630_oo01}); here
 * {@link #respond} delivers the keypress to the live TUI.
 *
 * <p>The blocking-loop behaviour requires a live Claude Code TUI and is verified
 * by integration tests against real claude, not unit tests. The pure helpers
 * ({@link #toPromptEvent}, {@link #mapResponseToChoice}) are unit tested.
 */
public final class TmuxLlmProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(TmuxLlmProvider.class.getName());

    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(
            /* interactivePrompts */ true,
            /* sessionRestore     */ true,
            /* watchdog           */ true,
            /* images             */ false,
            /* urlFetch           */ false,
            /* slashCommands      */ false); // slash commands (/compact, /clear) are typed into the TUI

    private static final int PANE_WIDTH = 200;
    private static final int PANE_HEIGHT = 50;
    private static final long IDLE_MILLIS = 600;
    private static final long CHECK_INTERVAL_MILLIS = 100;
    private static final long READY_TIMEOUT_MILLIS = 20_000;
    private static final long MAX_TURN_MILLIS = 600_000;

    private final String sessionId;
    private final String program;
    private final Map<String, ApprovalRequested> pendingApprovals = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile String model;
    private boolean started;
    private ActorSystem system;
    private TmuxSession tmux;
    private ActorRef<OutputWatcher> watcherRef;
    private PipePaneReader reader;

    /**
     * @param sessionId    tmux session name (unique on the host)
     * @param program      command line that launches the TUI (for example {@code "claude"})
     * @param defaultModel initial model alias (for example {@code "sonnet"})
     */
    public TmuxLlmProvider(String sessionId, String program, String defaultModel) {
        this.sessionId = sessionId;
        this.program = program;
        this.model = defaultModel;
    }

    @Override
    public String id() {
        return "claude-tmux";
    }

    @Override
    public String displayName() {
        return "Claude (tmux)";
    }

    @Override
    public List<ModelEntry> getAvailableModels() {
        return List.of(
                new ModelEntry("sonnet", "claude", "tmux"),
                new ModelEntry("opus", "claude", "tmux"),
                new ModelEntry("haiku", "claude", "tmux"));
    }

    @Override
    public String getCurrentModel() {
        return model;
    }

    @Override
    public void setModel(String model) {
        this.model = model;
        // Switching the model inside a live TUI requires typing "/model <name>";
        // wired in a later step. Storing it is enough for new sessions.
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean supportsInteractivePrompts() {
        return true;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        try {
            ensureStarted();
            if (model != null && !model.equals(this.model)) {
                setModel(model);
            }
            cancelled.set(false);
            long start = System.currentTimeMillis();
            tmux.sendLine(prompt);

            long deadline = start + MAX_TURN_MILLIS;
            while (!cancelled.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(CHECK_INTERVAL_MILLIS);
                TickResult tick = watcherRef.ask(w -> w.tick(System.currentTimeMillis())).join();
                if (!tick.settled()) {
                    continue;
                }
                boolean awaitingApproval = false;
                for (ExtractedEvent event : tick.events()) {
                    if (event instanceof AssistantMessage am) {
                        emitter.accept(ChatEvent.delta(am.text() + "\n"));
                        ctx.onActivity().run();
                    } else if (event instanceof ToolResult tr) {
                        emitter.accept(ChatEvent.delta("⎿ " + tr.text() + "\n"));
                        ctx.onActivity().run();
                    } else if (event instanceof ApprovalRequested ar) {
                        String promptId = UUID.randomUUID().toString();
                        pendingApprovals.put(promptId, ar);
                        emitter.accept(toPromptEvent(promptId, ar));
                        ctx.onActivity().run();
                        awaitingApproval = true;
                    }
                }
                if (awaitingApproval) {
                    return; // method 2: end the turn; the answer resumes via a fresh turn
                }
                // Only end the turn when the TUI is actually back at the idle input prompt.
                // A settle that is not input-ready means Claude is still working — a tool is
                // running or a permission dialog is rendering — so keep waiting; the next
                // settle will carry the dialog (or the final answer).
                if (ScreenExtractor.isInputReady(tick.capture())) {
                    emitter.accept(ChatEvent.result(sessionId, 0.0,
                            System.currentTimeMillis() - start, this.model, false));
                    return;
                }
            }
            if (cancelled.get()) {
                emitter.accept(ChatEvent.info("Cancelled"));
            } else {
                emitter.accept(ChatEvent.result(sessionId, 0.0,
                        System.currentTimeMillis() - start, this.model, false));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.accept(ChatEvent.error("claude-tmux interrupted"));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "claude-tmux sendPrompt failed", e);
            emitter.accept(ChatEvent.error("claude-tmux error: " + e.getMessage()));
        }
    }

    /**
     * Method 2: an approval answer resumes via a fresh turn. The choice string is returned
     * so the REST layer enqueues it as a continuation prompt; {@code sendPrompt} then types
     * it at the on-screen dialog and streams what follows. This is the primary answer path
     * (not the mid-turn {@link #respond}).
     */
    @Override
    public String resolveApprovalToContinuation(String promptId, String response) {
        ApprovalRequested ar = pendingApprovals.remove(promptId);
        return mapResponseToChoice(response, ar);
    }

    @Override
    public void respond(String promptId, String response) throws IOException {
        // Fallback only; the normal path is resolveApprovalToContinuation (a fresh turn).
        ApprovalRequested ar = pendingApprovals.remove(promptId);
        String choice = mapResponseToChoice(response, ar);
        try {
            tmux.sendText(choice);
            tmux.sendEnter();
        } catch (RuntimeException e) {
            throw new IOException("failed to deliver approval response to TUI", e);
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        if (started) {
            try {
                tmux.sendKey("Escape");
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Escape send during cancel failed", e);
            }
        }
    }

    // ---- pure helpers (unit tested) ----

    /**
     * Builds the SSE {@code prompt} event for a detected approval dialog.
     *
     * @param promptId the id the client uses to answer
     * @param ar       the detected dialog
     * @return a prompt {@link ChatEvent} carrying the question and the option labels
     */
    static ChatEvent toPromptEvent(String promptId, ApprovalRequested ar) {
        return ChatEvent.prompt(promptId, ar.prompt(), "tool_permission", ar.options());
    }

    /**
     * Maps a free-text or numeric response to the key(s) to type at a numbered
     * dialog. A bare number is used as-is; affirmative words select option 1,
     * negative words select option 2.
     *
     * @param response the user's answer
     * @param ar       the dialog being answered (may be {@code null} if unknown)
     * @return the choice string to type before Enter
     */
    static String mapResponseToChoice(String response, ApprovalRequested ar) {
        String r = response == null ? "" : response.trim().toLowerCase();
        if (r.matches("\\d+")) {
            return r;
        }
        if (r.startsWith("y") || r.startsWith("approve") || r.startsWith("trust") || r.startsWith("allow")) {
            return "1";
        }
        if (r.startsWith("n") || r.startsWith("deny") || r.startsWith("reject")) {
            return "2";
        }
        return "1";
    }

    // ---- lifecycle ----

    private synchronized void ensureStarted() {
        if (started) {
            return;
        }
        system = new ActorSystem("claude-tmux-provider");
        tmux = new TmuxSession(sessionId, PANE_WIDTH, PANE_HEIGHT);
        tmux.createRunning(program);
        waitForReady();
        OutputWatcher watcher = new OutputWatcher(
                tmux::captureAll, new ScreenExtractor(), new IdleDebouncer(IDLE_MILLIS));
        watcherRef = system.actorOf(sessionId + "-watcher", watcher);
        reader = new PipePaneReader(sessionId,
                () -> watcherRef.tell(w -> w.recordActivity(System.currentTimeMillis())));
        try {
            reader.start();
        } catch (IOException e) {
            throw new TmuxException("failed to start pipe-pane reader", e);
        }
        started = true;
    }

    /**
     * Waits for the TUI to reach an input-ready state, accepting the workspace
     * trust dialog if it appears on first launch.
     */
    private void waitForReady() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            String capture = tmux.captureAll();
            if (ScreenExtractor.detectApproval(capture).isPresent()) {
                // Trust dialog (or similar) — accept it to proceed.
                tmux.sendText("1");
                tmux.sendEnter();
            } else if (ScreenExtractor.isInputReady(capture)) {
                return; // booted and waiting for input — no need to wait out the timeout
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
