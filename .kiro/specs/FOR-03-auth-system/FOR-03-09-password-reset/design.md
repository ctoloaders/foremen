# Design Document — FOR-03-09 Password reset (end-to-end)

## Overview

A small, mostly-frontend change that wires the existing FOR-03-01 reset API into two new public pages,
fixes the emailed link, localizes the email, excludes CLIENT accounts, and caps the password at 72
characters. No migration, no new entity, no new ABAC resource, no in-app notification.

```
Login ──"Nie pamiętasz hasła?"──▶ /auth/forgot-password ──POST /request──▶ 200 (neutral)
                                                                    │ (Password_Account only)
                                                                    ▼
                                         Reset_Email (PL/RU HTML) ── link ──▶ /auth/reset-password?token=…
                                                                                   │ POST /confirm
                                                                                   ▼
                                                                    /login + "Hasło zostało zmienione"
```

## Backend

### AuthService.requestPasswordReset (R3)
Add the CLIENT exclusion to the existing guards (after the ACTIVE check):

```java
if (user.getStatus() != UserStatus.ACTIVE || isClient(user)) {
    return; // neutral 200, no token, no email (anti-enumeration)
}
```
`isClient` = `user.getRole() != null && "CLIENT".equals(user.getRole().getCode())`.

### PasswordResetConfirm (R5.6)
`@NotBlank @Size(min = 8, max = 72) String newPassword` — the same 8..72 policy as
`SetPasswordRequest`; an out-of-range value is a 400 through the existing
`MethodArgumentNotValidException` handler.

### Localized email (R4)
- `MailSender.sendPasswordReset(String toEmail, String token)` → change the signature to
  `sendPasswordReset(UserEntity user, String token)` so the locale is available (only caller:
  `AuthService.requestPasswordReset`).
- Replace the plain-text `SmtpMailSender` implementation with a Thymeleaf HTML sender mirroring
  `ThymeleafInvitationMailSender`: `Locale locale = EmailLocaleResolver.resolve(user.getLocale())`,
  subject via `MessageResolver`, body via `templateEngine.process("mail/password-reset", ctx)`, sent as
  a `MimeMessage` (UTF-8, HTML).
- Link: `UriComponentsBuilder.fromUriString(resetBaseUrl).queryParam("token", token).encode().toUriString()`.
- Template `templates/mail/password-reset.html`: same layout as `invite-set-password.html` (greeting,
  body, primary button → link, plain link fallback, footer).
- Config: default `foremen.mail.reset-base-url` → `http://localhost:3000/auth/reset-password`;
  update `.env.example` and `infra/dev/docker-compose.dev.yml` (`https://dev.foremen.eu/auth/reset-password`).
  The dev VM `.env` currently has no override, so the compose default applies.

Message keys (PL base / RU):

| Key | PL | RU |
|---|---|---|
| `mail.passwordReset.subject` | Resetowanie hasła — Foremen | Сброс пароля — Foremen |
| `mail.passwordReset.greeting` | Dzień dobry, {0}! | Здравствуйте, {0}! |
| `mail.passwordReset.body` | Otrzymaliśmy prośbę o zresetowanie hasła do Twojego konta Foremen. Kliknij przycisk poniżej, aby ustawić nowe hasło. | Мы получили запрос на сброс пароля вашего аккаунта Foremen. Нажмите кнопку ниже, чтобы установить новый пароль. |
| `mail.passwordReset.button` | Ustaw nowe hasło | Установить новый пароль |
| `mail.passwordReset.footer` | Link jest ważny 60 minut. Jeśli to nie Ty prosiłeś o reset, zignoruj tę wiadomość. | Ссылка действует 60 минут. Если вы не запрашивали сброс, просто проигнорируйте это письмо. |

## Frontend

- `app/auth/api/auth-api.ts`: `requestPasswordReset(email)` → `POST /api/auth/password-reset/request`;
  `confirmPasswordReset(token, newPassword)` → `POST /api/auth/password-reset/confirm`. Both public
  (`skipAuthRefresh`, like `login`).
