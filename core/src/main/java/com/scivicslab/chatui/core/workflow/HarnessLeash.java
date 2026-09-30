package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The leash: drives the LLM provider one constrained turn at a time. Each public method sends at
 * most one instruction through {@link LlmProvider#sendPrompt}, waits for the turn's result, forwards
 * the turn's events to the browser, records the turn in the I/O log, and returns what the workflow
 * needs to decide the next step (the reply text, a verdict, an item count).
 *
 * <p>This is a plain object. {@link HarnessLeashIIAR} wraps it as the workflow actor {@code harness}
 * and turns these return values and exceptions into action results. A method here throws when it
 * could not do what was asked (no registry, a provider failure, an index out of range); a fact
 * about the data (the answer was NO, the verdict was FAIL) is a return value, never an exception,
 * so the workflow decides on it with {@code onlyIf}.</p>
 */
public class HarnessLeash {

    private static final Logger LOG = Logger.getLogger(HarnessLeash.class.getName());

    private final LlmProvider provider;
    /** Where the browser-bound events go (the SSE actor, or a list in tests). */
    private final Consumer<ChatEvent> emitter;
    private final IoLogStore ioLog;
    private final ObjectMapper mapper;
    /** Raw run input (JSON): {@code {"path": "<checklist file>", "target": "<task>", "maxRefines": n}}. */
    private final String runInput;
    /** Registry used by {@link #awaitApproval} to block for an external approval; may be null. */
    private final WorkflowApprovalRegistry approvalRegistry;

    // Per-run state
    private String checklistPath = "";
    private String target = "";
    private final List<String> items = new ArrayList<>();
    private long ioSession = -1;
    private int turn = 0;

    // Explain -> judge -> implement pipeline state
    private String plan = "";
    private String judgeFeedback = "";
    private int refineCount = 0;
    private int maxRefines = 2;

    public HarnessLeash(LlmProvider provider, Consumer<ChatEvent> emitter, IoLogStore ioLog,
                        ObjectMapper mapper, String runInput, WorkflowApprovalRegistry approvalRegistry) {
        this.provider = provider;
        this.emitter = emitter;
        this.ioLog = ioLog;
        this.mapper = mapper;
        this.runInput = runInput;
        this.approvalRegistry = approvalRegistry;
    }

    /** Reads the run input and opens the I/O-log session. No turn is sent. */
    public void start() throws IOException {
        JsonNode in = (runInput == null || runInput.isBlank())
                ? mapper.createObjectNode() : mapper.readTree(runInput);
        this.checklistPath = in.path("path").asText("");
        this.target = in.path("target").asText("");
        this.turn = 0;
        this.maxRefines = in.path("maxRefines").asInt(2);
        this.refineCount = 0;
        this.plan = "";
        this.judgeFeedback = "";
        this.items.clear();
        this.ioSession = (ioLog != null) ? ioLog.ensureSession() : -1;
        emit(ChatEvent.info("▶ Workflow started"
                + (checklistPath.isBlank() ? "" : " — checklist: " + checklistPath)));
    }

    /**
     * One framing turn before a checklist run: tells the LLM what the checklist will be applied to
     * and asks it to only acknowledge.
     *
     * @return the reply
     * @throws IllegalStateException when the run input gave no target
     */
    public String frame() {
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("no target to frame");
        }
        return runTurn("これからチェックリストを1項目ずつ適用します。対象は次のとおりです。"
                + "まだ作業や先回りはせず、理解だけして「準備OK」と返してください。\n\n" + target);
    }

    /**
     * Reads the checklist file and splits it into items (markdown horizontal rules as separators).
     *
     * @return the number of items; 0 when the run input names no checklist
     */
    public int loadChecklist() throws IOException {
        items.clear();
        if (checklistPath == null || checklistPath.isBlank()) {
            emit(ChatEvent.info("(no checklist path given)"));
            return 0;
        }
        String text = Files.readString(resolve(checklistPath));
        for (String block : text.split("(?m)^---\\s*$")) {
            String b = block.strip();
            if (!b.isEmpty()) items.add(b);
        }
        emit(ChatEvent.info("☑ " + items.size() + " checklist item(s) loaded — checking one at a time"));
        return items.size();
    }

    /** @return the number of checklist items loaded by {@link #loadChecklist} */
    public int itemCount() {
        return items.size();
    }

    /**
     * Sends one checklist item as one constrained turn.
     *
     * @param index 0-based item index
     * @return the reply
     * @throws IndexOutOfBoundsException when no item has that index
     */
    public String sendItem(int index) {
        String item = items.get(index);
        int n = index + 1;
        emit(ChatEvent.info("— check " + n + " / " + items.size() + " —"));
        String instruction =
                "あなたはチェックリストを1項目ずつ検査しています。今は次の【1項目だけ】を対象に検査し、"
              + "その結果だけを報告してください。それ以外の項目に進んだり、先回りで作業したりしないでください。\n\n"
              + "【チェック項目 " + n + "/" + items.size() + "】\n" + item;
        return runTurn(instruction);
    }

    /** Tells the browser the checklist run is complete. */
    public void finish() {
        emit(ChatEvent.info("✅ Workflow complete — " + items.size() + " item(s) checked"));
    }

    // ── explain -> judge -> implement pipeline ───────────────────────────────

    /**
     * Asks for a full explanation of the intended approach, forbidding implementation. The reply
     * becomes the current plan; on a re-run the judge's feedback is folded in.
     *
     * @return the plan
     */
    public String explain() {
        String feedbackBlock = judgeFeedback.isBlank() ? ""
                : "\n\n【前回の審査での指摘（これを踏まえて説明し直すこと）】\n" + judgeFeedback;
        String instruction =
                "次の課題について、これから取る方針を徹底的に説明してください。"
              + "**実装は絶対にしないでください（コードやファイルを一切変更しない）。**"
              + "前提・全体像・手順・判断の根拠を、読み手が可否を判断できる完全な文で書いてください。\n\n"
              + "【課題】\n" + target + feedbackBlock;
        emit(ChatEvent.info(refineCount == 0
                ? "① 方針を説明中…" : "① 方針を再説明中（refine " + refineCount + "/" + maxRefines + "）…"));
        this.plan = runTurn(instruction);
        return plan;
    }

    /**
     * Judges the current plan with an independent turn.
     *
     * @return {@code "PASS"} or {@code "FAIL"}; {@code "PASS"} without a turn once the refine
     *         budget is used up, so the pipeline cannot loop forever
     */
    public String judge() {
        if (refineCount >= maxRefines) {
            emit(ChatEvent.info("② 判定：再説明の上限に達したため、この説明で先へ進みます"));
            return "PASS";
        }
        String instruction =
                "あなたは独立した審査員です。以下は課題と、それに対する提案説明です。"
              + "この説明が「実装に進んでよいだけ十分に明確・完全か」を厳しく判定してください。"
              + "1行目に必ず PASS か FAIL だけを書き、2行目以降に理由（FAIL の場合は不足している点を具体的に）"
              + "を書いてください。あなた自身は実装や新しい方針を書かないこと。\n\n"
              + "【課題】\n" + target + "\n\n【提案説明】\n" + plan;
        emit(ChatEvent.info("② 説明の十分性を判定中…"));
        String verdict = runTurn(instruction);
        if (firstLineStartsWith(verdict, "PASS")) {
            emit(ChatEvent.info("② 判定：PASS → 実装へ"));
            return "PASS";
        }
        this.judgeFeedback = verdict == null ? "" : verdict.strip();
        emit(ChatEvent.info("② 判定：FAIL → 再説明へ"));
        return "FAIL";
    }

    /** Counts one refine round (the judge's feedback is already held). @return the new count */
    public int refine() {
        return ++refineCount;
    }

    /** Tells the LLM the plan is approved and to implement it now. @return the reply */
    public String implement() {
        emit(ChatEvent.info("③ 承認された方針を実装します"));
        String reply = runTurn(
                "先ほど説明した方針が承認されました。**今からその方針を実装してください。**"
              + "承認された方針は次の通りです：\n\n" + plan);
        emit(ChatEvent.info("✅ Workflow complete"));
        return reply;
    }

    /**
     * Human gate: shows the plan and blocks until an external approver answers via
     * {@code POST /api/respond}, or 30 minutes pass.
     *
     * @return {@code "APPROVED"}, {@code "REJECTED"} or {@code "TIMEOUT"}
     * @throws IllegalStateException when no approval registry was given
     * @throws InterruptedException when the wait is interrupted
     */
    public String awaitApproval() throws InterruptedException {
        if (approvalRegistry == null) {
            throw new IllegalStateException("no approval registry");
        }
        String promptId = UUID.randomUUID().toString();
        CompletableFuture<WorkflowApprovalRegistry.Decision> future = new CompletableFuture<>();
        approvalRegistry.register(promptId, future);
        emit(ChatEvent.prompt(promptId,
                "この方針で実装してよいですか？\n\n" + plan,
                "workflow_approval",
                List.of("承認して実装", "却下（やり直し）")));
        emit(ChatEvent.info("② 承認待ち…（「承認して実装」または「却下」を押してください）"));
        try {
            WorkflowApprovalRegistry.Decision decision = future.get(30, TimeUnit.MINUTES);
            if (decision.approved()) {
                emit(ChatEvent.info("② 承認 → 実装へ"));
                return "APPROVED";
            }
            emit(ChatEvent.info("② 却下されました — 中止します"));
            return "REJECTED";
        } catch (TimeoutException e) {
            approvalRegistry.remove(promptId);
            emit(ChatEvent.error("承認待ちがタイムアウトしました"));
            return "TIMEOUT";
        } catch (java.util.concurrent.ExecutionException e) {
            approvalRegistry.remove(promptId);
            throw new IllegalStateException("approval failed: " + e.getMessage(), e);
        }
    }

    // ── generic turns for user-written workflow YAML ─────────────────────────

    /**
     * Sends one instruction turn.
     *
     * @return the reply text
     * @throws IllegalArgumentException when the instruction is blank
     */
    public String send(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("empty instruction");
        }
        return runTurn(instruction);
    }

    /**
     * Sends one question that must be answered YES or NO on the first line.
     *
     * @return {@code "YES"} when the reply's first line starts with YES, else {@code "NO"}
     * @throws IllegalArgumentException when the question is blank
     */
    public String check(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("empty question");
        }
        String reply = runTurn(question
                + "\n\nAnswer on the first line with exactly YES or NO, then give the reason.");
        boolean yes = firstLineStartsWith(reply, "YES");
        emit(ChatEvent.info(yes ? "✔ check: YES" : "✘ check: NO"));
        return yes ? "YES" : "NO";
    }

    /** True when the first non-empty line of the text, ignoring markdown emphasis, starts with the word. */
    static boolean firstLineStartsWith(String text, String word) {
        if (text == null) return false;
        for (String line : text.split("\\R")) {
            String t = line.replaceAll("[*_`#>\\s]", "").toUpperCase();
            if (t.isEmpty()) continue;
            return t.startsWith(word);
        }
        return false;
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * Sends one instruction, forwards the turn's events to the browser, records the turn, and
     * returns the reply text.
     *
     * @throws IllegalStateException when the provider failed the turn
     */
    private String runTurn(String instruction) {
        int turnNo = ++turn;
        StringBuilder assistant = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Consumer<ChatEvent> turnEmitter = ev -> {
            if ("delta".equals(ev.type()) && ev.content() != null) {
                assistant.append(ev.content());
            } else if ("thinking".equals(ev.type()) && ev.content() != null) {
                thinking.append(ev.content());
            }
            // The browser shows the turn live. The turn's own busy=false is dropped: the run is
            // still going, and only the runner's terminal result may end the browser's busy state.
            emit("result".equals(ev.type()) ? ev.withoutBusy() : ev);
        };
        try {
            // Blocks until the turn's result event: the next workflow step waits for the reply.
            provider.sendPrompt(instruction, provider.getCurrentModel(), turnEmitter, ProviderContext.simple(null));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "harness turn failed", e);
            emit(ChatEvent.error("turn failed: " + e.getMessage()));
            throw new IllegalStateException("turn failed: " + e.getMessage(), e);
        }
        recordTurn(turnNo, instruction, assistant.toString(), thinking.toString());
        return assistant.toString();
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
        emitter.accept(ev);
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
