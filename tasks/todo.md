# Workflow actors reworked against the POJO-actor anti-pattern documents (2026-09-30)

## Tasks
- [x] `harness` and `queue` are plain objects (`HarnessLeash`, `QueueBridge`) wrapped by `IIActorRef`
      adapters (`HarnessLeashIIAR`, `QueueBridgeIIAR`) that hold only `@Action` plumbing
      (ActorSuffixAndOwnedActorRef_260722_oo01)
- [x] Data facts are action messages, not failures: `check` → YES/NO, `judge` → PASS/FAIL,
      `awaitApproval` → APPROVED/REJECTED/TIMEOUT, `loadChecklist` → item count; the YAML stores
      them with `this.putJson` and gates with `this.onlyIf` (ExitConditionRidingOnAFailure_260914_oo01)
- [x] Catch-alls use `this.print`; no auto-created `out` (LookupThatCreates_260914_oo01)
- [x] All four bundled YAMLs rewritten; the checklist framing turn moved from `start` into doc-check's
      own conditional first step (it was sent in every pipeline, with checklist wording)
- [x] `ClaudeHarnessRunner.runWorkflow` extracted so tests run the bundled YAMLs through the real assembly
- [x] Tests: `BundledWorkflowsTest` (doc-check loop and framing, judge FAIL→PASS and refine budget,
      check-then-act YES/NO, busy stripping, queue.enqueue), `ClaudeHarnessRunnerTest`
- [x] README actions table and template; spec doc `TwoTilesOneRepository_260930_oo01` rewritten
      against AntiPattern_260921_oo01

## Review
- see the commit message

---

# Workflow as a queue item (3.0.0-SNAPSHOT)

## Problem
1. The Queue list above the prompt form shows each queued prompt in full; long prompts make the
   list hard to scan. The tooltip already shows the full text.
2. A Turing Workflow YAML cannot be queued. The Workflow tab is a read-only viewer of three bundled
   YAMLs whose Run button starts them immediately, outside the queue. The user wants to write YAML
   in that tab, put it into the queue, and have it run as a workflow when its turn comes, so that a
   workflow can gate an action on a condition, re-enqueue itself when the condition is not met, or
   loop until a condition holds.

## Design
- Queue stays browser-owned (localStorage). A queue item gains `kind`: `prompt` (default) or
  `workflow` (`{kind:'workflow', text:<title>, yaml, input, auto}`).
- When a workflow item's turn comes the browser POSTs `{yaml,input}` to `POST /api/workflows/run-yaml`,
  marks itself busy, and waits for the SSE `result` event exactly as for a prompt.
- `ClaudeHarnessRunner` runs YAML text (bundled name → text, or text from the browser). It always
  emits one terminal `result(busy=false)` when the run ends, so the browser queue advances; the
  per-action `result` emits in `ClaudeHarnessActor` are removed (single source of truth).
- Input JSON top-level fields are loaded into the workflow `vars`, so `${name}` works in user YAML.
- New workflow actor `queue` (`QueueBridgeActor`) with actions `requeue` (re-enqueue this same
  workflow) and `enqueue` (enqueue a plain prompt). Both emit a new SSE event `queue_add` whose
  content is the item JSON; the browser appends it to its queue. Delay before requeue = the step's
  own `delay:` field.
- Generic harness actions for user YAML: `send` (one instruction turn, SUCCESS) and `check` (one
  turn; SUCCESS iff the reply's first line starts with YES, FAILURE otherwise) — the condition gate.
- Workflow tab: editable YAML textarea (persisted in localStorage); selecting a bundled workflow
  loads it as a template; buttons `Add to queue` and `Run now`.
- A bundled template `check-then-act.yaml` demonstrates check → act / requeue.

## Tasks
- [x] Branch `feat/workflow-as-queue-item`; bump 12 pom.xml to 3.0.0-SNAPSHOT
- [x] (1) styles.css: `.queue-text` 3-line clamp; drop stray `}`; bump `styles.css?v`
- [x] ChatEvent: `queueAdd(itemJson)` type `queue_add` + unit test
- [x] QueueBridgeActor (`queue`: `requeue`, `enqueue`) + unit test
- [x] ClaudeHarnessActor: `send`, `check`; remove per-action `result` emits
- [x] ClaudeHarnessRunner: run from YAML text; vars from input JSON; terminal `result`; register `queue`
- [x] ChatResource: `POST /api/workflows/run-yaml`; add template to WORKFLOWS
- [x] `check-then-act.yaml` template
- [x] console.js/index.html/console.css: editable YAML, Add to queue, Run now
- [x] app.js: workflow queue items (render, send, edit, `queue_add` event); bump `app.js?v`, `console.js?v`
- [x] rm -rf target && mvn install (all tests); E2E if Playwright browsers exist
- [x] README: document the queue-workflow feature and YAML actions

## Review
- Version 3.0.0-SNAPSHOT in all 12 pom.xml; branch `feat/workflow-as-queue-item`.
- (1) `.queue-text` is clamped to 3 lines (`-webkit-line-clamp`), the `title` tooltip keeps the full text.
- (2) Queue items carry `kind`; a `workflow` item holds `yaml` + `input`. `sendFromQueue` posts it to
  `POST /api/workflows/run-yaml`; `ClaudeHarnessRunner` emits exactly one terminal `result` per run, so
  the browser's busy/queue logic is unchanged. Actor `queue` (`requeue`, `enqueue`) emits `queue_add`.
- Engine facts found while testing (turing-workflow 4.2.0): no `${var}` expansion in arguments; use
  `"jexl: state.getString('k')"` (values put with `putJson`). `out.print`/`error` need `{message: ...}`;
  the three bundled YAMLs' catch-alls used bare strings and were silently failing — fixed.
- Tests: core 134 unit tests green (new: QueueBridgeActorTest through a real Interpreter,
  ClaudeHarnessRunnerTest); all modules `mvn install` green; E2E QueueE2E + new WorkflowQueueE2E green.
  Full `-Pe2e` has pre-existing failures: LoginE2E/ProxyLoginScreenE2E/McpMessageDisplayE2E need their
  own profiles; ChatInteractionE2E.cancelButtonEnabled fails on main too; ThemeE2E.persistsAcrossReload
  is flaky (fails alone, passes in sequence; theme code untouched).
- Not done: server-side `ChatActor.busy` is not held during a workflow run (pre-existing: an MCP
  `submitPrompt` during a run would reach the provider concurrently). Jar copied to
  `~/works/quarkus-chat-ui-3.0.0-SNAPSHOT.jar`; the `quarkus-chat-ui.jar` link and running ports untouched.
