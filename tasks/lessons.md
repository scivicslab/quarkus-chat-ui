# Lessons

## 2026-09-30 Verify engine syntax against the pinned version's sources, not the skill
- Wrote workflow YAML with `${var}` and bare-string `out.error` from the turing-workflow SKILL.md; the
  pinned engine (4.2.0) supports neither. A unit test that runs the YAML through a real `Interpreter`
  caught it; reading the sources jar (`Interpreter.convertArgumentsToJson`, `WorkflowExpressions`) gave
  the real syntax (`jexl: state.getString('k')`, `{message: ...}`).
- Rule: before writing YAML for an engine, open the sources jar of the version in `pom.xml` and run the
  YAML in a unit test. Also check the existing bundled YAMLs for the same defect and fix them together.

## 2026-09-30 Never overwrite a jar a process may be running; never chain the check and the copy
- I ran `ps | grep quarkus-chat-ui-3` and `cp ... quarkus-chat-ui-3.0.0-SNAPSHOT.jar` in one command, so the
  copy happened regardless of what ps showed; a 28020 instance launched from that jar 25 minutes earlier
  was overwritten in place. The rule already existed (feedback_chatui3_deploy_no_live_overwrite).
- Rule: deploy = copy to a NEW unique file name (version + timestamp), then repoint the link. Never `cp`
  onto an existing versioned jar. If a check must gate an action, run the check in its own command
  and read it before acting.

## 2026-09-30 Call the Workflow-tab YAML "the outer workflow", never "the outer harness"
- `harness` is one actor inside that YAML (`ClaudeHarnessActor`, send/check) and also the word for
  the Claude Code / Codex CLI process. Naming the whole workflow after one of its actors confused
  the reader. Rule: outer workflow / `harness` actor / `queue` actor / inner workflow / CLI harness.

## 2026-09-30 Read doc_SCIVICS001 POJO-actor/400_AntiPatterns before writing any workflow actor
- I added `send`/`check` to a class named `ClaudeHarnessActor` and created `QueueBridgeActor`, both
  `extends IIActorRef<Object>` wrapping `new Object()`: state and behaviour in the adapter, no plain
  POJO, `Actor` in the name, and `check` returned FAILURE to mean "the answer was NO". All three are
  documented anti-patterns (010, 030, 050) that existed before I wrote the code.
- Rule: a workflow actor is a plain POJO (`HarnessLeash`) plus an `...IIAR` adapter whose `@Action`
  methods only delegate; a fact about the data is the action's message and the YAML decides with
  `this.putJson` + `this.onlyIf`; an action fails only when it could not do the work; catch-alls use
  `this.print`, never the auto-created `out`.
