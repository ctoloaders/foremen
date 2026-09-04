/**
 * OtpLoginPage — client passwordless OTP sign-in at `/auth/otp` (FOR-03-06 task 13.4).
 *
 * A two-step state machine (design "OTP state machine"):
 *  - Email step: a non-empty email calls `authApi.otpRequest` and, on `200`,
 *    ALWAYS advances to the code step regardless of eligibility (mirroring the
 *    backend anti-enumeration silent success, Req 7.2). An empty email is
 *    blocked with a field-level validation message (Req 7.3).
 *  - Code step: retains the email, shows a masked-email echo (Req 7.16), uses
 *    the segmented {@link OtpCodeInput} plus an explicit verify control
 *    (Req 7.4, 7.8), verifies six digits via `authApi.otpVerify` (Req 7.9,
 *    7.10). On `200` it auto-logs-in (`setTokens` + forced `/me` via
 *    `refreshIdentity`) and navigates via {@link useAuthRedirect} (Req 7.11);
 *    on `400` it shows an inline error and stays on the code step (Req 7.12);
 *    on `429` it shows the rate-limited message (Req 7.13). A back-to-email
 *    control (Req 7.15) and a resend control with an
 *    `OTP_RESEND_COOLDOWN_SECONDS` cooldown (Req 7.17–7.19) are provided.
 *
 * While a request is in flight the active submit control is disabled and shows
 * a spinner (Req 7.14). All user-facing strings are routed through i18n
 * (`auth.otp.*`) and forms/controls carry accessibility affordances
 * (labels, `role="alert"` errors, `aria-busy`) (Req 10.x, 11.x).
 */
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'

import { AuthCard } from '@/app/auth/components/AuthCard'
import { OtpCodeInput } from '@/app/auth/components/OtpCodeInput'
import { useAuthRedirect } from '@/app/auth/hooks/useAuthRedirect'
import * as authApi from '@/app/auth/api/auth-api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/lib/api-client'
import { useAuthStore } from '@/stores/auth-store'

/**
 * Resend cooldown in seconds, configurable per the design
 * ("Resend cooldown default 60 seconds, exposed as a configurable constant").
 * Default 60 (Req 7.17–7.19).
 */
export const OTP_RESEND_COOLDOWN_SECONDS = 60

/** Number of decimal digits in a complete one-time code. */
const OTP_LENGTH = 6

/** The two steps of the OTP sign-in flow. */
type OtpStep = 'email' | 'code'

/**
 * Masks the local part of an email for the code-step echo so the user can
 * confirm the destination without the full address being shown (Req 7.16).
 * `alice@example.com` -> `a***e@example.com`; short local parts fall back to a
 * single leading character plus a fixed mask.
 */
function maskEmail(email: string): string {
  const at = email.indexOf('@')
  if (at <= 0) return email
  const local = email.slice(0, at)
  const domain = email.slice(at)
  if (local.length <= 2) {
    return `${local[0]}***${domain}`
  }
  // eslint-disable-next-line unicorn/prefer-at -- String.prototype.at is not in the configured TS lib target
  const lastLocalChar = local[local.length - 1]
  return `${local[0]}***${lastLocalChar}${domain}`
}

