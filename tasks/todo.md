# Action descriptions beside each Workflow-tab step (ActionCatalogWithJavadoc_260930_oo01), 2026-10-02

## Tasks
- [x] Turing-workflow (4.3.0-SNAPSHOT, ee5bdda): ActionManifest reads META-INF/turing-plugin.json; ActionCatalog
      merges description + per-field @param prose into the schema; static describe(Class, action) and
      actionNamesOf(Class); doclet reads the argsType record's @param; the core jar carries its own manifest
- [x] chat-ui: argsType records with Javadoc on harness.send/check/sendNextItem, queue.enqueue, agent.runTools;
      YAML arguments in map form; core and plugin poms generate action-schemas/ (process-classes, absolute paths)
      and META-INF/turing-plugin.json (doclet at process-classes so tests see it)
- [x] REST GET /api/workflows/actions/{actor} and /{actor}/{action} over the static actor-to-class table
      (ClaudeHarnessRunner.ACTOR_CLASSES, INTERPRETER_ACTIONS for `this`)
- [x] Workflow tab: actions panel above the editor; per step actor.method, the Javadoc sentence, fields with
      type/required/description, and a problem line for an unknown action or a missing required key
- [x] Tests: ActionDescriptionTest (core, over the built artefacts), WorkflowActionsPanelE2E; all E2E green
- [x] AI workspace: the 3.x tile now dependsOn turing-workflow (09bcea7); jar placed, 28000 not restarted
- [ ] push: Turing-workflow main, quarkus-chat-ui branch, quarkus-AI-workspace main; restart 28000 and 28020

## Review
- The root pom's dependencyManagement hard-coded turing-workflow 4.2.0 beside the property; now `${turing-workflow.version}`.
- The schema generator needs absolute paths in a multi-module build (its defaults are relative to the reactor root).
- The doclet must run before `test`, or the manifest is absent from target/classes during unit tests.

---

# Agent Loop tab and per-turn Turing Workflow for the openai-compat provider (2026-09-30)

Spec: doc_SCIVICS002 quarkus-chat-ui/020_specs/110_AgentLoopTab_260930_oo01 (confirmed 2026-10-01, implemented).

