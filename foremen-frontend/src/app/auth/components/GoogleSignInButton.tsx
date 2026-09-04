import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'

import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api-client'
import {
  GoogleIdentityError,
  requestGoogleIdToken,
} from '@/lib/google-identity'
import * as authApi from '@/app/auth/api/auth-api'
import { useAuthStore } from '@/stores/auth-store'
import { useAuthRedirect } from '@/app/auth/hooks/useAuthRedirect'

/**
 * Backend message code returned by `POST /api/auth/google` when the verified
 * Google email is not linked to any account. Matched on so the client shows the
 * distinct contact-admin message (`auth.google.noAccount`) rather than a
 * generic auth failure (Req 14.9).
 */
const NO_ACCOUNT_CODE = 'error.auth.google.no.account'

interface GoogleSignInButtonProps {
  /**
   * Optional callback invoked whenever the button surfaces (or clears) an
   * error message, letting a parent page render the error in its own form-level
   * alert region. When omitted the button renders the message itself.
   */
  onError?: (message: string | null) => void
  className?: string
}

/**
 * GoogleSignInButton — the Sign-in-with-Google control (design "Google Sign-In
 * (frontend)", Req 14.4–14.14).
 *
 * On click it obtains a Google ID token via {@link requestGoogleIdToken}, then
 * (with the control disabled and a spinner shown — Req 14.13) exchanges it at
 * `POST /api/auth/google` through {@link authApi.googleExchange} and branches on
 * the outcome:
 *
 *  - `200 AUTHENTICATED` → the Google email is linked to an ACTIVE account:
 *    record the JWT pair via `setTokens`, hydrate the identity, and navigate to
 *    the Return_Location (or `/`) via {@link useAuthRedirect} (Req 14.4).
 *  - `200 ACTIVATION_REQUIRED` → the account is INVITED; **no session is issued
 *    and no tokens are stored**. Navigate to
 *    `/auth/set-password?token=<setPasswordToken>` so the single activation gate
 *    (set-password) can run. The captured Return_Location is left untouched so
 *    it survives this second redirect (Req 14.5, 14.6, 14.7, 14.14).
 *  - `403 error.auth.google.no.account` → show the distinct contact-admin
 *    message (`auth.google.noAccount`), not a generic failure (Req 14.9).
 *  - `403` deactivated (any other 403) → surface the server-localized
 *    `ApiError.message`; no token stored (Req 14.8).
 *  - user cancels/dismisses the prompt → return to idle, no error surfaced
 *    (Req 14.10).
 *  - client pre-token failure (script load / init) → generic `auth.google.failed`
 *    message (Req 14.11).
 *  - any other non-`200` → surface `ApiError.message` (Req 14.12).
 *
 * Security invariant: `setTokens` is only ever called on the `AUTHENTICATED`
 * branch; the `ACTIVATION_REQUIRED` and every error branch store no token
 * (Req 14.14).
 */