export default function OtpLoginPage() {
  const { t } = useTranslation()
  const redirect = useAuthRedirect()
  const setTokens = useAuthStore((s) => s.setTokens)
  const refreshIdentity = useAuthStore((s) => s.refreshIdentity)

  const [step, setStep] = useState<OtpStep>('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')

  // Field- and form-level messages, kept separate so the email-step validation
  // and the code-step backend error surface in the correct place.
  const [emailError, setEmailError] = useState<string | null>(null)
  const [codeError, setCodeError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  const [inFlight, setInFlight] = useState(false)
  const [cooldown, setCooldown] = useState(0)

  // Guards a single auto-submit per completed code so onComplete + the explicit
  // verify button can never fire two concurrent verify requests (Req 7.7, 7.8).
  const verifiedCodeRef = useRef<string | null>(null)

  const emailInputId = useId()
  const emailErrorId = useId()
  const codeErrorId = useId()
  const emailFormErrorId = useId()
  const codeFormErrorId = useId()

  // Countdown timer for the resend cooldown: ticks once per second while active
  // and re-enables the control at zero (Req 7.18).
  useEffect(() => {
    if (cooldown <= 0) return
    const id = window.setInterval(() => {
      setCooldown((remaining) => (remaining <= 1 ? 0 : remaining - 1))
    }, 1000)
    return () => window.clearInterval(id)
  }, [cooldown])

  /**
   * Translates a rejected auth call into a user-facing message. Backend
   * `ApiError`s carry a server-localized message (surfaced verbatim, Req 10.3
   * / 7.12 / 7.13); anything else falls back to the generic i18n string.
   */
  const messageFor = useCallback(
    (error: unknown): string =>
      error instanceof ApiError ? error.message : t('auth.error.generic'),
    [t],
  )

  /**
   * Issues `POST /api/auth/otp/request` for the retained email and, on success,
   * advances to (or stays on) the code step and starts the resend cooldown.
   * Used by both the initial email submit and the resend control (Req 7.2,
   * 7.19). Errors surface at form level; a `429` shows the backend
   * rate-limited message (Req 7.13).
   */
  const requestCode = useCallback(async () => {
    setInFlight(true)
    setFormError(null)
    try {
      await authApi.otpRequest(email)
      // Anti-enumeration: always advance on 200 regardless of eligibility.
      verifiedCodeRef.current = null
      setStep('code')
      setCode('')
      setCodeError(null)
      setCooldown(OTP_RESEND_COOLDOWN_SECONDS)
    } catch (error) {
      setFormError(messageFor(error))
    } finally {
      setInFlight(false)
    }
  }, [email, messageFor])

  /** Email step submit: block an empty email, otherwise request a code. */
  const handleEmailSubmit = useCallback(
    (event: FormEvent) => {
      event.preventDefault()
      if (inFlight) return
      if (email.trim() === '') {
        setEmailError(t('auth.otp.validation.emailRequired'))
        return
      }
      setEmailError(null)
      void requestCode()
    },
    [email, inFlight, requestCode, t],
  )

  /**
   * Verifies the entered code via `POST /api/auth/otp/verify`. Shared by
   * auto-submit (all six boxes filled) and the explicit verify control. Blocks
   * a code that is not exactly six digits (Req 7.9). On `200` auto-logs-in and
   * redirects (Req 7.11); a `400`/`429` stays on the code step with an inline
   * message (Req 7.12, 7.13).
   */
  const verifyCode = useCallback(
    async (candidate: string) => {
      if (inFlight) return
      if (candidate.length !== OTP_LENGTH) {
        setCodeError(t('auth.otp.validation.codeLength'))
        return
      }
      setInFlight(true)
      setCodeError(null)
      setFormError(null)
      try {
        const tokens = await authApi.otpVerify(email, candidate)
        setTokens(tokens)
        await refreshIdentity()
        redirect()
      } catch (error) {
        // Allow the same (or a corrected) code to be re-submitted after a
        // failure by clearing the auto-submit guard.
        verifiedCodeRef.current = null
        setCodeError(messageFor(error))
      } finally {
        setInFlight(false)
      }
    },
    [email, inFlight, messageFor, redirect, refreshIdentity, setTokens, t],
  )

  /**
   * Auto-submit handler passed to {@link OtpCodeInput}. Fires once per distinct
   * completed code; the guard prevents a duplicate submit if the component
   * re-invokes `onComplete` for the same value (Req 7.7).
   */
  const handleComplete = useCallback(
    (fullCode: string) => {
      if (inFlight) return
      if (verifiedCodeRef.current === fullCode) return
      verifiedCodeRef.current = fullCode
      void verifyCode(fullCode)
    },
    [inFlight, verifyCode],
  )

  /** Explicit verify control (keyboard/AT operable, Req 7.8). */
  const handleVerifySubmit = useCallback(
    (event: FormEvent) => {
      event.preventDefault()
      verifiedCodeRef.current = code
      void verifyCode(code)
    },
    [code, verifyCode],
  )

  /** Back-to-email control: return to the first step to request a new code (Req 7.15). */
  const handleBack = useCallback(() => {
    if (inFlight) return
    setStep('email')
    setCode('')
    setCodeError(null)
    setFormError(null)
    verifiedCodeRef.current = null
  }, [inFlight])

  /** Resend control: re-issue the request for the retained email (Req 7.19). */
  const handleResend = useCallback(() => {
    if (inFlight || cooldown > 0) return
    void requestCode()
  }, [cooldown, inFlight, requestCode])

  if (step === 'email') {
    return (
      <AuthCard
        title={t('auth.otp.emailStep.title')}
        error={formError ?? undefined}
        errorId={emailFormErrorId}
      >
        <form
          className="space-y-4"
          onSubmit={handleEmailSubmit}
          aria-describedby={formError ? emailFormErrorId : undefined}
          noValidate
        >
          <div className="space-y-1.5">
            <label htmlFor={emailInputId} className="text-sm font-medium">
              {t('auth.otp.emailStep.email')}
            </label>
            <Input
              id={emailInputId}
              type="email"
              autoComplete="email"
              inputMode="email"
              value={email}
              disabled={inFlight}
              aria-invalid={emailError != null}
              aria-describedby={emailError != null ? emailErrorId : undefined}
              onChange={(event) => {
                setEmail(event.target.value)
                if (emailError != null) setEmailError(null)
              }}
            />
            {emailError != null ? (
              <p id={emailErrorId} role="alert" className="text-sm text-destructive">
                {emailError}
              </p>
            ) : null}
          </div>

          <Button type="submit" className="w-full" disabled={inFlight} aria-busy={inFlight}>
            {inFlight ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
            {t('auth.otp.emailStep.requestCode')}
          </Button>
        </form>
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title={t('auth.otp.codeStep.title')}
      description={t('auth.otp.codeStep.sentTo', { email: maskEmail(email) })}
      error={formError ?? undefined}
      errorId={codeFormErrorId}
    >
      <form
        className="space-y-4"
        onSubmit={handleVerifySubmit}
        aria-describedby={formError ? codeFormErrorId : undefined}
        noValidate
      >
        <div className="space-y-1.5">
          <OtpCodeInput
            value={code}
            onChange={(next) => {
              setCode(next)
              if (codeError != null) setCodeError(null)
            }}
            onComplete={handleComplete}
            disabled={inFlight}
            aria-invalid={codeError != null}
            aria-describedby={codeError != null ? codeErrorId : undefined}
            className="justify-center"
          />
          {codeError != null ? (
            <p id={codeErrorId} role="alert" className="text-center text-sm text-destructive">
              {codeError}
            </p>
          ) : null}
        </div>

        <Button
          type="submit"
          className="w-full"
          disabled={inFlight || code.length !== OTP_LENGTH}
          aria-busy={inFlight}
        >
          {inFlight ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
          {inFlight ? t('auth.otp.codeStep.verifying') : t('auth.otp.codeStep.verify')}
        </Button>

        <div className="flex items-center justify-between">
          <Button type="button" variant="link" className="px-0" onClick={handleBack} disabled={inFlight}>
            {t('auth.otp.codeStep.back')}
          </Button>
          <Button
            type="button"
            variant="link"
            className="px-0"
            onClick={handleResend}
            disabled={inFlight || cooldown > 0}
          >
            {cooldown > 0 ? t('auth.otp.resendIn', { seconds: cooldown }) : t('auth.otp.resend')}
          </Button>
        </div>
      </form>
    </AuthCard>
  )
}
