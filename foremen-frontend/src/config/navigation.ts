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
