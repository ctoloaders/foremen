---
name: automate-spec
description: >-
  Automate a Foremen spec documented test cases as runnable E2E tests in the
  foremen-qa-auto suite (Cucumber-JVM + Playwright-for-Java against the live
  Docker stack). Use when the user asks to automate / implement test automation
  for / write E2E for a spec, or references a spec test-cases.md. Resolves the
  target spec, reads its test-cases.md, creates a detailed slice folder in
  foremen-qa-auto, implements BOTH the API (Part A) and UI (Part B) scenarios,
  runs the slice, and fixes failures until it is green.
---

# Automate a spec test cases (foremen-qa-auto)

Turn a completed spec test-cases.md into a runnable **detailed** E2E slice in the
`foremen-qa-auto` module, then run it and drive it to green. The authored Gherkin (with inline
Russian narration) mirrors the documented numbered test cases one-to-one, so the generated
`run-report.md` reads as the spec documented case set.

This skill coordinates existing standards; it does not replace them. Before implementing, read:

- `.kiro/steering/foremen-qa-auto.md` - the authoritative suite-extension standard (tags, folders,
  inline-narration convention, repeatability, Gradle tasks). Follow it exactly.
- `foremen-qa-auto/README.md` - stack bring-up, `installBrowsers`, `QA_*` config, project layout.
- `.kiro/steering/test-cases.md` - the test-cases document standard (Part A API / Part B UI split,
  repeatability, regression group, MD-report template).

## Step 1 - Resolve the target spec

1. If the user named a spec (e.g. FOR-06-01, work-reporting), use it. Otherwise use the spec of the
   **active editor file** (the `.kiro/specs/**/<spec>/` folder of the open `tasks.md` / `design.md`).
   If still ambiguous, ask the user which spec with `user_input`.
2. Confirm the spec folder exists and locate `test-cases.md` inside it
   (`.kiro/specs/**/<spec-id>/test-cases.md`).
   - If `test-cases.md` is **missing**, stop and tell the user; it is the required input. Offer to
     author it first via the test-cases.md steering standard (a separate task). Do not invent test
     cases from scratch.
