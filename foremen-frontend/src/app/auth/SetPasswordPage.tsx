/**
 * SetPasswordPage — invite / Google-bridge activation at `/auth/set-password`
 * (FOR-03-06 task 12.1).
 *
 * Reads the activation `token` from the `?token=` query parameter. The token
 * may originate either from a FOR-03-02 invitation email or from the Google
 * `ACTIVATION_REQUIRED` bridge (Requirement 14) — both are the same FOR-03-02
 * invite/set-password token and are handled identically here (Req 6.1, 14.6).
 *
 * Behavior:
 *  - Missing/empty token → show an invalid-link message and never submit
 *    (Req 6.2).
 *  - Fields: new password + confirm password, validated with zod (8..72 chars,
 *    confirm must match) (Req 6.3, 6.4, 6.5).
 *  - On submit call `authApi.setPassword(token, password)` (Req 6.6).
 *  - On 200 auto-login: record the returned tokens (`setTokens`), force an
 *    identity refresh (`refreshIdentity`), then navigate via `useAuthRedirect`
 *    (Return_Location or `/`) (Req 6.7, 12.8).
 *  - On 400 surface the localized `ApiError.message` at form level and stay on
 *    the page (Req 6.8).
 *  - While in flight disable the submit control and show a spinner (Req 6.9).
 *
 * Accessibility basics are wired here (labels, `role="alert"` error region via
 * AuthCard, `aria-busy`, `aria-describedby`); task 15.2 does a broader pass.
 */
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { Loader2 } from 'lucide-react'
import { z } from 'zod'

import { AuthCard } from '@/app/auth/components/AuthCard'
import { useAuthRedirect } from '@/app/auth/hooks/useAuthRedirect'
import * as authApi from '@/app/auth/api/auth-api'
import { ApiError } from '@/lib/api-client'
import { useAuthStore } from '@/stores/auth-store'

/**
 * Password policy mirrors the backend (`8..72` chars, Req 6.4) and the
 * confirm-match rule (Req 6.5). Validation messages are i18n keys resolved at
 * render time (matching the app's `t(errors.field.message)` convention).
 */
const setPasswordSchema = z
  .object({
    password: z
      .string()
      .min(8, 'auth.setPassword.validation.length')
      .max(72, 'auth.setPassword.validation.length'),
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'auth.setPassword.validation.mismatch',
  })

type SetPasswordFormValues = z.infer<typeof setPasswordSchema>

export default function SetPasswordPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const redirect = useAuthRedirect()

  const setTokens = useAuthStore((state) => state.setTokens)
  const refreshIdentity = useAuthStore((state) => state.refreshIdentity)

  // Activation token from `?token=`; normalize an empty string to null so a
  // blank value is treated as "missing" (Req 6.1, 6.2).
  const rawToken = searchParams.get('token')
  const token = rawToken != null && rawToken !== '' ? rawToken : null

  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<SetPasswordFormValues>({
    resolver: zodResolver(setPasswordSchema),
    mode: 'onBlur',
    defaultValues: { password: '', confirmPassword: '' },
  })

  const onSubmit = async (values: SetPasswordFormValues) => {
    // Guard: never submit without a token (Req 6.2). The submit control is not
    // rendered when the token is missing, so this is defensive.
    if (token == null) return

    setFormError(null)
    try {
      const tokens = await authApi.setPassword(token, values.password)
      // Auto-login: record tokens then force an identity refresh (Req 6.7).
      setTokens(tokens)
      await refreshIdentity()
      redirect()
    } catch (error) {
      // 400 (invalid / expired / used token) surfaces the server-localized
      // message at form level; the user stays on the page (Req 6.8).
      if (error instanceof ApiError) {
        setFormError(error.message)
      } else {
        setFormError(t('auth.error.generic'))
      }
    }
  }

  // Missing/empty token → invalid-link message, no form (Req 6.2).
  if (token == null) {
    return (
      <AuthCard
        title={t('auth.setPassword.title')}
        error={t('auth.setPassword.invalidLink')}
        errorId="set-password-invalid-link"
      >
        <div />
      </AuthCard>
    )
  }

  const errorId = 'set-password-form-error'

  return (
    <AuthCard
      title={t('auth.setPassword.title')}
      error={formError ?? undefined}
      errorId={errorId}
    >
      <form
        onSubmit={handleSubmit(onSubmit)}
        className="space-y-4"
        aria-describedby={formError ? errorId : undefined}
        noValidate
      >
        {/* New password */}
        <div className="space-y-2">
          <label
            htmlFor="new-password"
            className="text-sm font-medium text-foreground"
          >
            {t('auth.setPassword.newPassword')}
          </label>
          <input
            id="new-password"
            type="password"
            autoComplete="new-password"
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={errors.password ? 'new-password-error' : undefined}
            {...register('password')}
            className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          />
          {errors.password && (
            <p id="new-password-error" role="alert" className="text-xs text-destructive">
              {t(errors.password.message ?? '')}
            </p>
          )}
        </div>

        {/* Confirm password */}
        <div className="space-y-2">
          <label
            htmlFor="confirm-password"
            className="text-sm font-medium text-foreground"
          >
            {t('auth.setPassword.confirmPassword')}
          </label>
          <input
            id="confirm-password"
            type="password"
            autoComplete="new-password"
            aria-invalid={errors.confirmPassword ? true : undefined}
            aria-describedby={
              errors.confirmPassword ? 'confirm-password-error' : undefined
            }
            {...register('confirmPassword')}
            className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          />
          {errors.confirmPassword && (
            <p id="confirm-password-error" role="alert" className="text-xs text-destructive">
              {t(errors.confirmPassword.message ?? '')}
            </p>
          )}
        </div>

        <button
          type="submit"
          disabled={isSubmitting}
          aria-busy={isSubmitting}
          className="inline-flex h-9 w-full items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
        >
          {isSubmitting && <Loader2 className="h-4 w-4 animate-spin" />}
          {isSubmitting
            ? t('auth.setPassword.submitting')
            : t('auth.setPassword.submit')}
        </button>
      </form>
    </AuthCard>
  )
}
