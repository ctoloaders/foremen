import { useMemo } from 'react'

import type { CurrentUser } from '@/stores/auth-store'
import { useAuthStore } from '@/stores/auth-store'

/**
 * The exact literal role code that the backend `ForemenPermissionEvaluator`
 * (FOR-03-03) grants every permission without consulting the matrix. The client
 * mirrors this precisely — no case-folding, no trimming — so the UI shows
 * exactly what the server allows.
 */
const ADMIN_ROLE_CODE = 'ADMIN'

/**
 * The access requirement a navigation item or route declares: the
 * `(resource, operation)` pair the user must be granted to see/reach it.
 *
 * Co-located with the Permission_Hook as a convenient re-export point; the
 * navigation config and Route_Requirement_Map re-export or reuse this shape.
 */
export interface PermissionRequirement {
  resource: string
  operation: string
}

/**
 * Builds the `"<RESOURCE>:<OPERATION>"` membership key, mirroring the backend
 * `PermissionSet` key convention (FOR-03-03).
 */
function permissionKey(resource: string, operation: string): string {
  return `${resource}:${operation}`
}

/**
 * Flattens a Current_User's `permissions` array into an O(1)-lookup membership
 * `Set` of `"<RESOURCE>:<OPERATION>"` keys. Returns an empty set when there is
 * no user (deny-by-default).
 */
function buildGrantSet(user: CurrentUser | null): Set<string> {
  const grants = new Set<string>()
  if (user == null) return grants
  for (const entry of user.permissions) {
    for (const op of entry.operations) {
      grants.add(permissionKey(entry.resource, op))
    }
  }
  return grants
}

export interface UsePermissionResult {
  hasPermission: (resource: string, operation: string) => boolean
}

/**
 * The single client-side authorization predicate for the Foremen web client.
 *
 * Reads the current `Current_User` from the Auth_Store (FOR-03-06) and returns
 * `hasPermission(resource, operation)`, mirroring the backend
 * `ForemenPermissionEvaluator` semantics:
 *
 * - No `Current_User` (unauthenticated or mid-hydration) → `false` for every
 *   pair (deny-by-default).
 * - `roleCode` equal to the exact literal `ADMIN` → `true` for every pair
 *   without inspecting the `permissions` array (ADMIN bypass, exact match only:
 *   no case-fold / trim).
 * - Otherwise → exact-match membership in the flattened grant set.
 *
 * Subscribing to `s.user` (not the whole store) means the predicate re-evaluates
 * exactly when the user identity/permissions change, so a re-hydration that
 * reflects a role or permission change is picked up on the next render.
 */
export function usePermission(): UsePermissionResult {
  const user = useAuthStore((s) => s.user)

  // ADMIN bypass by EXACT literal match (no case-fold / trim), mirroring
  // ForemenPermissionEvaluator (FOR-03-03).
  const isAdmin = user?.roleCode === ADMIN_ROLE_CODE

  // Flatten once, memoized on the user identity, so repeated calls within a
  // render are O(1) and the set is rebuilt only when the user changes.
  const grants = useMemo(() => buildGrantSet(user), [user])

  const hasPermission = useMemo(() => {
    return (resource: string, operation: string): boolean => {
      if (user == null) return false // deny-by-default (no Current_User)
      if (isAdmin) return true // ADMIN bypass
      return grants.has(permissionKey(resource, operation)) // exact-match membership
    }
  }, [user, isAdmin, grants])

  return { hasPermission }
}
