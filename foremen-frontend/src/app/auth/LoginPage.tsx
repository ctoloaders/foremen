/**
 * LoginPage — employee email + password sign-in at `/login` (FOR-03-06 task 11.1).
 *
 * Behavior:
 *  - Presents an email field, a password field, a submit control, and the
 *    Sign-in-with-Google control (Req 5.1, 14.1).
 *  - react-hook-form + zod validation: email required + format, password
 *    required; invalid input blocks submission with field-level messages and
 *    never calls the backend (Req 5.2).
 *  - On submit calls `Auth_Store.login(email, password)`, which records the
 *    tokens then hydrates the Current_User via `GET /api/auth/me`; on success
 *    navigates to the saved Return_Location (or `/`) via `useAuthRedirect`
 *    (Req 5.3, 5.4).
 *  - Surfaces the server-localized `ApiError.message` at form level on 401
 *    (invalid credentials) and 403 (account not activated / deactivated)
 *    (Req 5.5, 5.6).
 *  - While a login request is in flight the submit control is disabled and a
 *    spinner is shown (Req 5.7).
 *  - If the user is already authenticated, redirects away from `/login` to the
 *    saved Return_Location (or `/`) via `useAuthRedirect` (Req 5.8).
 *  - The GoogleSignInButton's `onError` is wired to the same form-level error
 *    region so a Google failure surfaces alongside password errors (Req 14.1).
 *
 * Accessibility basics are wired here (labels, `role="alert"` error region via
 * AuthCard, `aria-busy`, `aria-describedby`); task 15.2 does a broader pass.
 *
 * NOTE: this must remain a `default` export — the router lazy-imports it as the
 * default (design "Route restructure", task 8.1).
 */
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { z } from 'zod'

import { AuthCard } from '@/app/auth/components/AuthCard'
import { GoogleSignInButton } from '@/app/auth/components/GoogleSignInButton'
import { useAuthRedirect } from '@/app/auth/hooks/useAuthRedirect'
import { ApiError } from '@/lib/api-client'
import { useAuthStore } from '@/stores/auth-store'

/**
 * Login form schema (Req 5.2). Email is required and must be a valid address;
 * password is required (non-empty). Validation messages are i18n keys resolved
 * at render time (matching the app's `t(errors.field.message)` convention).
 */
const loginSchema = z.object({
  email: z
    .string()
    .min(1, 'auth.login.validation.emailRequired')
    .email('auth.login.validation.emailInvalid'),
  password: z.string().min(1, 'auth.login.validation.passwordRequired'),
})

type LoginFormValues = z.infer<typeof loginSchema>

export default function LoginPage() {
  const { t } = useTranslation()
  const redirect = useAuthRedirect()

  const login = useAuthStore((state) => state.login)
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated)
  const hydrationStatus = useAuthStore((state) => state.hydrationStatus)

  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    mode: 'onBlur',
    defaultValues: { email: '', password: '' },
  })

  // Guards the redirect so it fires at most once. Both the already-present
  // session (Req 5.8) and a successful login flip `isAuthenticated` to true,
  // and the redirect is driven from a single place — the effect below — so the
  // Return_Location is consumed exactly once. Calling `redirect()` from both
  // the effect and `onSubmit` previously raced: the second call consumed the
  // already-cleared Return_Location and fell back to `/`, clobbering the
  // navigation to the captured deep link (Req 12.3, 12.4).
  const hasRedirectedRef = useRef(false)

  // Single redirect trigger (Req 5.8, 12.3, 12.4). Runs once hydration has
  // resolved (so an as-yet-undetermined session is never redirected) and the
  // user is authenticated — whether they arrived already authenticated or just
  // completed a login. `consumeReturnLocation()` (inside `redirect`) runs
  // exactly once thanks to the ref guard.
  useEffect(() => {
    if (hydrationStatus === 'done' && isAuthenticated && !hasRedirectedRef.current) {
      hasRedirectedRef.current = true
      redirect()
    }
  }, [hydrationStatus, isAuthenticated, redirect])

  const onSubmit = async (values: LoginFormValues) => {
    setFormError(null)
    try {
      // `login` records the tokens then hydrates the Current_User (Req 5.3,
      // 5.4). The resulting `isAuthenticated` transition drives the single
      // redirect effect above; we intentionally do not call `redirect()` here
      // so the Return_Location is consumed exactly once (Req 12.3, 12.4).
      await login(values.email, values.password)
    } catch (error) {
      // 401 (invalid credentials) / 403 (not activated / deactivated) surface
      // the server-localized message at form level (Req 5.5, 5.6).
      if (error instanceof ApiError) {
        setFormError(error.message)
      } else {
        setFormError(t('auth.error.generic'))
      }
    }
  }

  const errorId = 'login-form-error'

  return (
    <AuthCard
      title={t('auth.login.title')}
      error={formError ?? undefined}
      errorId={errorId}
      footer={
        <GoogleSignInButton onError={(message) => setFormError(message)} />
      }
    >
      <form
        onSubmit={handleSubmit(onSubmit)}
        className="space-y-4"
        aria-describedby={formError ? errorId : undefined}
        noValidate
      >
        {/* Email */}
        <div className="space-y-2">
          <label
            htmlFor="login-email"
            className="text-sm font-medium text-foreground"
          >
            {t('auth.login.email')}
          </label>
          <input
            id="login-email"
            type="email"
            autoComplete="email"
            aria-invalid={errors.email ? true : undefined}
            aria-describedby={errors.email ? 'login-email-error' : undefined}
            {...register('email')}
            className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          />
          {errors.email && (
            <p id="login-email-error" role="alert" className="text-xs text-destructive">
              {t(errors.email.message ?? '')}
            </p>
          )}
        </div>

        {/* Password */}
        <div className="space-y-2">
          <label
            htmlFor="login-password"
            className="text-sm font-medium text-foreground"
          >
            {t('auth.login.password')}
          </label>
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={
              errors.password ? 'login-password-error' : undefined
            }
            {...register('password')}
            className="h-9 w-full rounded-md border border-border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          />
          {errors.password && (
            <p id="login-password-error" role="alert" className="text-xs text-destructive">
              {t(errors.password.message ?? '')}
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
          {isSubmitting ? t('auth.login.submitting') : t('auth.login.submit')}
        </button>
      </form>
    </AuthCard>
  )
}
