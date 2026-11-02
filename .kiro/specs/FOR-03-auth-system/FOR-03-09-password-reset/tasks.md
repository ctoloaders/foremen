# Implementation Plan — FOR-03-09 Password reset (end-to-end)

Small spec: backend guard + DTO cap + localized email, then the two public pages, login link and
routes, then tests and test-cases. Two repos: backend/spec in the root repo, UI in `foremen-frontend/`.
Run only affected test classes (`.kiro/steering/test-execution-rules.md`).

## Tasks

- [x] 1. Backend: request guard + confirm policy
  - [x] 1.1 Exclude CLIENT users in `AuthService.requestPasswordReset` (neutral return, no token, no mail)
    - _Requirements: 3.1, 3.2_
    - _Depends on: none_
  - [x] 1.2 `PasswordResetConfirm.newPassword` → `@Size(min = 8, max = 72)`
    - _Requirements: 5.6_
    - _Depends on: none_
- [x] 2. Backend: localized reset email
  - [x] 2.1 `MailSender.sendPasswordReset(UserEntity, String)`; Thymeleaf HTML implementation
    (`EmailLocaleResolver`, `MessageResolver`, MimeMessage UTF-8), link
    `reset-base-url?token=<encoded>`; update the caller in `AuthService`
    - _Requirements: 4.1, 4.2_
    - _Depends on: 1.1_
  - [x] 2.2 Template `templates/mail/password-reset.html` (layout of `invite-set-password.html`)
    - _Requirements: 4.2, 4.3_
    - _Depends on: none_
  - [x] 2.3 Message keys `mail.passwordReset.*` in `messages.properties` + `messages_ru.properties`
    - _Requirements: 4.3, 6.2_
    - _Depends on: none_
  - [x] 2.4 Default `foremen.mail.reset-base-url` → `/auth/reset-password` in `application.yml`,
    `.env.example`, `infra/dev/docker-compose.dev.yml`
    - _Requirements: 4.1_
    - _Depends on: none_
- [x] 3. Backend tests
  - [x] 3.1 Unit tests: CLIENT / INVITED / unknown → no token, no mail; ACTIVE employee → token + mail
    with the user; confirm DTO rejects 7 and 73 chars; mail sender PL/RU subject + `?token=` link
    - _Requirements: 3, 4, 5.6_
    - _Depends on: 1.1, 1.2, 2.1, 2.2, 2.3_
- [x] 4. Frontend
  - [x] 4.1 `auth-api.ts`: `requestPasswordReset`, `confirmPasswordReset` (public, `skipAuthRefresh`)
    - _Requirements: 2.3, 5.4_
    - _Depends on: none_
  - [x] 4.2 i18n keys `auth.login.forgotPassword`, `auth.forgot.*`, `auth.reset.*` in `pl.json` + `ru.json`
    - _Requirements: 6.1_
    - _Depends on: none_
  - [x] 4.3 `ForgotPasswordPage` (`/auth/forgot-password`)
    - _Requirements: 2.1–2.5_
    - _Depends on: 4.1, 4.2_
  - [x] 4.4 `ResetPasswordPage` (`/auth/reset-password?token=`)
    - _Requirements: 5.1–5.5_
    - _Depends on: 4.1, 4.2_
  - [x] 4.5 Login page link + success alert; router: two lazy public routes
    - _Requirements: 1.1, 1.2, 5.4, 7.1_
    - _Depends on: 4.3, 4.4_
  - [x] 4.6 Vitest: both pages (validation, neutral message, invalid link, success navigation), login
    link + alert, routes, locale parity
    - _Requirements: 1, 2, 5, 6_
    - _Depends on: 4.5_
- [x] 5. Author test-cases.md
  - Create `test-cases.md` per `.kiro/steering/test-cases.md` (Russian): API scenarios against the
    Dockerized app + UI browser scenarios; repeatability via `+{run-id}` users and teardown; regression
    group (login, invite set-password, OTP); MD report template.
  - _Requirements: 1–7_
  - _Depends on: 3.1, 4.6_
- [x] 6. Validate end to end (local stack, browser) and update FOR-03 OVERVIEW status
  - Rebuild backend + frontend; forgot-password → token row → reset page → new password works, old
    fails; CLIENT and unknown emails → neutral, no token; invalid token page; PL + RU.
  - _Requirements: 1–7_
  - _Depends on: 3.1, 4.6, 5_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.2, 2.2, 2.3, 2.4, 4.1, 4.2 — independent files (service guard, DTO,
  template, message bundles, config, FE api, FE locales).
- **Wave 2 (parallel):** 2.1, 4.3, 4.4 — mail sender (needs 1.1's touched caller), the two pages (need
  api + keys); disjoint files.
- **Wave 3 (parallel):** 3.1, 4.5 — backend tests; login link + routes.
- **Wave 4:** 4.6 — frontend tests.
- **Wave 5:** 5 — test-cases.md.
- **Wave 6:** 6 — end-to-end validation + OVERVIEW status.
