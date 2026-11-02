# Design — FOR-03-10 Google login (end-to-end)

## Flow

```
Login page ── GIS renderButton (locale pl|ru) ── user picks Google account
   └─ credential (Google_ID_Token) ─▶ POST /api/auth/google { idToken }
        verify (sig/aud/exp) ─▶ email_verified? ─▶ user by email ─▶ CLIENT? ─▶ status
        ACTIVE → tokens │ INVITED → activate + tokens │ DEACTIVATED/CLIENT/none → 403
   ◀─ 200 { status: AUTHENTICATED, tokens } ─ setTokens → refreshIdentity → redirect
```

## Backend

`AuthService.loginWithGoogle(idToken)`:

```java
GoogleIdToken token = verifyGoogleIdToken(idToken);          // 401 token.invalid
Payload p = token.getPayload();
if (!Boolean.TRUE.equals(p.getEmailVerified())) throw 401 error.auth.google.email.unverified;
UserEntity user = userDao.findByEmail(p.getEmail().toLowerCase()).orElseThrow(403 no.account);
if (isClient(user)) throw 403 error.auth.google.client;
switch (status) {
  ACTIVE      -> authenticated(issueTokens(user))
  INVITED     -> activateViaGoogle(user); authenticated(issueTokens(user))
  DEACTIVATED -> throw 403 error.auth.account.deactivated
}
```

`activateViaGoogle`: `status = ACTIVE`, save; `inviteTokenDao.findByUserIdAndUsedFalse(id)` → `used =
true`, save. `password_hash` untouched (the user may later set one via FOR-03-09 reset). Everything
runs in the existing `@Transactional` method.

`GoogleLoginResponse`: keep the record shape `{status, tokens, setPasswordToken}` for wire
compatibility, but only the `authenticated(...)` factory remains (`setPasswordToken` always null);
`activationRequired` and `STATUS_ACTIVATION_REQUIRED` are removed. `InviteService.mintSetPasswordToken`
stays (used by invite issuance).

Messages:

| Key | PL | RU |
|---|---|---|
| `error.auth.google.no.account` | Najpierw poproś administratora o zarejestrowanie Cię w systemie. | Сначала попросите администратора зарегистрировать вас в системе. |
| `error.auth.google.client` | Klienci logują się jednorazowym kodem wysyłanym na e-mail. | Клиенты входят по одноразовому коду, отправленному на e-mail. |
| `error.auth.google.email.unverified` | Adres e-mail konta Google nie jest zweryfikowany. | Адрес электронной почты аккаунта Google не подтверждён. |

## Frontend

`lib/google-identity.ts` — replace `requestGoogleIdToken()` (One Tap prompt) with
`renderGoogleButton(container, { locale, width, onCredential })`: loads the GIS script once,
`initialize({ client_id, callback, ux_mode: 'popup' })` once per page load (the callback dispatches
to the latest registered `onCredential`, so re-renders/StrictMode remounts are safe), then
`renderButton(container, { type: 'standard', theme: 'outline', size: 'large', text: 'signin_with',
shape: 'rectangular', logo_alignment: 'left', width, locale })`. Rejects with `GoogleIdentityError`
(`script_load_failed` | `init_failed`). `isGoogleSignInConfigured()` reports whether
`VITE_GOOGLE_CLIENT_ID` is set.

`GoogleSignInButton` — renders a container `div`; on mount and on language change calls
`renderGoogleButton` with `i18n.language` and the container width (clamped 200..400 px). On credential
→ `authApi.googleExchange` → `setTokens` → `refreshIdentity` → `redirect()`. Busy state: the container
gets `aria-busy` + `pointer-events-none opacity-50` and a spinner. Error mapping per Requirement 4.3;
the CLIENT case renders the message plus a `Link` to `/auth/otp`. Returns `null` when not configured.
`authApi.googleExchange` return type becomes `{ status: 'AUTHENTICATED'; tokens }`; the
`redirectToSetPassword` bridge is deleted.

Frontend keys (`auth.google.*`):

| Key | PL | RU |
|---|---|---|
| `noAccount` | Najpierw poproś administratora o zarejestrowanie Cię w systemie | Сначала попросите администратора зарегистрировать вас в системе |
| `clientUseOtp` | Klienci logują się jednorazowym kodem wysyłanym na e-mail | Клиенты входят по одноразовому коду, отправленному на e-mail |
| `otpLink` | Zaloguj się kodem z e-maila | Войти по коду из e-mail |

`auth.google.button` stays (used as the accessible label of the button region).

CSP (`foremen-frontend/nginx.conf`): add `https://accounts.google.com/gsi/client` to `script-src`,
`https://accounts.google.com/gsi/` to `frame-src` (new directive; `default-src 'self'` otherwise
blocks the button iframe), `connect-src` and `style-src` (`https://accounts.google.com/gsi/style`).

## Configuration / infra

| Where | Change |
|---|---|
| `docker-compose.yml` | backend `FOREMEN_GOOGLE_CLIENT_ID: ${FOREMEN_GOOGLE_CLIENT_ID:-}`; frontend `build.args.VITE_GOOGLE_CLIENT_ID` |
| `foremen-frontend/Dockerfile` | `ARG/ENV VITE_GOOGLE_CLIENT_ID` |
| `foremen-frontend/cloudbuild.frontend.yaml` | `availableSecrets` `foremen-dev-google-oauth-client` → `VITE_GOOGLE_CLIENT_ID` build arg |
| `infra/dev/docker-compose.dev.yml` | backend `FOREMEN_GOOGLE_CLIENT_ID: ${FOREMEN_GOOGLE_CLIENT_ID:-}` |
| `infra/dev/vm-startup.sh` | read metadata `secret-google-oauth-client`, fetch, write `FOREMEN_GOOGLE_CLIENT_ID` to `.env` |
| `.env.example`, `infra/dev/secrets.template.env` | document `FOREMEN_GOOGLE_CLIENT_ID` / `VITE_GOOGLE_CLIENT_ID` |
| `.gitignore` | `infra/dev/google/`, `client_secret_*.json` |

Secret Manager `foremen-dev-google-oauth-client` holds only the client id (accessors: VM SA, Cloud
Build SAs, `foremen-ci`). The live VM `.env` gets the line from the secret via SSH (the startup-script
metadata refresh remains a separate operator step).

Google Cloud OAuth client: authorized JavaScript origins must include `https://dev.foremen.eu` (set),
and `http://localhost:3000` + `http://localhost` for local testing; add `https://app.foremen.eu` at
go-live. No redirect URIs are needed.

## Error handling

| Case | HTTP | Code |
|---|---|---|
| Invalid/expired/foreign-audience token | 401 | `error.auth.google.token.invalid` |
| `email_verified` ≠ true | 401 | `error.auth.google.email.unverified` |
| No user | 403 | `error.auth.google.no.account` |
| CLIENT | 403 | `error.auth.google.client` |
| DEACTIVATED | 403 | `error.auth.account.deactivated` |

## Testing

- Backend: `AuthServiceGoogleLoginTest` (unit, mocked verifier/DAOs) for every row above + INVITED
  activation (status, invite tokens used, hash untouched); update `GoogleLoginResponseInvariantPropertyTest`
  and `GoogleExchangeIntegrationTest` (INVITED → AUTHENTICATED + ACTIVE in DB; CLIENT → 403).
- Frontend: `GoogleSignInButton.test.tsx` with a mocked `renderGoogleButton` (success → tokens +
  redirect; no-account, client + OTP link, generic error; not configured → nothing rendered).
- Live: the real Google popup cannot be automated; it is validated manually on `dev.foremen.eu`.
