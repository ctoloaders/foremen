import type { PermissionRequirement } from '@/hooks/usePermission'

// Re-export so the Route_Requirement_Map and guards can import the shape from
// the navigation config (the single source of nav requirements) without
// duplicating the type. Defined once in the Permission_Hook (FOR-03-07).
export type { PermissionRequirement }

export interface NavItemConfig {
  path: string
  labelKey: string
  /** Optional override for the TopBar page title. Falls back to labelKey when not set. */
  titleKey?: string
  icon: string
  bottomNav: boolean
  /**
   * The `(resource, operation)` the user must be granted to see this item.
   * When absent, the item is visible to any authenticated user.
   */
  requiredPermission?: PermissionRequirement
}

export interface NavSectionConfig {
  titleKey: string | null
  items: NavItemConfig[]
}

export const NAV_CONFIG: NavSectionConfig[] = [
  {
    titleKey: null,
    items: [
      { path: '/', labelKey: 'nav.dashboard', icon: 'layout-dashboard', bottomNav: true },
      {
        path: '/projects',
        labelKey: 'nav.projects',
        icon: 'folder-kanban',
        bottomNav: true,
        requiredPermission: { resource: 'PROJECTS', operation: 'READ' },
      },
      {
        path: '/rooms',
        labelKey: 'nav.rooms',
        icon: 'door-open',
        bottomNav: false,
        requiredPermission: { resource: 'ROOMS', operation: 'READ' },
      },
      {
        path: '/estimate',
        labelKey: 'nav.estimate',
        icon: 'calculator',
        bottomNav: false,
        requiredPermission: { resource: 'ESTIMATE', operation: 'READ' },
      },
    ],
  },
  {
    titleKey: 'nav.sections.catalog',
    items: [
      {
        path: '/catalog/works',
        labelKey: 'nav.workCatalog',
        icon: 'book-open',
        bottomNav: false,
        requiredPermission: { resource: 'WORK_CATALOG', operation: 'READ' },
      },
      {
        path: '/catalog/prices',
        labelKey: 'nav.workPrices',
        icon: 'tag',
        bottomNav: false,
        requiredPermission: { resource: 'WORK_PRICES', operation: 'READ' },
      },
    ],
  },
  {
    titleKey: 'nav.sections.warehouse',
    items: [
      {
        path: '/materials',
        labelKey: 'nav.materials',
        icon: 'package',
        bottomNav: true,
        requiredPermission: { resource: 'MATERIALS', operation: 'READ' },
      },
      {
        path: '/finances',
        labelKey: 'nav.finances',
        icon: 'wallet',
        bottomNav: true,
        requiredPermission: { resource: 'FINANCES', operation: 'READ' },
      },
      {
        path: '/deliveries',
        labelKey: 'nav.deliveries',
        icon: 'truck',
        bottomNav: false,
        requiredPermission: { resource: 'DELIVERIES', operation: 'READ' },
      },
    ],
  },
  {
    titleKey: 'nav.sections.dictionaries',
    items: [
      {
        path: '/measurement-units',
        labelKey: 'nav.measurementUnits',
        icon: 'ruler',
        bottomNav: false,
        requiredPermission: { resource: 'MEASUREMENT_UNITS', operation: 'READ' },
      },
      {
        path: '/currencies',
        labelKey: 'nav.currencies',
        icon: 'coins',
        bottomNav: false,
        requiredPermission: { resource: 'CURRENCIES', operation: 'READ' },
      },
      {
        path: '/vat-rates',
        labelKey: 'nav.vatRates',
        icon: 'percent',
        bottomNav: false,
        requiredPermission: { resource: 'VAT_RATES', operation: 'READ' },
      },
      {
        path: '/room-types',
        labelKey: 'nav.roomTypes',
        icon: 'layout-grid',
        bottomNav: false,
        requiredPermission: { resource: 'ROOM_TYPES', operation: 'READ' },
      },
      {
        path: '/work-categories',
        labelKey: 'nav.workCategories',
        icon: 'list-tree',
        bottomNav: false,
        requiredPermission: { resource: 'WORK_CATEGORIES', operation: 'READ' },
      },
      {
        path: '/delivery-categories',
        labelKey: 'nav.deliveryCategories',
        icon: 'boxes',
        bottomNav: false,
        requiredPermission: { resource: 'DELIVERY_CATEGORIES', operation: 'READ' },
      },
      {
        path: '/delivery-statuses',
        labelKey: 'nav.deliveryStatuses',
        icon: 'list-checks',
        bottomNav: false,
        requiredPermission: { resource: 'DELIVERY_STATUSES', operation: 'READ' },
      },
      {
        path: '/material-categories',
        labelKey: 'nav.materialCategories',
        icon: 'layers',
        bottomNav: false,
        requiredPermission: { resource: 'MATERIAL_CATEGORIES', operation: 'READ' },
      },
      {
        path: '/offer-packages',
        labelKey: 'nav.offerPackages',
        icon: 'package-2',
        bottomNav: false,
        requiredPermission: { resource: 'OFFER_PACKAGES', operation: 'READ' },
      },
    ],
  },
  {
    titleKey: 'nav.sections.system',
    items: [
      {
        path: '/users',
        labelKey: 'nav.users',
        titleKey: 'users.pageTitle',
        icon: 'users',
        bottomNav: true,
        requiredPermission: { resource: 'USERS', operation: 'READ' },
      },
      {
        path: '/roles',
        labelKey: 'nav.roles',
        titleKey: 'roles.pageTitle',
        icon: 'shield',
        bottomNav: false,
        requiredPermission: { resource: 'ROLES', operation: 'READ' },
      },
      {
        path: '/audit',
        labelKey: 'nav.audit',
        titleKey: 'audit.pageTitle',
        icon: 'scroll-text',
        bottomNav: false,
        requiredPermission: { resource: 'AUDIT', operation: 'READ' },
      },
    ],
  },
  {
    titleKey: 'nav.sections.settings',
    items: [
      { path: '/settings/appearance', labelKey: 'nav.settings.appearance', icon: 'palette', bottomNav: false },
    ],
  },
]

/**
 * The single shared visibility rule for a navigation item (FOR-03-07).
 *
 * An item is visible when it has no `requiredPermission`, or when the supplied
 * `hasPermission` predicate grants the item's requirement. `Sidebar`, `Drawer`,
 * and `BottomNav` all route through this one helper so the filtering rule
 * cannot drift between the three surfaces.
 *
 * @param item the navigation item to test
 * @param hasPermission the Permission_Hook predicate `(resource, operation) => boolean`
 */
export function isNavItemVisible(
  item: NavItemConfig,
  hasPermission: (resource: string, operation: string) => boolean
): boolean {
  if (item.requiredPermission == null) return true
  return hasPermission(item.requiredPermission.resource, item.requiredPermission.operation)
}
