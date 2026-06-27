package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.actor.SseActor;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The "leash": a Turing Workflow actor that drives the Claude harness <b>one constrained step at a
 * time</b>. Claude is itself an agent, so this actor does not run a tool loop; instead each
 * {@code @Action} sends exactly one instruction to Claude via the {@link LlmProvider}, waits for the
 * turn to finish ({@code sendPrompt} blocks until the {@code result} event), streams the reply to the
 * browser, and records it to the I/O log. The workflow YAML decides the sequence — e.g. feed a
 * documentation checklist item by item so Claude checks each one instead of skimming the whole list.
 *
 * <p>Contract with the workflow YAML (actor name {@code harness}):
 * {@code start} (init) -> {@code loadChecklist} (read the checklist into a list) ->
 * {@code sendNextItem}* (one item per turn; SUCCESS while items remain, FAILURE when exhausted) ->
 * {@code finish}.</p>
 */
public class ClaudeHarnessActor extends IIActorRef<Object> {

    private static final Logger LOG = Logger.getLogger(ClaudeHarnessActor.class.getName());

    private final LlmProvider provider;
    private final ActorRef<SseActor> sseRef;
    private final IoLogStore ioLog;
    private final ObjectMapper mapper;
    /** Raw run input (JSON): {@code {"path": "<checklist file>", "target": "<optional framing>"}}. */
    private final String runInput;

    // Per-run state
    private String checklistPath;
    private String target;
    private final List<String> items = new ArrayList<>();
    private int cursor = 0;
    private long ioSession = -1;
    private int turn = 0;

    public ClaudeHarnessActor(String name, LlmProvider provider, ActorRef<SseActor> sseRef,
                              IoLogStore ioLog, IIActorSystem system, ObjectMapper mapper, String runInput) {
        super(name, new Object(), system);
        this.provider = provider;
        this.sseRef = sseRef;
        this.ioLog = ioLog;
        this.mapper = mapper;
        this.runInput = runInput;
    }

    /** Initialises the run from the JSON input and (optionally) frames the task with one context turn. */
    @Action("start")
    public ActionResult start(String args) {
        try {
            JsonNode in = (runInput == null || runInput.isBlank())
                    ? mapper.createObjectNode() : mapper.readTree(runInput);
            this.checklistPath = in.path("path").asText("");
            this.target = in.path("target").asText("");
            this.cursor = 0;
            this.turn = 0;
            this.ioSession = (ioLog != null) ? ioLog.ensureSession() : -1;
            emit(ChatEvent.info("▶ Workflow started"
                    + (checklistPath.isBlank() ? "" : " — checklist: " + checklistPath)));
            // An optional framing turn so Claude knows the target before the checklist begins.
            if (!target.isBlank()) {
                runTurn("これからチェックリストを1項目ずつ適用します。対象は次のとおりです。"
                        + "まだ作業や先回りはせず、理解だけして「準備OK」と返してください。\n\n" + target);
            }
            return new ActionResult(true, "started");
        } catch (Exception e) {
            emit(ChatEvent.error("workflow start failed: " + e.getMessage()));
            return new ActionResult(false, "start failed");
        }
    }

    /** Reads the checklist file and splits it into items (markdown horizontal rules as separators). */
    @Action("loadChecklist")
    public ActionResult loadChecklist(String args) {
        if (checklistPath == null || checklistPath.isBlank()) {
            // No checklist: nothing to load, but not an error — the run may be target-only.
            emit(ChatEvent.info("(no checklist path given)"));
            return new ActionResult(true, "no checklist");
        }
        try {
            String text = Files.readString(resolve(checklistPath));
            items.clear();
            for (String block : text.split("(?m)^---\\s*$")) {
                String b = block.strip();
                if (!b.isEmpty()) items.add(b);
            }
            cursor = 0;
            emit(ChatEvent.info("☑ " + items.size() + " checklist item(s) loaded — checking one at a time"));
            return new ActionResult(true, items.size() + " items");
        } catch (Exception e) {
            emit(ChatEvent.error("could not read checklist " + checklistPath + ": " + e.getMessage()));
            return new ActionResult(false, "load failed");
        }
    }

