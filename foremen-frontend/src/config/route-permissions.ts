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
// FOR-04-02: interim guard for the Measurement Units page until the FOR-04-15
// "Dictionaries" menu entry provides its requirement from NAV_CONFIG.
const extra: Record<string, PermissionRequirement> = {
  '/measurement-units': { resource: 'MEASUREMENT_UNITS', operation: 'READ' },
  // FOR-04-03: interim guard for the Currencies page until the FOR-04-15
  // "Dictionaries" menu entry provides its requirement from NAV_CONFIG.
  '/currencies': { resource: 'CURRENCIES', operation: 'READ' },
  // FOR-04-04: interim guard for the VAT Rates page until the FOR-04-15
  // "Dictionaries" menu entry provides its requirement from NAV_CONFIG.
  '/vat-rates': { resource: 'VAT_RATES', operation: 'READ' },
  // FOR-04-05: interim guard for the Room Types page until the FOR-04-15
  // "Dictionaries" menu entry provides its requirement from NAV_CONFIG.
  '/room-types': { resource: 'ROOM_TYPES', operation: 'READ' },
  // FOR-04-06: interim guard for the Work Categories page until the FOR-04-15
  // "Dictionaries" menu entry provides its requirement from NAV_CONFIG.
  '/work-categories': { resource: 'WORK_CATEGORIES', operation: 'READ' },
  // FOR-04-07: interim guard for the Delivery Categories page until the FOR-04-15
  // "Dictionaries" menu entry provides its requirement from NAV_CONFIG.
  '/delivery-categories': { resource: 'DELIVERY_CATEGORIES', operation: 'READ' },
  // FOR-04-08: interim guard for the Delivery Statuses page until the FOR-04-15
  // "Dictionaries" menu entry provides its requirement from NAV_CONFIG.
  '/delivery-statuses': { resource: 'DELIVERY_STATUSES', operation: 'READ' },
}

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
