import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

import { cn } from '@/lib/utils'
import { NAV_CONFIG } from '@/config/navigation'
import { NavItem } from '@/app/layout/NavItem'
import { UserFooter } from '@/app/layout/UserFooter'

/**
 * Sidebar — Desktop/Tablet sidebar navigation panel.
 *
 * Desktop (>1024px): full 240px width, controlled by `visible` prop
 * Tablet (768–1024px): collapsed 64px, expands to 240px overlay on hover/tap
 *
 * Requirements: 2.1, 2.2, 2.3, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
 */

export interface SidebarProps {
  collapsed: boolean
  visible: boolean
}

export function Sidebar({ collapsed, visible }: SidebarProps) {
  const [hovered, setHovered] = useState(false)
  const { pathname } = useLocation()
  const { t } = useTranslation()

  // When collapsed and hovered, expand to full width overlay
  const expanded = collapsed && hovered

  // Don't render if not visible and not collapsed (desktop hidden state)
  if (!visible && !collapsed) {
    return null
  }

  return (
    <>
      {/* Backdrop overlay when expanded on tablet */}
      {expanded && (
        <div
          className="fixed inset-0 z-30 bg-black/50"
          onClick={() => setHovered(false)}
          aria-hidden="true"
        />
      )}

      <aside
        className={cn(
          'fixed left-0 top-0 z-40 flex h-screen flex-col border-r border-border bg-background transition-all duration-300',
          collapsed && !expanded && 'w-16',
          (!collapsed || expanded) && 'w-60',
          expanded && 'shadow-xl'
        )}
        onMouseEnter={() => {
          if (collapsed) setHovered(true)
        }}
        onMouseLeave={() => {
          if (collapsed) setHovered(false)
        }}
        onTouchStart={() => {
          if (collapsed && !hovered) setHovered(true)
        }}
      >
        {/* Header: Brand icon + text */}
        <div className="flex items-center gap-3 px-4 py-4">
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-white text-sm font-bold text-black">
            F
          </div>
          {(!collapsed || expanded) && (
            <span className="text-lg font-semibold text-foreground">
              Foremen
            </span>
          )}
        </div>

        {/* Navigation sections */}
        <nav className="flex-1 overflow-y-auto px-2 py-2">
          {NAV_CONFIG.map((section, sectionIndex) => (
            <div key={sectionIndex} className="mb-4">
              {/* Section title */}
              {section.titleKey && (!collapsed || expanded) && (
                <p className="mb-1 px-3 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                  {t(section.titleKey)}
                </p>
              )}

              {/* Section items */}
              <div className="flex flex-col gap-0.5">
                {section.items.map((item) => (
                  <NavItem
                    key={item.path}
                    icon={item.icon}
                    labelKey={item.labelKey}
                    path={item.path}
                    active={pathname === item.path}
                    collapsed={collapsed && !expanded}
                    variant="sidebar"
                  />
                ))}
              </div>
            </div>
          ))}
        </nav>

        {/* User footer */}
        <div className="border-t border-border">
          <UserFooter collapsed={collapsed && !expanded} />
        </div>
      </aside>
    </>
  )
}
