---
inclusion: always
---

# Standard: Workspace Git Repository Structure

This workspace is **not a single git repository**. It contains **two separate,
independent git repositories**. Committing a change requires committing it in the
**correct** repo — otherwise the change is silently left uncommitted.

The critical trap: the root repo's `.gitignore` (line 52) ignores
`foremen-frontend/`, so the entire `foremen-frontend/` subtree is invisible to the
root repo. A `git add` / commit run from the workspace root will **never** stage
frontend source changes.

## Two repositories

### 1. Root repo — `<workspace root>` (`/Users/alexander.borohov/projects/loaders/foremen`)

- Branch: `main`.
- Tracks: specs (`.kiro/`), backend (`foremen-backend/`), infrastructure
  (`docker-compose.yml`), documentation (`docs/`), and other top-level files.
- **Ignores `foremen-frontend/` entirely** (`.gitignore` line 52 → `foremen-frontend/`).
  Verified: `git check-ignore foremen-frontend/` matches, and
  `git ls-files foremen-frontend/src` returns **0** tracked files from the root.

### 2. Frontend repo — `foremen-frontend/` (nested, independent)

- Top-level: `/Users/alexander.borohov/projects/loaders/foremen/foremen-frontend`
  (its own `.git`). Branch: `main`.
- Tracks **all** frontend code and tests under `foremen-frontend/src/**` (features,
  components, locales, tests, app layout, router, config). Verified: the inner repo
  tracks the frontend `src` tree that the root repo does not see.

### Consequence

- A commit at the **root** will **never** include frontend changes.
- A `git add` / commit **inside `foremen-frontend/`** will **never** include
  spec / backend / infra changes.
- A single logical feature that touches both (e.g. a spec plus its frontend
  implementation) needs **two commits — one per repo**.

## How to commit frontend (`foremen-frontend`) changes

Frontend changes MUST be committed **from inside the `foremen-frontend/` directory**.
Set the tool's `cwd` to the `foremen-frontend` path — do **not** use `cd`.

Stage specific files (not `git add .`):

```
# from within foremen-frontend/
git status
git add src/features/project-workspace src/components/shell src/app/layout/TopBar.tsx src/app/layout/Sidebar.tsx src/app/router.tsx src/config/route-permissions.ts src/features/projects/components/ProjectsList.tsx src/locales/pl.json src/locales/ru.json
git commit -m "feat(FOR-05-01): project workspace shell"
```

Git-safety norms (consistent with the workspace standards):

- Push only to a **new branch**; never push directly to `main` unless explicitly asked.
- **Never** force-push.
- **Never** modify git config.
- Commit **only when the user asks**.

## How to commit workspace / spec + backend changes

Spec, backend, and infrastructure changes are committed from the **root repo**
(set `cwd` = workspace root). A root commit picks up `.kiro/`, `foremen-backend/`,
`docker-compose.yml`, `docs/`, etc. — but **never** the frontend.

```
# from within the workspace root
git status
git add .kiro/specs/FOR-05-project-estimate foremen-backend/... docker-compose.yml
git commit -m "feat(FOR-05-01): spec + backend"
```

Same git-safety norms apply: new branch only, no force-push, no config changes,
commit only when asked.

## Checklist before committing a feature that spans both

- [ ] Commit frontend code in `foremen-frontend/` (inner repo)
- [ ] Commit spec / backend / infra in the root repo
- [ ] Verify with `git status` in **both** repos that nothing intended was left behind

## Note on the commit-after-tasks hook

When a spec's tasks complete and a commit is expected (e.g. via the
`commit-after-tasks` hook), remember which repo holds the implementation:

- A **frontend-only** spec's implementation lives in the `foremen-frontend/` repo,
  so the code commit MUST be made **there**. A commit at the root would only capture
  the spec docs under `.kiro/` and would silently miss all frontend source.
- A backend / infra spec commits from the **root** repo.
- A spec that spans both needs a commit in **each** repo.
