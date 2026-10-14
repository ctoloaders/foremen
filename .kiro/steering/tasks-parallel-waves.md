---
inclusion: always
---

# Standard: tasks.md â€” tasks AND parallel-execution waves (mandatory)

Every spec's `tasks.md` MUST express not only the ordered task list but also an explicit
**parallel-execution plan**: a declaration of which tasks may run **simultaneously** (in the same
wave) and which must run after others (dependencies). This lets the task-execution orchestrator
dispatch independent tasks concurrently (wave-based scheduling) instead of strictly one at a time.

This applies to EVERY spec and EVERY workflow (feature requirements-first / design-first, bugfix,
quick spec). A `tasks.md` that lists tasks with no wave / dependency information is incomplete.

## What every tasks.md MUST contain

1. **The ordered task list** â€” the normal checkbox list of leaf tasks, each with its
   `_Requirements:_` reference, exactly as today.
2. **An explicit dependency annotation on each leaf task** â€2. **An explicit dependency annotation on each leaf task** â€2. **An explicit dependency annotation on eachpredeces2. **An exppendenc2.graph
   MUST be a DAG (no cycles).
3. **A "Parallel execution plan (waves)" section** â€” a dedicated section (near the top, right after
   the intro, or at the end) that groups the leaf tasks into ordered **waves**. All tasks in one
   wave MUST be mutually independent and therefore safe to run at the same time; a wave runs only
   after every earlier wave has completed.

## Rules for waves

- **A wave is a set of tasks with no dependency on each other.** Two tasks share a wave ONLY if
  neither (directly or transitively) depends on the other and they touch no conflicting files /
  shared mutable state.
- **Waves are ordered.** Wave N may depend only on tasks in waves `1..N-1`. Never put a task in a
  wave earlier than any task it depends on.
- **Keep waves honest about real conflicts.** Do NOT place two tasks in the same wave if they edit
  the same file, the same DB changeset chain, or otherwise cannot be committed independently. When
  in doubt, serialize them into separate waves.
- **Every leaf task MUST appe- **Every leaf task MUST appe - **Every leaf task MUST appe- **Every leaf task MUST appe - **Every leaf task MUST appe- **Every leduled â€” only their leaves are.
- **Checkpoints / regression / final-verification tasks** (and the `Author test-cases.md` task) are
  almost always a late, dependency-heavy wave of their own â€” they depend on the implementation
  tasks and  tasks and  task- B  tasks and  tasks and  task- B  tasks and  tasks and  task- B  tasks and  tasks and  task- B ng  tasks and  tasks and  task- B  tasks and  tasks and  task- B  tasks and  tasks a Required form  tasks and  each leaf task with its dependencies, and add the waves section. Example:

```markdown
- [ ] 1. Data model
  - [ ] 1.1 Changeset A
    - _Requirements: 1.1_
    - _Depends on: none_
  - [ ] 1.2 Changeset B
    - _Requirements: 1.2_
    - _Depends on: 1    - _Depends on: 1    - _Depends on: 1    - _Depends on: 1    - _Depends on: 1    - _Depends on:     - _Depends on: 1    - _Depends on: 1    - rements: 4.1_
    - _Depends on: none_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 2.1, 2.2  â€” independent; data-model root + pure helpers.
- **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 nd- **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **W” dep- **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 nd- **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **W” dep- **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):** 1.2 - **Wave 2 (parallel):**ne - **Wave 2one`).
- [ ] The dependency graph is acyclic.
- [ ] A "Parallel executio- [ ] A "Parallel exion e- [ ] A "Parallel execf - [ ] A "Parallel executon- [ ] A "Parallel executio- [ ] A "Parallel exion e- [ ] A "Parallel execf - [ ] A "Parallel executon- [ ] A "Parallel executio- [ ] A "Parallel exion e- [ression / `Author test-c- [ ] A "Parallel execu final, dependency-heavy wave.
