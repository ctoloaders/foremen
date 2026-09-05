import { NAV_CONFIG, type PermissionRequirement } from '@/config/navigation'

/**
 * Route_Requirement_Map (FOR-03-07).
 *
 * Maps a route path to the Permission_Requirement the user must be granted to
 * reach it. Derived from `NAV_CONFIG` so a menu item and its route always share
 * one requirement (Req 4.1), plus an `extra` map for guarded routes that are
 * not menu items (e.g. future detail routes).
 *
 * Any path not present here — including `/403` — resolves to `undefined`, i.e.
 * no requirement: authenticated access is sufficient (Req 4.4, 5.4).
 */

const fromNav: Record<string, PermissionRequirement> = Object.fromEntries(
  NAV_CONFIG.flatMap((section) => section.items)
    .filter((item) => item.requiredPermission != null)
    .map((item) => [item.path, item.requiredPermission as PermissionRequirement]),
)

// Extra non-menu guarded routes can be added here later (e.g. detail routes).
const extra: Record<string, PermissionRequirement> = {}

const ROUTE_REQUIREMENTS: Record<string, PermissionRequirement> = {
  ...fromNav,
  ...extra,
}

/**
 * Resolve the Permission_Requirement for a route path.
 *
 * Returns `undefined` for unmapped paths and for `/403`, meaning no permission
 * check is required (authenticated access is sufficient).
 */
export function requirementForPath(pathname: string): PermissionRequirement | undefined {
  return ROUTE_REQUIREMENTS[pathname]
}
