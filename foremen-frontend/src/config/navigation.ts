export interface NavItemConfig {
  path: string
  labelKey: string
  /** Optional override for the TopBar page title. Falls back to labelKey when not set. */
  titleKey?: string
  icon: string
  bottomNav: boolean
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
      { path: '/projects', labelKey: 'nav.projects', icon: 'folder-kanban', bottomNav: true },
      { path: '/rooms', labelKey: 'nav.rooms', icon: 'door-open', bottomNav: false },
      { path: '/estimate', labelKey: 'nav.estimate', icon: 'calculator', bottomNav: false },
    ],
  },
  {
    titleKey: 'nav.sections.warehouse',
    items: [
      { path: '/materials', labelKey: 'nav.materials', icon: 'package', bottomNav: true },
      { path: '/finances', labelKey: 'nav.finances', icon: 'wallet', bottomNav: true },
      { path: '/deliveries', labelKey: 'nav.deliveries', icon: 'truck', bottomNav: false },
    ],
  },
  {
    titleKey: 'nav.sections.system',
    items: [
      { path: '/users', labelKey: 'nav.users', icon: 'users', bottomNav: true },
      { path: '/roles', labelKey: 'nav.roles', titleKey: 'roles.pageTitle', icon: 'shield', bottomNav: false },
      { path: '/audit', labelKey: 'nav.audit', titleKey: 'audit.pageTitle', icon: 'scroll-text', bottomNav: false },
    ],
  },
]
