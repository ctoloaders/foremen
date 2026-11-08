---
inclusion: always
---

# Standard: tasks.md — parallel-execution waves (mandatory)

Every spec's `tasks.md` MUST end with a **"Task Dependency Graph"** section: a JSON block that
groups the leaf tasks into ordered **waves**. All tasks in one wave are mutually independent and
safe to run at the same time; a wave runs only after every earlier wave has completed. This lets the
task-execution orchestrator dispatch independent tasks concurrently.

This applies to EVERY spec and EVERY workflow (feature requirements-first / design-first, bugfix,
quick spec). A `tasks.md` without this section is incomplete.

## Required section (at the END of tasks.md)

The section MUST be the last section of the file, with exactly this heading and format:

````markdown
## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "4.1"] },
    { "id": 3, "tasks": ["2.4", "2.5", "4.2", "4.3"] },
    { "id": 4, "tasks": ["2.6", "4.4", "5.1", "5.2", "5.3", "5.5", "8.1", "8.2"] },
    { "id": 5, "tasks": ["5.4", "5.6", "8.3", "9.2", "10.1"] },
    { "id": 6, "tasks": ["5.7", "6.1", "8.4", "9.1", "10.2"] },
    { "id": 7, "tasks": ["6.2", "9.3"] },
    { "id": 8, "tasks": ["11", "12"] }
  ]
}
```
````

## Rules

- Wave `id`s start at `0` and increase by 1. Task entries are the task numbers exactly as they
  appear in `tasks.md` (e.g. `"2.3"`, `"11"`), as strings.
- **A wave is a set of tasks with no dependency on each other**, directly or transitively.
- **Waves are ordered.** A task may depend only on tasks in earlier waves. Never place a task in a
  wave earlier than or equal to any task it depends on.
- **Be honest about conflicts.** Do NOT put two tasks in the same wave if they edit the same file,
  the same DB changeset chain, or otherwise cannot be committed independently. When in doubt,
  serialize into separate waves.
- **Every leaf task appears in exactly one wave.** Parent/group tasks are not scheduled; only their
  leaves are. Optional tasks (marked `*`) are included too.
- **The dependency graph MUST be acyclic.**
- **Checkpoints, regression, final-verification and `Author test-cases.md` tasks** belong in the
  last waves, since they depend on the implementation tasks.
- Keep the JSON valid (no comments, no trailing commas) so it can be parsed.
- When tasks are added, removed or renumbered, update this section in the same edit.