3. Read `test-cases.md` fully. Note: it is written in **Russian**. Identify:
   - **Part A (API)** cases - HTTP against http://localhost:8080 (/api/...).
   - **Part B (UI)** cases - browser-engine scenarios on the frontend (http://localhost:3000).
   - The **regression** group and the declared **repeatability** strategy (run-id / teardown).
   - Each TC-* id with its steps and expected results; these become the Gherkin scenarios.
4. Read the spec requirements.md and design.md for the behaviors, routes, endpoints, roles, and
   error codes the cases exercise.

## Step 2 - Pick the detailed slice id and prepare the folders

The suite **detailed** tier is where per-spec E2E lives (never smoke; smoke is the shallow
build-signal tier). Each spec maps to a detailed feature id @FOR-QA-AUTO-0N.

1. Choose the slice id by matching the pattern already used in
   `foremen-qa-auto/src/test/resources/features/detailed/` and `build.gradle` (e.g. FOR-05-09 maps
   to FOR-QA-AUTO-09). If a sibling id already exists for the FOR-NN family, extend it consistently.
   When unsure of the exact id string, inspect the existing `features/detailed/for-qa-auto-*`
   folders and `featureTestForQaAuto*` tasks and follow the closest precedent.
2. Create the feature folder `foremen-qa-auto/src/test/resources/features/detailed/<feature-id>/`.
   Split by surface, as the existing for-qa-auto-09 slice does: `<area>_api.feature` for Part A and
   `<area>_ui.feature` for Part B.
3. The `RunSmokeTest` @Suite runner discovers any `.feature` on the features classpath resource by
   tag, so **no runner change is needed**.

## Step 3 - Implement ALL test cases (API + UI)

Author one Gherkin scenario per documented TC-*, in both surfaces. Follow the foremen-qa-auto.md
standard strictly:

- **Tags**: every feature @detailed @FOR-QA-AUTO-0N; add a surface tag @api or @ui (mirroring
  for-qa-auto-09), plus any sub-slice tag you want a pinned alias task for.
- **Mirror the test cases**: reference the TC-* id in each scenario name or intent line, and make
  the scenario narration mirror the case steps and expected results. Cover Part A (API), Part B
  (UI), and the **regression** group.
- **Inline Russian narration is mandatory** on every feature/scenario/step: a feature description
  line, an optional scenario intent line, and per-step trailing comments '# что:' (what) and
  '# ожидание:' (expected). A step with no annotation falls back to a keyword-generic report
  line - a smell; always annotate. This is what makes the client report correct.
- **Reuse first** - compose from existing glue before writing new code:
  - steps: `CommonSteps` (Given the application stack is ready), `AuthSteps`, `TestUserSteps`
    (role-based ACTIVE user provisioning with self-registered teardown), and the per-feature step
    classes under `src/test/java/com/foremen/qa/steps/`.
  - page objects under `pages/` (one per screen; prefer role/label/test-id locators), the workspace
    tab helpers (`WorkspaceTabs`, `ProjectWorkspacePage`), and API helpers under `support/`
    (`ApiHelper` plus feature helpers like `TeamApiHelper`, `NotificationApiHelper`).
  - Only add a new page object / step class / API helper for genuinely new screens or endpoints,
    placing them in the matching package and keeping routes/locators centralized.
- **Part A (API) scenarios** drive the backend directly through an ApiHelper-style client (login,
  CRUD, review endpoints, status/body/error-code assertions); no browser needed.
- **Part B (UI) scenarios** drive the frontend through page objects (open the project workspace tab,
  fill the form, submit, review, assert badges/toasts/visibility).
- **Repeatability**: never hard-code emails/codes/names - use `DataGen` for a per-run run-id, and
  register teardown via world.registerTeardown(...) (or rely on `TestUserSteps`). The slice must
  pass twice in a row with no manual DB cleanup. Never modify or delete seed data / system roles.
- **Gradle entry point**: add a thin per-id alias task in `foremen-qa-auto/build.gradle` by copying
  an existing `featureTestForQaAuto0N` block, renaming it, and pinning its default -DQA_TAGS to the
  new @detailed and @FOR-QA-AUTO-0N.
- Update the QA-AUTO OVERVIEW coverage map if the repo keeps one.

## Step 4 - Bring up the stack and run the slice

The suite needs the **live Docker stack**; it never starts an embedded app or Testcontainers.

1. From the repo root, bring the stack up: `docker compose up` (postgres :5432, liquibase, backend
   :8080, frontend :3000). The suite waits for readiness and fails fast if it is not up within
   QA_READINESS_TIMEOUT_MS.
2. One-time per environment: `./gradlew installBrowsers` (from foremen-qa-auto/) to download the
   Playwright Chromium binaries. Run it if the browser binaries are absent.
3. Run only this slice (from foremen-qa-auto/, so the ~20-min backend suite is never touched):

   ```bash
   ./gradlew featureTest -DQA_TAGS="@detailed and @FOR-QA-AUTO-0N" > /tmp/qa-<spec-id>.log 2>&1; echo "EXIT=$?"
   ```

   or the pinned alias task you added (`./gradlew featureTestForQaAuto0N`). Redirect to a temp log
   and read that log (the terminal wrapper truncates streamed output). Use the Cucumber JSON/HTML
   and the steering `run-report.md` under `build/qa-report/` as the authoritative pass/fail record.

## Step 5 - Fix failures and re-run until green (the core loop)

Repeat until the slice is fully green:

1. Read /tmp/qa-<spec-id>.log and `build/qa-report/` to find each failing scenario/step and its
   assertion/stacktrace.
2. **Diagnose the root cause before patching.** A failure is one of:
   - a **test-code defect** (wrong locator, wrong endpoint/payload, bad assertion, missing teardown,
     flaky wait) -> fix the feature/step/page/helper code in foremen-qa-auto.
   - a **product defect** (the app genuinely violates the documented behavior) -> fix the real code
     in foremen-backend and/or foremen-frontend to satisfy the spec, then re-verify the targeted
     tests per `.kiro/steering/test-execution-rules.md` (run only affected classes, never the full
     ~20-min suite). Rebuild/redeploy the stack so the running containers pick up the fix before
     re-running the slice.
   - a **spec/test-case ambiguity** (the documented expectation itself is unclear or contradictory)
     -> surface it to the user with `user_input` rather than guessing; do not silently weaken the
     assertion to force green.
3. Apply the fix in the correct repo (respect `.kiro/steering/git-repo-structure.md`: frontend
   changes commit from inside foremen-frontend/; backend/spec/qa changes from the root repo).
4. Re-run the same sliced command. If a container-side fix was made, rebuild the affected service
   (`docker compose up -d --build backend` / `frontend`) first.
5. **If the same approach fails twice, stop patching incrementally** - re-diagnose the real root
   cause and try a fundamentally different approach, per the global failure-loop rule. Report what
   you tried.

Done when: the slice passes end-to-end (0 failures), it is **repeatable** (passes on a second
consecutive run with no manual cleanup), and the generated `run-report.md` narration reads cleanly
(no keyword-generic fallback lines, every TC-* represented).

## Guardrails

- Do **not** run `./gradlew build` or the full `test` task of foremen-backend (~20 min). Run only
  the sliced QA task, and for product fixes only the affected backend/frontend test classes.
- Do **not** commit unless the user asks. If asked, make one commit per repo per
  `git-repo-structure.md`, staging only the files this task touched (never git add .).
- Treat `test-cases.md` as the contract. If reality diverges from it, fix the product or raise the
  ambiguity - never quietly rewrite the expectation to make a test pass.
- Keep every new scenario narration in Russian; keep raw Gherkin business-readable.

## Quick reference (end-to-end)

1. Resolve spec -> read .kiro/specs/**/<spec>/test-cases.md (+ requirements/design).
2. Create foremen-qa-auto/src/test/resources/features/detailed/<feature-id>/<area>_api.feature and
   <area>_ui.feature.
3. Implement every TC-* (API + UI + regression), reusing steps/pages/helpers; add Russian inline
   narration; use DataGen + teardown; add a featureTestForQaAuto0N alias in build.gradle.
4. docker compose up (root) -> ./gradlew installBrowsers (once) -> run the slice to a temp log.
5. Read the report, fix test-code or product defects, rebuild if needed, re-run until green and
   repeatable.
