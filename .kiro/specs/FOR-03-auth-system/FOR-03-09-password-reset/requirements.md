# Requirements Document — FOR-03-09 Password reset (end-to-end)

## Introduction

FOR-03-01 shipped the password-reset **API** (`POST /api/auth/password-reset/request`, `POST
/api/auth/password-reset/confirm`, single-use 60-minute tokens, session revocation) but the flow is
not usable end to end:

- no UI starts it (the login page has no "forgot password" entry, nothing calls `/request`);
- the emailed link is malformed (`resetBaseUrl + token` → `/auth/set-passwordAbC…`, no `?token=`);
- the link targets the invite-activation page, whose `/api/auth/set-password` accepts only invite
  tokens, so a reset token would be rejected; nothing calls `/confirm`;
- the reset email is English plain text, while invite/OTP emails are localized HTML (PL/RU).

This spec makes password reset work end to end for password-based (employee) accounts. It is a child
of `FOR-03-auth-system` and reuses the FOR-03-01 tokens/endpoints, the FOR-03-02/05 Thymeleaf mail
infrastructure (`EmailLocaleResolver`, `MessageResolver`) and the FOR-03-06 auth pages.

**Decisions (approved by the user, 2026-11-01):**
- After a successful reset the user is sent to the **login page** with a success message (no
  auto-login; the confirm endpoint stays as is and revokes every session).
- Reset applies to **password accounts only**: CLIENT accounts sign in with OTP and have no password,
  so a request for a CLIENT (like an unknown / INVITED / DEACTIVATED email) returns the same neutral
  200 and sends nothing.
- The email is localized by the **user's stored locale** (`users.locale`), like invite/OTP emails.

**Notifications:** none in-app. The only message is the transactional reset **email** (delivered via
the existing mail sender, like invite/OTP). An in-app bell notification is not applicable: the
recipient cannot sign in.

**Backend i18n entity fields:** none (no entity change, no migration).

## Glossary

- **Reset_Request_Page** — public page `/auth/forgot-password` where a user enters their email.
- **Reset_Confirm_Page** — public page `/auth/reset-password?token=…` where a user sets a new password.
- **Reset_Token** — the FOR-03-01 `password_reset_tokens` row (single-use, 60-minute TTL).
- **Reset_Email** — the localized HTML email carrying the Reset_Link.
- **Reset_Link** — `<reset base URL>?token=<Reset_Token>` pointing at the Reset_Confirm_Page.
- **Password_Account** — an ACTIVE user whose role is not CLIENT.

## Requirements

### Requirement 1 — Entry point on the login page

**User Story:** As an employee who forgot my password, I want a "forgot password" link on the login
page, so that I can start a reset without contacting an admin.

1. THE login page SHALL show a link "Nie pamiętasz hasła?" / "Забыли пароль?"
   (`auth.login.forgotPassword`) that navigates to `/auth/forgot-password`.
2. THE link SHALL be keyboard-reachable and have an accessible name equal to its text.

### Requirement 2 — Request a reset

**User Story:** As a user, I want to request a reset link by email, so that I can set a new password.

1. THE Reset_Request_Page SHALL be public and show a title, a hint, an email field and a submit button
   (`auth.forgot.title`, `auth.forgot.hint`, `auth.forgot.submit`).
2. WHEN the email is empty or invalid THEN the page SHALL show the existing login validation messages
   and SHALL NOT submit.
3. WHEN submitted THEN the page SHALL call `POST /api/auth/password-reset/request {email}` and, on a 200
   response, SHALL replace the form with the neutral confirmation `auth.forgot.sent` — the same message
   for every email (anti-enumeration).
4. THE page SHALL offer a "back to login" link (`auth.forgot.backToLogin`).
5. WHEN the request fails with a non-2xx response THEN the page SHALL show the server-localized error
   (or the generic auth error) and keep the form.

### Requirement 3 — Who receives a Reset_Email

**User Story:** As the system owner, I want only password accounts to receive reset emails, so that the
flow does not leak account existence or confuse OTP clients.

1. WHEN the email belongs to a Password_Account THEN the backend SHALL create a Reset_Token and send a
   Reset_Email (existing FOR-03-01 behaviour).
2. WHEN the email is unknown, or belongs to an INVITED, DEACTIVATED or CLIENT user THEN the backend
   SHALL create no token, send no email, and still respond 200 with no body (anti-enumeration).

### Requirement 4 — Reset_Email content and link

**User Story:** As a user, I want a clear, localized email with a working link.

1. THE Reset_Link SHALL be `<foremen.mail.reset-base-url>?token=<token>` with the token URL-encoded;
   the default base URL SHALL be `http://localhost:3000/auth/reset-password` (dev:
   `https://dev.foremen.eu/auth/reset-password`).
2. THE Reset_Email SHALL be HTML (Thymeleaf template `mail/password-reset.html`) in the recipient's
   locale resolved by `EmailLocaleResolver` (RU → Russian, otherwise Polish).
3. THE subject, greeting, body, button label and footer SHALL come from backend message keys
   `mail.passwordReset.subject|greeting|body|button|footer` in `messages.properties` (PL) and
   `messages_ru.properties` (RU); the footer states the 60-minute validity and "ignore if not you".

### Requirement 5 — Set the new password

**User Story:** As a user who opened the Reset_Link, I want to set a new password and then sign in.

1. THE Reset_Confirm_Page SHALL be public, read `token` from the query string, and show two password
   fields and a submit button (`auth.reset.title`, `auth.reset.password`, `auth.reset.confirm`,
   `auth.reset.submit`).
2. WHEN `token` is missing or empty THEN the page SHALL show `auth.reset.invalidLink` with a link to
   the Reset_Request_Page and SHALL NOT render the form.
3. THE page SHALL validate 8..72 characters and matching passwords, reusing
   `auth.setPassword.validation.length` / `.mismatch`.
4. WHEN submitted THEN the page SHALL call `POST /api/auth/password-reset/confirm {token, newPassword}`;
   on 200 it SHALL navigate to `/login` and show `auth.reset.success`.
5. WHEN the backend rejects the token (invalid / used / expired, 400) THEN the page SHALL show
   `auth.reset.invalidLink` with a link to request a new one.
6. THE backend confirm request SHALL accept passwords of 8..72 characters (bcrypt limit) and reject
   others with 400 (aligning `PasswordResetConfirm` with the set-password policy).
7. After a successful confirm the old password SHALL no longer authenticate, the new one SHALL, and
   every existing refresh token of the user SHALL be revoked (existing FOR-03-01 behaviour).

### Requirement 6 — Localization

1. EVERY new UI string SHALL exist in `pl.json` and `ru.json` (keys: `auth.login.forgotPassword`,
   `auth.forgot.{title,hint,submit,submitting,sent,backToLogin}`,
   `auth.reset.{title,password,confirm,submit,submitting,success,invalidLink,requestNew}`); the locale
   parity test SHALL pass.
2. NO hard-coded user-facing text in the new pages or the email.

### Requirement 7 — Compatibility

1. THE invite activation page `/auth/set-password` and `POST /api/auth/set-password` SHALL be
   unchanged.
2. THE request/confirm endpoint paths, status codes and anti-enumeration behaviour SHALL be unchanged
   (except the CLIENT exclusion of R3.2 and the 72-character maximum of R5.6).
