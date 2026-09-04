import { Navigate, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

import { AppShell } from '@/app/layout/AppShell'
import { captureReturnLocation } from '@/lib/return-location'
import { useAuthStore } from '@/stores/auth-store'

/**
 * AuthLoading — the loading indication shown while Session_Hydration has not
 * yet resolved. Rendering this (rather than redirecting) prevents prematurely
 * sending an as-yet-undetermined session to `/login` (Req 4.5, 9.4).
 */
function AuthLoading() {
  const { t } = useTranslation()
  return (
    <div
      className="flex min-h-screen items-center justify-center bg-background"
      role="status"
      aria-live="polite"
    >
      <span className="text-sm text-muted-foreground">{t('auth.guard.loading')}</span>
    </div>
  )
}

/**
 * ProtectedLayout — the authenticated-only route guard (Auth_Guard) that gates
 * every protected route mounted under `/`.
 *
 * Behavior (Req 9.1–9.5, 4.5, 12.1, 12.7):
 *  - WHILE Session_Hydration is pending → render {@link AuthLoading} rather than
 *    redirecting, so an undetermined session is never bounced to `/login`.
 *  - WHEN unauthenticated → capture the attempted protected location (path plus
 *    query) as the Return_Location and redirect to `/login`.
 *    {@link captureReturnLocation} no-ops for Public_Routes, so a Public_Route
 *    is never used as a return target.
 *  - WHEN authenticated → render {@link AppShell}, which owns its own
 *    `<Outlet/>` for the protected child routes.
 *
 * The guard decides access solely on authentication state; it performs no
 * resource/operation permission checks — those are deferred to
 * FOR-03-07 (Req 9.5, 12.7).
 */
export function ProtectedLayout() {
  const hydrationStatus = useAuthStore((s) => s.hydrationStatus)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const location = useLocation()

  if (hydrationStatus === 'pending') {
    return <AuthLoading />
  }

  if (!isAuthenticated) {
    captureReturnLocation(location.pathname + location.search)
    return <Navigate to="/login" replace />
  }

  return <AppShell />
}