    /**
     * Sends the next checklist item to Claude as one constrained turn. SUCCESS while items remain (the
     * workflow loops back here), FAILURE when the list is exhausted (the workflow falls through to finish).
     */
    @Action("sendNextItem")
    public ActionResult sendNextItem(String args) {
        if (cursor >= items.size()) {
            return new ActionResult(false, "no more items");
        }
        String item = items.get(cursor);
        int n = cursor + 1;
        cursor++;
        emit(ChatEvent.info("— check " + n + " / " + items.size() + " —"));
        String instruction =
                "あなたはチェックリストを1項目ずつ検査しています。今は次の【1項目だけ】を対象に検査し、"
              + "その結果だけを報告してください。それ以外の項目に進んだり、先回りで作業したりしないでください。\n\n"
              + "【チェック項目 " + n + "/" + items.size() + "】\n" + item;
        runTurn(instruction);
        return new ActionResult(true, "sent " + n);
    }

    /** Ends the run. */
    @Action("finish")
    public ActionResult finish(String args) {
        emit(ChatEvent.info("✅ Workflow complete — " + items.size() + " item(s) checked"));
        emit(ChatEvent.result(provider.getSessionId(), 0.0, 0L, provider.getCurrentModel(), false));
        return new ActionResult(true, "finished");
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** Sends one instruction to Claude, streams the reply to the browser, and records the turn. */
    private void runTurn(String instruction) {
        int turnNo = ++turn;
        StringBuilder assistant = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Consumer<ChatEvent> emitter = ev -> {
            if ("delta".equals(ev.type()) && ev.content() != null) {
                assistant.append(ev.content());
            } else if ("thinking".equals(ev.type()) && ev.content() != null) {
                thinking.append(ev.content());
            }
            // Forward everything to the browser so the leashed conversation shows live in the left pane.
            sseRef.tell(a -> a.emit(ev));
        };
        try {
            // Blocks until the turn's result event — this is the leash: the next step waits for Claude.
            provider.sendPrompt(instruction, provider.getCurrentModel(), emitter, ProviderContext.simple(null));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "harness turn failed", e);
            sseRef.tell(a -> a.emit(ChatEvent.error("turn failed: " + e.getMessage())));
        }
        recordTurn(turnNo, instruction, assistant.toString(), thinking.toString());
    }

    /** Records one turn into the H2 I/O log in the marker format the Sessions tab reads. */
    private void recordTurn(int turnNo, String prompt, String assistant, String thinkingText) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String requestJson = new org.json.JSONObject()
                    .put("messages", new org.json.JSONArray().put(
                            new org.json.JSONObject().put("role", "user").put("content", prompt)))
                    .toString();
            StringBuilder m = new StringBuilder();
            m.append("REQUEST:\n").append(requestJson);
            m.append("\n\nRESPONSE:\n").append(assistant == null ? "" : assistant);
            if (thinkingText != null && !thinkingText.isBlank()) {
                m.append("\n\nREASONING:\n").append(thinkingText);
            }
            m.append("\n\nUSAGE: promptTokens=0 completionTokens=0");
            ioLog.record(ioSession, "harness", "turn" + turnNo + "/step1/llm", m.toString());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "harness I/O log record failed", e);
        }
    }

    private void emit(ChatEvent ev) {
        sseRef.tell(a -> a.emit(ev));
    }

    /** Resolves a user-given path; {@code ~} and {@code $HOME} expand to the home directory. */
    private static Path resolve(String p) {
        String s = p.trim();
        String home = System.getProperty("user.home");
        if (s.startsWith("~/")) s = home + s.substring(1);
        else if (s.startsWith("$HOME/")) s = home + s.substring(5);
        return Path.of(s);
    }
}
