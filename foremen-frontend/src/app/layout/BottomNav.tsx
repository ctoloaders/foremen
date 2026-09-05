import { useLocation } from 'react-router-dom'
import { NAV_CONFIG, isNavItemVisible } from '@/config/navigation'
import { NavItem } from '@/app/layout/NavItem'
import { usePermission } from '@/hooks/usePermission'

/**
 * Mobile bottom navigation bar.
 * Renders only items from NAV_CONFIG that have bottomNav: true AND are visible
 * to the current user by permission (up to 5 items, by permission).
 * Fixed at the bottom of the screen with z-50 to stay above content.
 */
export function BottomNav() {
  const { pathname } = useLocation()
  const { hasPermission } = usePermission()

  const bottomNavItems = NAV_CONFIG.flatMap((section) => section.items)
    .filter((item) => item.bottomNav)
    .filter((item) => isNavItemVisible(item, hasPermission))

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-50 flex h-16 items-center justify-around border-t border-border bg-background">
      {bottomNavItems.map((item) => (
        <NavItem
          key={item.path}
          icon={item.icon}
          labelKey={item.labelKey}
          path={item.path}
          active={pathname === item.path}
          variant="bottom-nav"
        />
      ))}
    </nav>
  )
}