## Plan
- [x] core: `TurnRunner` and `QueueSink` interfaces + `QueueItem` record; `HarnessLeash` takes a `TurnRunner`,
      `QueueBridge` a `QueueSink`; the two quarkus-chat-ui implementations live in `ClaudeHarnessRunner`
      (provider.sendPrompt with busy stripped; SSE queue_add). YAML, IIARs and BundledWorkflowsTest unchanged
      (the test's ScriptedProvider becomes a scripted TurnRunner). This is what the later port to
      chat-ui-with-audit-trail swaps (server-side PromptQueue; ChatSessionIIAR.sendPrompt/getResult)
- [x] `plugin-openai-compat-agent`: `AgentTurn` (POJO) + `AgentTurnIIAR` (actions start/step/runTools/finish/stepCount),
      `LlmCall` and `ToolCaller` interfaces with `OpenAiCompatClient` / MCP-HTTP implementations,
      `TextToolCallParser` (ported from chat-ui-with-audit-trail), `AgentLoopWorkflowRunner`,
      `AgentLoopExtensionImpl` (@ApplicationScoped, config chat-ui.agent-loop.*), bundled `agent-loop-react.yaml`
- [x] `LlmProviderProducer`: inject `Instance<AgentLoopExtension>`, pass it for openai-compat only
- [x] REST `AgentLoopResource`: GET /api/agent-loop, GET /workflows, GET /workflows/{name}, POST /workflow
- [x] UI: 5th right-pane tab "Agent Loop" (select + Refresh + Use, read-only step boxes; note when not openai-compat)
- [x] Unit tests: Interpreter-driven loop over scripted LlmCall/ToolCaller (tool then answer, step limit, cancel),
      TextToolCallParserTest; E2E: tab renders in the e2e profile (openai-compat, no server needed for listing)
- [x] rm -rf target && mvn install; live check against gpu-broker 28005 with the app's own /mcp fs tools
- [x] README section; jar to ~/works under a new name + relink; commit; ask before push

## Review
- Live check on a disposable instance (port 28990, gpu-broker 28005, model qwen3.8-flash-next, own /mcp):
  "list the files under quarkus-chat-ui" -> the model wrote <invoke name="list_directory">, the tool ran,
  the answer came as one delta + one result(busy=false); the I/O log trace shows turn 1 with a tool step
  and an llm step. An outer check-then-act YAML posted to run-yaml: harness.check ran the inner loop
  (get_file_info -> YES), onlyIf took the act transition, harness.send answered DONE; the two inner
  results reached the browser with busy=null and only the runner's terminal result had busy=false.
- Unit: core 134 + plugin 8 tests; all modules `mvn install` green; E2E AgentLoopTabE2E, WorkflowQueueE2E,
  QueueE2E green. Jar placed under a new name and quarkus-chat-ui-3.jar relinked; running 28020 untouched.
- The I/O log turn number is now shared through IoLogStore.beginTurn()/currentTurn(), so ChatActor's
  turnN/step1/llm and the loop's turnN/stepM/tool carry the same N.

## Out of scope
- Claude / Codex / claude-tmux providers (their loop is inside the CLI); the tab only shows a note there
- Per-conversation loop selection (quarkus-chat-ui has one conversation); prompt-construction sub-workflow

---

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

---

# Actions tab: an actor's actions, and one action's description

## Plan
The action catalog that `f7ad469` exposed is useful, but the place it was shown is wrong: it put a
panel inside the Workflow tab, beside the YAML editor, which rewrote a tab whose job is editing and
queueing a workflow. Put the catalog in its own tab instead — type an actor name, get its actions;
name an action, get its description — and give the Workflow tab back the shape it had at `8ea372c`.

## Tasks
- [x] index.html: drop `#wf-actions` from the Workflow tab; add the `Actions` tab button and panel
- [x] console.js: remove `wfRenderActions` / `wfExtractActions` / `wfDescribe` / `wfDescribeCache`
      and the input listener that called them; restore the `wfRefreshParams` debounce
- [x] console.js: `actLoadActorNames`, `actListActions`, `actShowAction`, `actShow`, `initActions`
- [x] console.css: rename the `.wf-action*` rules to `.act-*`; the list fills the panel
- [x] ChatResource: `GET /api/workflows/actions` — the actor names, for the `actor` field's datalist
- [x] Replace the JUnit `WorkflowActionsPanelE2E` with `ActionsTabE2E` (`main()`, outside the build)
- [x] rm -rf target && mvn install
- [x] Drive the tab in a headless browser against a freshly built jar
- [x] README: the Actions tab and its three endpoints

## Review
- The Workflow tab is back to its `8ea372c` content: no actions panel, the YAML editor and the
  queue buttons only.
- The Actions tab asks three endpoints, none of which needs a running workflow: `GET
  /api/workflows/actions` (new here, the actor names), and the two `f7ad469` already had for an
  actor's actions and one action's description.
- Typing an actor and pressing Show lists its actions as buttons; clicking one fills the `action`
  field and shows the description; clearing that field lists the actions again. An unknown actor or
  action is a problem line, not an empty panel.
- Verified in a headless browser against the built jar on a scratch port: `harness` lists its 12
  actions, `send` shows "Sends one instruction to the LLM as one turn; the message is the reply."
  with the field `instruction (string, required) — the text sent to the LLM as this turn's prompt`,
  an unknown action is reported, the Workflow tab holds no actions panel and still has `#wf-yaml`,
  and the browser logged no unexpected error.
- The running instances on 28020 and 28021 and the `~/works/quarkus-chat-ui-3.jar` link are
  untouched; deploying this build is a separate decision.

---

# Actions tab: the list carries each description; one action shows its argument and a YAML example

## Plan
The list of an actor's actions shows names only, and a raw-String action such as `harness.explain`
answers "takes a raw String; its shape is not declared", which tells the reader nothing about what to
write. The Javadoc already knows more than the first sentence: the method's own `@param` line says
what the String is (or that it is ignored), and a `<pre>` block in the Javadoc is the usage example
the author wrote. The doclet keeps only the first sentence and, for records, the record's `@param`s.

Carry the rest through: the doclet writes `details` (the body after the first sentence) and
`example` (the first `<pre>` block) into `turing-plugin.json`; `ActionCatalog.describe` returns them
and, for a raw-String action, the method's `@param` as `argument`; the chat-ui composes a YAML step
(`actor:`/`method:`/`arguments:`) from the schema when the Javadoc has no example; the list endpoint
returns `{name, description}` pairs; the tab shows all of it.

## Tasks
- [x] Turing-workflow `TuringPluginDoclet`: `details`, `example`; Javadoc inline tags converted to text
      instead of brace-stripped
- [x] Turing-workflow `ActionManifest.ActionDoc` + `ActionCatalog.describe`: `details`, `example`, `argument`
- [x] Turing-workflow tests: `test-turing-plugin.json` and `ActionCatalogTest` cover the three
- [x] Turing-workflow: rm -rf target && mvn install (4.3.0-SNAPSHOT)
- [x] chat-ui `HarnessLeashIIAR`/`QueueBridgeIIAR`: `@param args` on every raw-String action, one `<pre>`
      YAML example per action
- [x] chat-ui `ChatResource`: list returns `{name, description}`; describe adds `yaml`
- [x] chat-ui `console.js`/`console.css`: list rows with descriptions; action view with argument, details,
      example
- [x] chat-ui `ActionDescriptionTest`: `explain` argument, `send` example, yaml composition
- [x] chat-ui README Actions tab section
- [x] rm -rf target && mvn install; headless check against the built jar

## Review
- Turing-workflow `218b473`: the doclet writes `details`, `example` and the String action's own
  `@param`; inline tags become text (the old brace-stripping would have eaten `{key: value}` from a
  YAML example). A new `TuringPluginDocletTest` runs the javadoc tool on a source file and checks the
  JSON; `ActionCatalogTest` checks `argument`, `details`, `example`. 363 tests green; 4.3.0-SNAPSHOT
  installed to `~/.m2`.
- chat-ui: every harness and queue action's Javadoc now has `@param args ignored` (or the record's
  `@param`s), a body saying where its input comes from and what to do with its message, and a
  `<pre>{@code ...}</pre>` block holding the step as the bundled YAMLs write it. The manifest shows all
  14 actions with an example.