- `app/auth/ForgotPasswordPage.tsx` (`/auth/forgot-password`): zod email schema (reuses
  `auth.login.validation.email*`), submit → neutral `auth.forgot.sent` panel; back-to-login link.
- `app/auth/ResetPasswordPage.tsx` (`/auth/reset-password`): structure of `SetPasswordPage` (token
  from `?token=`, two fields, 8..72 + match via the `auth.setPassword.validation.*` keys); success →
  `navigate('/login', { state: { passwordReset: true } })`; 400 → invalid-link panel with a
  `auth.reset.requestNew` link to `/auth/forgot-password`.
- `LoginPage.tsx`: `auth.login.forgotPassword` link under the password field; when
  `location.state?.passwordReset` show an inline success alert `auth.reset.success`.
- `app/router.tsx`: two lazy public routes next to `/auth/set-password` / `/auth/otp` (outside the
  protected shell; an already-authenticated user is redirected the same way as on the login page).

UI keys (PL / RU):

| Key | PL | RU |
|---|---|---|
| `auth.login.forgotPassword` | Nie pamiętasz hasła? | Забыли пароль? |
| `auth.forgot.title` | Resetowanie hasła | Сброс пароля |
| `auth.forgot.hint` | Podaj e-mail konta — wyślemy link do ustawienia nowego hasła. | Укажите e-mail аккаунта — мы отправим ссылку для установки нового пароля. |
| `auth.forgot.submit` | Wyślij link | Отправить ссылку |
| `auth.forgot.submitting` | Wysyłanie... | Отправка... |
| `auth.forgot.sent` | Jeśli konto istnieje, wysłaliśmy link na podany adres. Link jest ważny 60 minut. | Если аккаунт существует, мы отправили ссылку на указанный адрес. Ссылка действует 60 минут. |
| `auth.forgot.backToLogin` | Wróć do logowania | Вернуться ко входу |
| `auth.reset.title` | Ustaw nowe hasło | Установите новый пароль |
| `auth.reset.password` | Nowe hasło | Новый пароль |
| `auth.reset.confirm` | Powtórz hasło | Повторите пароль |
| `auth.reset.submit` | Zapisz hasło | Сохранить пароль |
| `auth.reset.submitting` | Zapisywanie... | Сохранение... |
| `auth.reset.success` | Hasło zostało zmienione. Zaloguj się nowym hasłem. | Пароль изменён. Войдите с новым паролем. |
| `auth.reset.invalidLink` | Link jest nieprawidłowy lub wygasł. Poproś o nowy. | Ссылка недействительна или истекла. Запросите новую. |
| `auth.reset.requestNew` | Poproś o nowy link | Запросить новую ссылку |

## Error handling

| Case | Backend | UI |
|---|---|---|
| Unknown / INVITED / DEACTIVATED / CLIENT email | 200, nothing sent | neutral `auth.forgot.sent` |
| Invalid / used / expired token | 400 (existing reset-token message) | `auth.reset.invalidLink` + request-new link |
| Password < 8 or > 72 | 400 validation | blocked client-side first |
| Mail transport failure | caught + logged in `AuthService.requestPasswordReset`, 200 (a 500 would reveal the account exists) | neutral message |

## Testing

- Backend: `AuthServicePasswordReset*` unit tests (CLIENT excluded; ACTIVE employee gets a token and
  a mail call with the user), `PasswordResetConfirm` validation (72 max), mail sender test (PL/RU
  subject, link with `?token=` and encoding). Run only the affected `--tests` classes.
- Frontend: Vitest component tests for both pages (validation, neutral message, invalid link, success
  navigation), login link + success alert, route registration, locale parity.
- `test-cases.md`: API (Docker) + UI (browser) scenarios. The local stack relays real SMTP (no mail
  catcher), so scenarios read the issued token from `password_reset_tokens` (latest row for the user)
  instead of an inbox, and test users use `+{run-id}` addresses on a controlled mailbox.
