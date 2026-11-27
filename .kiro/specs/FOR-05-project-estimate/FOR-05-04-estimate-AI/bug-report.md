# Bug report: FOR-05-04-estimate-AI

Screen URL: `https://dev.foremen.eu/projects/30` (rooms tab, "Create estimate" with AI). Reproduction API call:
`POST https://dev.foremen.eu/api/rooms/project/30/autoimport` (ESTIMATE mode).

## Round 1 (2026-11-27)

- [x] 1. `POST /api/rooms/project/30/autoimport` returns `503 error.estimateai.unavailable` on dev
  - Triage: observed `503 Service Unavailable`, `code: error.estimateai.unavailable` for the ESTIMATE-mode
    start; expected `202 {jobId, status}` and a running AI estimate job. Layer: infra (dev deployment config);
    no code defect.
  - Cause: `EstimateAiStartGuard` (line 64) refuses the start when `foremen.estimate-ai.enabled` is false or the
    plan client is the `DisabledEstimatePlanClient`. The switch defaults to `false` in `application.yml`
    (`${FOREMEN_ESTIMATE_AI_ENABLED:false}`). The local `docker-compose.yml` sets
    `FOREMEN_ESTIMATE_AI_ENABLED: ${FOREMEN_ESTIMATE_AI_ENABLED:-true}`, but the dev stack file
    `infra/dev/docker-compose.dev.yml` never passes the variable, so the dev backend runs with the feature off.
    FOR-05-02 rooms import works on dev because the same file sets `FOREMEN_ROOMS_IMPORT_ENABLED=true`.
  - Fix: in `infra/dev/docker-compose.dev.yml` (backend environment, next to the FOR-05-02 block) add
    `FOREMEN_ESTIMATE_AI_ENABLED: ${FOREMEN_ESTIMATE_AI_ENABLED:-true}` and
    `FOREMEN_ESTIMATE_AI_LLM_STUB: ${FOREMEN_ESTIMATE_AI_LLM_STUB:-false}`. The Gemini key, model and project are
    inherited through the existing fallback chain (`foremen.estimate-ai.llm.*` → `foremen.rooms-import.llm.*` →
    `foremen.ocr.llm.*`, key from Secret Manager `foremen-dev-ocr-gemini-key`), so no new secret is needed. The
    explicit `LLM_STUB=false` keeps the `application-docker.yml` stub default (`true`) from ever applying on dev.
    Redeploy the dev stack (sync the compose file to the VM, `docker compose up -d backend`).
  - Check after the fix: backend log shows `GeminiEstimatePlanClient` (not `Disabled*`); the start returns 202;
    the job runs to `COMPLETED` (or a model error, which is a different defect), and the offer AI chat answers.
  - _Requirements: 2.8_
  - _Depends on: none_
- [x] V1. Validate Round 1 on dev
  - After redeploy, log in on `https://dev.foremen.eu`, open project 30, start "Create estimate" with AI, and
    confirm the job starts (no 503), progresses through its stages, and the estimate is filled.
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — dev compose configuration and redeploy.
- **Wave 2:** V1 — validation on dev, depends on 1.

## Round 2 (2026-11-27)

Screen URL: `https://dev.foremen.eu/projects/30/rooms` ("Create estimate" button, then "Create" in the dialog).

- [x] 2. After "Create estimate" → "Create", the dialog shows "ИИ сервис недоступен" (AI service unavailable)
  - Triage: observed the message `error.estimateai.failed.llm_unavailable` (ru: "Сервис ИИ недоступен."), shown after the job starts. Expected a running AI estimate job that fills the estimate. Layer: infra (dev config), possibly backend. This is a different failure from Round 1: the start no longer returns 503 (commit `ebc34a3` enabled the feature), the job now fails inside the worker.
  - Cause (not yet confirmed): `EstimateAiWorker.reasonOf` maps both `DISABLED` and `LLM_UNAVAILABLE` to   - Cause (not yet confirmed): `EstimateAiWorker.reasonOf` maps both `DISABLED` and `LLM_UNAVAILABLE` to   - Cause (not yet confirmed): `EPI  - Cause (not yet confirmed): `EstimateAiWorker.reasonOf` maprk  - Cause (no, mo  - Cause (not yet ) t  - Cause (not yet conend  - Cause (not yet confirmed): `EstimateAiWorker.reasonOf` maps both `DISABLED` and `LLM_UNAVAILABLE` to   - Csta  - Cause (not yet confirmed): `EstimateAiWorker.reasonOf` maps both `DISABLED` and `LLM_UNAVAILABLE` to   - Cause (not yet conf(c) key quota or restriction.
  - Needed to confirm: the backend log on the dev VM around the job time (`GenAiPlanJsonTransport` exception and its cause). `gcloud` on this machine has expired credentials (`gcloud auth login` needed), so I could not read the VM logs. Either re-authenticate gcloud or paste the backend log lines.
  - Fix: depends on the confirmed cause. If config, correct `infra/dev/docker-compose.dev.yml` or the secret and redeploy with `infra/dev/56-sync-stack.sh`. If the exception hides the cause, also log the underlying exception message at WARN in `GenAiPlanJsonTransport`.
  - _Requirements: 2.8_
  - _Depends on: none_
- [x] V2. Validate Round 2 on dev
  - Open project 30 rooms, start "Create estimate", confirm the job reaches COMPLETED and the estimate is filled.
  - _Depends on: 2_

## Parallel execution plan (waves) — Round 2

- **Wave 1:** 2 — diagnosis and fix.
- **Wave 2:** V2 — validation on dev.


## Dev validation (2026-11-27, after deploy of root `3f2cf7b` and frontend `7012e3f`)

- Cause of Round 2 #2 (corrected): the dev backend log showed `503 error.estimateai.unavailable`, i.e. the Round 1 defect. The Round 1 compose change (`ebc34a3`) was committed but not synced to the VM; `infra/dev/56-sync-stack.sh` applied it on 2026-11-27. It was not a Gemini failure.

Setup: dedicated test users created through the app (ADMIN `POST /api/users` + the invite `set-password` flow): MANAGER 13, FOREMAN 14, WORKER 15 (`test+qa-*-11272111@example.com`), and a test project 31 created by the MANAGER. After the checks the users (13-18) were deactivated and project 31 was renamed and set to CANCELLED (delete is refused with `error.project.hasDependents`). Project 5 was only opened read-only; nothing was saved there.

- Browser, MANAGER, `/projects/31/rooms` → "Create estimate" with a text description → "Create": `POST /api/rooms/project/31/autoimport` 202 `{jobId: 10, status: QUEUED}` (no 503). The job went `PLANNING_WORKS` → `ASSIGNING` → `COMPLETED` in about 40 s with no failure reason; the project got 2 rooms and 19 estimate lines. ✅
- Backend container: `FOREMEN_ESTIMATE_AI_ENABLED=true`, `FOREMEN_ESTIMATE_AI_LLM_STUB=false`. Project 30 itself was not re-run, to keep its estimate unchanged.