- `GET /api/workflows/actions/{actor}` answers `{name, description}` per action; `/{actor}/{action}`
  adds `details`, `example`, `argument` (raw-String actions) and `yaml`; `ActionStepYaml` composes the
  step from the schema when the Javadoc has no example (`out.print` → `arguments: {message: "<string>"}`).
- Headless browser against the built jar on a scratch port: the harness list shows 12 actions each with
  its sentence; `explain` shows `args (string) — ignored`, the details and the two-line step; `check`
  shows the field and the 9-line example with its braces and jexl intact; `out.print` shows the
  composed step with the "(composed from the declaration)" label; no browser errors.
- The `this`/`interpreter` actions had no description because `InterpreterIIAR` answered them from an
  if-chain and `IIActorRef` answered the JSON State ones from a switch, neither an `@Action` method.
  Turing-workflow `5bad4c4` makes each one an `@Action` method with Javadoc and a `<pre>` step, and
  `ActionManifest.docFor` walks the hierarchy (putJson is declared on `IIActorRef`); the hand-written
  `INTERPRETER_ACTIONS` list is gone. Converting also fixed `sleep`/`readYaml`/`print`, which read the
  one-element JSON array a bare `arguments:` string becomes as literal text.
- The uber-jar kept only one `META-INF/turing-plugin.json` (core's), so at runtime the engine's and the
  plugin's actions had no prose even before this work, while unit tests on separate jars saw them all.
  Turing-workflow `1ae8a57`: each jar writes `META-INF/turing-plugin/<groupId>.<artifactId>.json` and
  one line in a `META-INF/services/...ActionManifestSource` index, which the uber-jar concatenates; the
  built jar now holds three manifests and a three-line index.
- Verified on the built jar: `this` lists 17 actions, all described; `harness` 18 (12 own + the 6 JSON
  State ones it inherits); `this.putJson` shows its argument shape and the `judge.verdict` step;
  `onlyIf` its jexl example. `out` (6 of 9) and `calc` (11 of 22) are described only where
  turing-workflow's own Javadoc has a first sentence.

---

# Actions tab: the actors above, the chosen actor's actions below; no hand-written actor table

## Plan
The tab named its actors from `ClaudeHarnessRunner.ACTOR_CLASSES`, a table written beside the
registration code, so an actor a plugin registers (`agent`) never appeared, and the actor field asked
the reader to know the name. Actors are not a fixed set. Answer the list from the code that registers
them, add the actors alive in the application, and show them as a list to pick from.

## Tasks
- [x] `WorkflowActorSource` SPI; `ClaudeHarnessRunner` and the agent plugin's `AgentLoopExtensionImpl`
      implement it by running their own `registerRunActors` on a throwaway actor system
- [x] `WorkflowActorCatalog`: every source plus the live `ChatUiActorSystem` actors, keyed by origin + name
      (`queue` is both the workflow's `QueueBridgeIIAR` and the application's `QueueActor`)
- [x] `GET /api/actions`, `/api/actions/list?origin&actor`, `/api/actions/describe?origin&actor&action`
      replace `/api/workflows/actions/*`; `ACTOR_CLASSES` is gone
- [x] Upper pane with origin headings and a filter; lower pane with the actions, an action's description
      and a back button; draggable line; Clear
- [x] README; tests; built jar driven in a headless browser

## Review
- 25 rows: 11 from the workflow run (`this` listed beside `interpreter`, plus the engine's `out`/`calc`/
  `list`/`str`), 6 from the agent loop including `agent` (11 actions, all described), 8 live application
  actors. `harness` selected lists 18 actions all described; `check` opens with its sentence and step;
  back, filter, Clear and the drag all work; no browser errors.
- The same two-pane tab, from one script template, went into chat-ui-with-audit-trail (`21f06f0` there).
- Added after review: a run in progress is listed from its actor system itself. `ClaudeHarnessRunner`
  and the agent plugin's `AgentLoopRun` tell `WorkflowActorCatalog` when their system comes and goes;
  its actors appear under `running:<title> #n` whoever registered them, and go when the run ends.
  Verified with a 20-second `this.sleep` workflow: 7 rows while it ran, 0 afterwards.
