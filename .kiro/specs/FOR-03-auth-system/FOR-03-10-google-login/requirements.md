# Requirements — FOR-03-10 Google login (end-to-end)

## Introduction

FOR-03-06 shipped a Sign-in-with-Google skeleton (`POST /api/auth/google`, a login-page button), but it
never worked on any environment: the OAuth client id was not wired into the backend or the frontend
build, the frontend CSP blocks Google Identity Services (GIS), the button relied on the One Tap
`prompt()` (suppressed by Google after dismissals), and the backend did not check `email_verified`.

This spec makes Google login work end to end for **employees** with these business rules:

- A user whose Foremen email is a Google-managed account (Gmail, Google Workspace, or any Google
  account whose email Google reports as verified) can sign in with Google.
- An INVITED employee is **automatically activated** on the first Google sign-in (Google's verified
  email is trusted); an ACTIVE employee is signed in.
- A Google account whose email is not registered in Foremen gets "First ask the admin to register you
  in the system".
- Matching is by **exact email only**. Different emails are different accounts; a Google account can
  never be linked to a different (non-Google) Foremen email.
- CLIENT users cannot use Google login — they sign in only with the OTP code sent to email.
- No domain restriction. Environment: `https://dev.foremen.eu` now, `https://app.foremen.eu` later.

Out of scope: account linking, Google-only provisioning (self-registration), notifications (the user
is not in the app yet when this happens — none are emitted), entity i18n fields or migrations.

## Glossary

- **Google_ID_Token** — the JWT returned by GIS to the browser, verified by the backend
  (`GoogleIdTokenVerifier`: signature, `aud` = configured client id, expiry, issuer).
- **Employee** — any user whose role code is not `CLIENT`.

## Requirements

### Requirement 1 — Configuration

**User story:** As an operator, I want the OAuth client id configured per environment without
committing credentials.

1. The backend SHALL read the client id from `FOREMEN_GOOGLE_CLIENT_ID` (`foremen.google.client-id`);
   both `docker-compose.yml` and `infra/dev/docker-compose.dev.yml` SHALL pass it to the backend.
2. The frontend SHALL read `VITE_GOOGLE_CLIENT_ID` at build time; the `Dockerfile` SHALL accept it as a
   build arg, the root compose SHALL pass it, and the dev Cloud Build SHALL source it from Secret
   Manager (`foremen-dev-google-oauth-client`).
3. On the dev VM the backend value SHALL come from the same secret (`/opt/foremen/.env`, populated by
   `vm-startup.sh`).
4. The downloaded OAuth credentials JSON SHALL never be committed (gitignored); the client secret is
   not used by this flow and SHALL NOT be deployed.
5. WHEN `VITE_GOOGLE_CLIENT_ID` is empty THEN the login page SHALL NOT render the Google button.

### Requirement 2 — Token verification

1. The backend SHALL verify the Google_ID_Token as today; any failure → `401
   error.auth.google.token.invalid`.
2. WHEN the token's `email_verified` claim is not `true` THEN the backend SHALL respond `401
   error.auth.google.email.unverified` and issue nothing.

### Requirement 3 — Account resolution

1. The backend SHALL look up the user by the token email, lower-cased, exact match only.
2. WHEN no user has that email THEN `403 error.auth.google.no.account` ("First ask the admin to
   register you in the system").
3. WHEN the user's role is `CLIENT` (any status) THEN `403 error.auth.google.client`; no token,
   no state change.
4. WHEN the employee is DEACTIVATED THEN `403 error.auth.account.deactivated`.
5. WHEN the employee is INVITED THEN, in one transaction, the backend SHALL set status ACTIVE, mark all
   of the user's unused invite tokens used, leave `password_hash` unchanged, and issue a session.
6. WHEN the employee is ACTIVE THEN the backend SHALL issue a session.
7. The response SHALL always be `{ status: "AUTHENTICATED", tokens }`; the `ACTIVATION_REQUIRED`
   branch and its set-password bridge SHALL be removed.

### Requirement 4 — Login page button

1. The login page SHALL render Google's own GIS button (`google.accounts.id.renderButton`) in the
   page language (PL/RU) at the card width, instead of the One Tap prompt.
2. On a credential, the page SHALL call `POST /api/auth/google`, store the tokens, hydrate the
   identity, and redirect like a password login; while in flight the button area SHALL be busy.
3. Errors: `error.auth.google.no.account` → `auth.google.noAccount`; `error.auth.google.client` →
   `auth.google.clientUseOtp` plus a link `auth.google.otpLink` to `/auth/otp`; any other API error →
   the server-localized message; a GIS load/init failure → `auth.google.failed`.
4. The frontend CSP SHALL allow `https://accounts.google.com/gsi/` for scripts, frames, connections and
   styles.

### Requirement 5 — Localization

1. Backend messages (PL base / RU) SHALL be updated/added for `error.auth.google.no.account`,
   `error.auth.google.client`, `error.auth.google.email.unverified`.
2. Frontend keys `auth.google.noAccount` (updated), `auth.google.clientUseOtp`, `auth.google.otpLink`
   (new) SHALL exist in `pl.json` and `ru.json`.
