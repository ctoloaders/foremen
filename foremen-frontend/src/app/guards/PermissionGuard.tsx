import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'

import { requirementForPath } from '@/config/route-permissions'
import { usePermission } from '@/hooks/usePermission'
import { recordAllowedLocation, recordDeniedLocation } from '@/lib/last-allowed-location'

/**
 * PermissionGuard — the Permission_Route_Guard (FOR-03-07).
 *
 * A pathless layout-route element composed *inside* the authenticated branch of
 * `ProtectedLayout` → `AppShell`. It gates deep-linked protected routes against
 * the Route_Requirement_Map and either renders the matched child route or
 * redirects to `/403`.
 *
 * Behavior:
 *  - Reads the currently matched location (`pathname` + `search`) and looks up
 *    its Permission_Requirement via {@link requirementForPath}.
 *  - GRANTS (renders `<Outlet/>`) when the path has no requirement OR
 *    `usePermission().hasPermission(...)` grants it (Req 4.3, 4.4, 4.6). The
 *    ADMIN bypass and deny-by-default rules live entirely in `usePermission`.
 *  - DENIES (redirects to `/403` with `replace`) otherwise (Req 4.2, 4.7).
 *
 * It performs NO authentication check: it is only ever mounted under
 * `ProtectedLayout`'s authenticated render, so anonymous users are already
 * redirected to `/login` upstream (Req 4.5). The `/403` redirect uses
 * `<Navigate replace>` and never writes a FOR-03-06 Return_Location (that is
 * captured solely by `ProtectedLayout` when unauthenticated), so a forbidden
 * route can never become a post-login return target and Back does not
 * re-trigger the denial (Req 4.7).
 *
 * As a side effect (not during render) it records the resolved location in the
 * Last_Allowed_Location module: the granted route as the Last_Allowed_Location
 * (the module ignores `/403`, Req 4.4) or the denied route as the
 * Denied_Location (Req 4.8), so the Forbidden_Page's Go_Back control can return
 * the user to a page they could actually view.
 */
export function PermissionGuard() {
  const { pathname, search } = useLocation()
  const { hasPermission } = usePermission()

  const requirement = requirementForPath(pathname)
  const granted =
    requirement == null || hasPermission(requirement.resource, requirement.operation)

  useEffect(() => {
    const loc = pathname + search
    if (granted) {
      recordAllowedLocation(loc) // Req 4.3, 4.4, 4.6 (module ignores /403)
    } else {
      recordDeniedLocation(loc) // Req 4.8
    }
  }, [granted, pathname, search])

  if (!granted) {
    // Deny → /403, replace (keeps the forbidden URL out of history), never sets
    // a Return_Location (Req 4.2, 4.7).
    return <Navigate to="/403" replace />
  }

  // Grant / ADMIN / no-requirement → render the matched child route.
  return <Outlet />
}
