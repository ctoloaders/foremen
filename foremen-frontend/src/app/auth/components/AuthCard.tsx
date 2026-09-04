import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * AuthCard: shared layout scaffolding for the auth pages (LoginPage,
 * SetPasswordPage, OtpLoginPage). It renders a centered card container with a
 * title slot, an optional description slot, a children/form slot, and a
 * form-level error region.
 *
 * This is a presentational scaffold and intentionally owns no user-facing
 * strings: callers pass already-translated `title`, `description`, and `error`
 * text (routed through i18n at the call site, matching the app's
 * `useTranslation()` convention). The error region uses `role="alert"` so
 * assistive technology announces form-level backend/validation errors
 * (design "Accessibility & interaction states", Req 11.x).
 */
interface AuthCardProps {
  /** Already-translated page/form title rendered in the card header. */
  title: string
  /** Optional already-translated supporting text under the title. */
  description?: ReactNode
  /**
   * Form-level error message (already-translated / server-localized). When
   * present it is rendered in a `role="alert"` region above the form body.
   */
  error?: ReactNode
  /** The form and its fields. */
  children: ReactNode
  /**
   * Optional footer slot (e.g. secondary actions or the Google sign-in
   * control) rendered below the form body.
   */
  footer?: ReactNode
  /** Optional id linking the error region for `aria-describedby` on a form. */
  errorId?: string
  className?: string
}

export function AuthCard({
  title,
  description,
  error,
  children,
  footer,
  errorId,
  className,
}: Readonly<AuthCardProps>) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 px-4 py-12">
      <div
        className={cn(
          'w-full max-w-md space-y-6 rounded-lg border border-border bg-background p-6 shadow-lg sm:p-8',
          className,
        )}
      >
        <div className="space-y-1.5 text-center">
          <h1 className="text-2xl font-semibold leading-none tracking-tight">{title}</h1>
          {description ? (
            <p className="text-sm text-muted-foreground">{description}</p>
          ) : null}
        </div>

        {error ? (
          <div
            id={errorId}
            role="alert"
            className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {error}
          </div>
        ) : null}

        <div className="space-y-4">{children}</div>

        {footer ? <div className="space-y-4">{footer}</div> : null}
      </div>
    </div>
  )
}
