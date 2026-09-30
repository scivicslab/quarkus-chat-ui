# Lessons

## 2026-09-30 Verify engine syntax against the pinned version's sources, not the skill
- Wrote workflow YAML with `${var}` and bare-string `out.error` from the turing-workflow SKILL.md; the
  pinned engine (4.2.0) supports neither. A unit test that runs the YAML through a real `Interpreter`
  caught it; reading the sources jar (`Interpreter.convertArgumentsToJson`, `WorkflowExpressions`) gave
  the real syntax (`jexl: state.getString('k')`, `{message: ...}`).
- Rule: before writing YAML for an engine, open the sources jar of the version in `pom.xml` and run the
  YAML in a unit test. Also check the existing bundled YAMLs for the same defect and fix them together.