export function GoogleSignInButton({
  onError,
  className,
}: Readonly<GoogleSignInButtonProps>) {
  const { t } = useTranslation()
  const setTokens = useAuthStore((state) => state.setTokens)
  const refreshIdentity = useAuthStore((state) => state.refreshIdentity)
  const redirect = useAuthRedirect()

  const [loading, setLoading] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  // Surface an error either through the parent (when it owns the alert region)
  // or via the button's own state.
  const emitError = useCallback(
    (message: string | null) => {
      if (onError) {
        onError(message)
      } else {
        setLocalError(message)
      }
    },
    [onError],
  )

  const handleClick = useCallback(async () => {
    if (loading) return
    emitError(null)
    setLoading(true)

    let idToken: string
    try {
      idToken = await requestGoogleIdToken()
    } catch (err) {
      // Pre-token client failure: distinguish a user cancel/dismiss (return to
      // idle, no error) from a genuine script-load/init failure.
      if (err instanceof GoogleIdentityError && err.isCancelled) {
        // Req 14.10: cancel/dismiss → idle, no error surfaced.
        setLoading(false)
        return
      }
      // Req 14.11: any other pre-token client failure → generic message.
      emitError(t('auth.google.failed'))
      setLoading(false)
      return
    }

    try {
      const response = await authApi.googleExchange(idToken)

      if (response.status === 'AUTHENTICATED') {
        // Req 14.4: ACTIVE account — record tokens, hydrate identity, redirect.
        setTokens(response.tokens)
        await refreshIdentity()
        redirect()
        return
      }

      // Req 14.5, 14.6, 14.7, 14.14: ACTIVATION_REQUIRED — INVITED account.
      // Store NO tokens; forward the raw set-password token to the
      // SetPasswordPage. Leave the Return_Location untouched so it survives
      // this second redirect and is consumed on set-password success.
      redirectToSetPassword(response.setPasswordToken)
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 403 && err.code === NO_ACCOUNT_CODE) {
          // Req 14.9: no linked account → distinct contact-admin message.
          emitError(t('auth.google.noAccount'))
        } else {
          // Req 14.8 (deactivated 403) and Req 14.12 (any other non-200):
          // surface the server-localized backend message verbatim.
          emitError(err.message)
        }
      } else {
        // Unexpected non-ApiError failure during the exchange.
        emitError(t('auth.google.failed'))
      }
      // No token is ever stored on an error branch (Req 14.14).
    } finally {
      setLoading(false)
    }
  }, [loading, emitError, t, setTokens, refreshIdentity, redirect])

  return (
    <div className="space-y-2">
      <button
        type="button"
        onClick={() => {
          void handleClick()
        }}
        disabled={loading}
        aria-busy={loading}
        className={cn(
          'inline-flex h-10 w-full items-center justify-center gap-2 rounded-md border border-input bg-background px-4 text-sm font-medium shadow-sm transition-colors',
          'hover:bg-accent hover:text-accent-foreground',
          'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
          'disabled:pointer-events-none disabled:opacity-50',
          className,
        )}
      >
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        ) : (
          <GoogleGlyph />
        )}
        {t('auth.google.button')}
      </button>

      {onError == null && localError != null ? (
        <p role="alert" className="text-sm text-destructive">
          {localError}
        </p>
      ) : null}
    </div>
  )
}

/**
 * Navigates to the SetPasswordPage with the freshly-minted set-password token,
 * using a full-document assign so no auth session/state is implied. Kept as a
 * small seam so the ACTIVATION_REQUIRED branch never touches the Auth_Store.
 */
function redirectToSetPassword(setPasswordToken: string): void {
  const target = `/auth/set-password?token=${encodeURIComponent(setPasswordToken)}`
  window.location.assign(target)
}

/** The Google "G" mark rendered on the button when not loading. */
function GoogleGlyph() {
  return (
    <svg
      className="h-4 w-4"
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      <path
        fill="#4285F4"
        d="M23.52 12.273c0-.851-.076-1.67-.218-2.455H12v4.642h6.458a5.52 5.52 0 0 1-2.394 3.622v3.01h3.878c2.269-2.09 3.578-5.166 3.578-8.82z"
      />
      <path
        fill="#34A853"
        d="M12 24c3.24 0 5.956-1.075 7.942-2.908l-3.878-3.01c-1.075.72-2.45 1.145-4.064 1.145-3.125 0-5.77-2.11-6.714-4.946H1.276v3.11A11.997 11.997 0 0 0 12 24z"
      />
      <path
        fill="#FBBC05"
        d="M5.286 14.281A7.212 7.212 0 0 1 4.91 12c0-.79.136-1.558.376-2.281v-3.11H1.276A11.997 11.997 0 0 0 0 12c0 1.936.464 3.769 1.276 5.391l4.01-3.11z"
      />
      <path
        fill="#EA4335"
        d="M12 4.773c1.762 0 3.344.606 4.59 1.796l3.442-3.442C17.951 1.19 15.235 0 12 0A11.997 11.997 0 0 0 1.276 6.609l4.01 3.11C6.23 6.883 8.875 4.773 12 4.773z"
      />
    </svg>
  )
}
