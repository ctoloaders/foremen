# Implementation Plan — FOR-03-10 Google login (end-to-end)

Backend rules + messages, frontend GIS button, config/CSP/infra plumbing, tests, deploy. Two repos:
backend/spec/infra in the root repo, UI in `foremen-frontend/`. Run only affected test classes
(`.kiro/steering/test-execution-rules.md`).

## Tasks

- [x] 1. Backend
  - [x] 1.1 `AuthService.loginWithGoogle`: `email_verified` check, CLIENT rejection, INVITED
    auto-activation (status ACTIVE, invite tokens used), always AUTHENTICATED
    - _Requirements: 2.2, 3.1–3.7_
    - _Depends on: none_
  - [x] 1.2 `GoogleLoginResponse`: drop `activationRequired` / `STATUS_ACTIVATION_REQUIRED`; update
    `AuthController` javadoc
    - _Requirements: 3.7_
    - _Depends on: 1.1_
  - [x] 1.3 Messages `error.auth.google.{no.account,client,email.unverified}` PL + RU
    - _Requirements: 5.1_
    - _Depends on: none_
- [x] 2. Backend tests
  - [x] 2.1 `AuthServiceGoogleLoginTest` (new) + update `GoogleLoginResponseInvariantPropertyTest`,
    `GoogleExchangeIntegrationTest`
    - _Requirements: 2, 3_
    - _Depends on: 1.1, 1.2, 1.3_
- [x] 3. Frontend
  - [x] 3.1 `google-identity.ts`: `renderGoogleButton`, `isGoogleSignInConfigured`; remove One Tap
    prompt API
    - _Requirements: 4.1, 1.5_
    - _Depends on: none_
  - [x] 3.2 i18n keys `auth.google.{noAccount,clientUseOtp,otpLink}` PL + RU
    - _Requirements: 5.2_
    - _Depends on: none_
  - [x] 3.3 `GoogleSignInButton` on the rendered button; error mapping + OTP link; `auth-api`
    `googleExchange` type; delete the set-password bridge
    - _Requirements: 4.2, 4.3, 3.7_
    - _Depends on: 3.1, 3.2_
  - [x] 3.4 Vitest `GoogleSignInButton.test.tsx`; keep `LoginPage` tests green
    - _Requirements: 4_
    - _Depends on: 3.3_
- [x] 4. Config, CSP, infra
  - [x] 4.1 Frontend `Dockerfile` build arg, `cloudbuild.frontend.yaml` secret, `nginx.conf` CSP
    - _Requirements: 1.2, 4.4_
    - _Depends on: none_
  - [x] 4.2 Root `docker-compose.yml`, `infra/dev/docker-compose.dev.yml`, `vm-startup.sh`,
    `.env.example`, `secrets.template.env`, `.gitignore`
    - _Requirements: 1.1, 1.3, 1.4_
    - _Depends on: none_
- [x] 5. Author test-cases.md
  - Create `test-cases.md` per `.kiro/steering/test-cases.md` (Russian): API scenarios against the
    Dockerized app (token-level negatives), manual browser scenarios for the real Google popup on dev,
    repeatability strategy, regression group, MD report template.
  - _Requirements: 1–5_
  - _Depends on: 2.1, 3.4_
- [x] 6. Validate + deploy
  - Local stack: build with the client id, login page renders the GIS button (PL/RU), CSP clean,
    `/api/auth/google` negatives; push; sync dev compose + VM `.env`; verify the backend has the client
    id and the button renders on `dev.foremen.eu`; update FOR-03 OVERVIEW.
  - _Requirements: 1–5_
  - _Depends on: 2.1, 3.4, 4.1, 4.2, 5_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.3, 3.1, 3.2, 4.1, 4.2 — disjoint files (service, bundles, GIS
  wrapper, locales, frontend build/CSP, compose/infra).
- **Wave 2 (parallel):** 1.2, 3.3 — DTO cleanup (after the service stops using the factory); button
  (needs wrapper + keys).
- **Wave 3 (parallel):** 2.1, 3.4 — backend tests; frontend tests.
- **Wave 4:** 5 — test-cases.md.
- **Wave 5:** 6 — validation, deploy, OVERVIEW status.
