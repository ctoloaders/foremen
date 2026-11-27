# Bug report: FOR-08-ai-estimate

Scope: the loaders-bot assistant widget embedded on the authenticated app shell (`AppShell` mounts
`useLoadersBot`). The widget and its agent runtime are external (loaders-bot). Foremen owns the
OpenAPI toolset (`foremen-backend/src/main/resources/openapi/foremen-bot-toolset.yaml`), the bot
configuration mirror (`bot-configuration.md`), and the host glue
(`foremen-frontend/src/features/assistant/loaders-bot.ts`).

Screen URL: TBD. The assistant renders on every authenticated page. Add the page URL where the bugs
were reproduced so validation knows where to start.

## Round 1 (2026-11-23)

- [ ] 1. Agent reports that a project was created without calling the create-project tool
  - Triage: observed: the agent replied that project "Plac Defilad" was created (DRAFT) with an
    estimate net total of 79,087.10 and offered next steps. No `POST /api/projects` request reached
    the backend. Expected: the agent calls the `createProject` tool (`POST /api/projects`), follows
    the confirm-before-write policy, and reports only the outcome the tool returned.
    Layer: both (loaders-bot agent behavior and toolset/configuration; no Foremen code path produces
    the reply). Suspect: `foremen-backend/src/main/resources/openapi/foremen-bot-toolset.yaml`
    (`createProject`, line ~72 is present in the curated toolset), `.kiro/specs/FOR-08-ai-estimate/bot-configuration.md`
    (goals G1–G8; §4.2 live console validation is still a manual follow-up).
    First check: query the database for a project named "Plac Defilad". If none exists, the reply
    is a hallucinated success. If one exists, identify which request created it.
  - _Requirements: FOR-08 bot tool invocation and write-confirmation requirements (bot-configuration §4, goal G1–G8)_
  - _Depends on: none_

- [ ] 2. New chat shows answers from earlier sessions and other users
  - Triage: observed: a new chat contains older agent answers, including answers from other
    sessions. Expected: a new chat starts empty and only shows the current signed-in user's
    conversation. Layer: both. Suspect: the widget keeps conversation state keyed by site, not by
    user or session (external). On the Foremen side, `foremen-frontend/src/app/layout/UserFooter.tsx`
    logs out and navigates to `/login`, but nothing tells the widget that the identity changed.
    `foremen-frontend/src/features/assistant/loaders-bot.ts` has no `identify` or reset call. Its
    header says the widget resolves identity from `browser_storage` and `GET /api/auth/me`, while
    `bot-configuration.md` §1.3 and §2.1 describe a host auth provider and an identity-token endpoint.
    Establish which model is live before choosing the fix.
  - _Requirements: FOR-08 bot identity and session requirements (bot-configuration §1.3, §2.1)_
  - _Depends on: none_

- [ ] V1. Validate Round 1 in browser
  - Bring up the local stack and log in with a non-ADMIN and an ADMIN user. Ask the assistant to
    create a project and confirm that the `createProject` request appears in the backend log before any
    success message. Log out, log in as a second user, open a new chat, and confirm no earlier
    answers appear. Record evidence per bug.
  - Part of the widget behavior lives in the loaders-bot admin panel. Changes there are validated in
    the bot test console and recorded here as manual evidence.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 2. Independent: bug 1 is toolset and bot configuration, bug 2 is widget
  identity and session handling.
- **Wave 2:** V1, validation after both fixes.

## Round 1 — validation results (2026-11-23, dev.foremen.eu, admin session)

Steps: logged in as ADMIN, opened a new conversation in the assistant, asked it to create project
"QA test 2026-11-23 22-27" (address Plac Defilad 8, status DRAFT), approved the confirmation card. Then
retried once in the same conversation and approved again.

- Bug 1 (no tool call, hallucinated success): not reproduced as a hallucination in this session. The bot
  called `POST /api/projects` both times. Both calls returned 401, and the bot reported a failure.
  The earlier "project successfully created" reply is false: no "Plac Defilad" project exists on dev
  (project list: Test Koten, syta, Alex, Talarowa 8B).
- New bug found while validating (add to Round 1 scope): the reply says the user must "log in"
  (`требуется удостоверение`), even though the user is logged in. The Russian term `удостоверение`
  means an identity document. It should say authorization or sign-in, and it should not tell a logged-in
  user to log in.

### Root cause for the 401 (evidence)

- The browser session token, sent with `Content-Type: application/json`, reaches the controller:
  `POST /api/projects` with `{}` returns 400 validation (`name` must not be blank).
- The same token and body with `Content-Type: text/plain;charset=UTF-8` returns 401 with
  `path: /error`. The assistant's tool call uses `text/plain`. The Foremen frontend sends
  `application/json` (`foremen-frontend/src/lib/api-client.ts`).
- A nonexistent path with a valid token (`GET /api/does-not-exist-fb`) also returns 401 with
  `path: /error`, not 404. So any Spring error that reaches the `/error` dispatch loses its
  authentication and is reported as "authentication required". The `text/plain` body most likely
  fails with 415 at that point. The 415 is inferred from the masking, not observed directly.
- The original curl from the first session also sent `Content-Type: text/plain;charset=UTF-8`. Its
  401 was therefore this same issue. The JWT secret mismatch I found earlier was real for the compose
  secret, but the 401 persisted after the JWT update, so the header is the main cause.

### Fix candidates (for approval, not applied)

1. Loaders-bot toolset / tool-call configuration: send `Content-Type: application/json` for JSON tool
   calls. This is external configuration and must be changed in the loaders-bot admin panel.
2. Backend: return the real status for unsupported media types and missing routes (415, 404) instead
   of 401, so errors are not reported as authentication failures. Check the `/error` dispatch in
   `SecurityConfig` (the entry point applies to error dispatches as well).

### Status

- [ ] 1. Still open. The tool call fails on the 401/`text/plain` path. The reply wording is wrong.
- [ ] 2. Not reproduced in this session. The new conversation showed only the greeting, with no answers
  from earlier conversations. The "View conversation history" panel was not checked.
