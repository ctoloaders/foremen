import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ChevronDown } from 'lucide-react'

import { cn } from '@/lib/utils'
import { NAV_CONFIG, isNavItemVisible } from '@/config/navigation'
import { NavItem } from '@/app/layout/NavItem'
import { UserFooter } from '@/app/layout/UserFooter'
import { usePermission } from '@/hooks/usePermission'

/** The section rendered with a collapsible header on the Sidebar/Drawer. */
const COLLAPSIBLE_SECTION_KEY = 'nav.sections.dictionaries'

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
  // Dictionaries section is collapsible; default-expanded (Req 3.1, 3.2, 3.4).
  const [dictionariesOpen, setDictionariesOpen] = useState(true)
  const { pathname } = useLocation()
  const { t } = useTranslation()
  const { hasPermission } = usePermission()

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
          {NAV_CONFIG.map((section, sectionIndex) => {
            const visibleItems = section.items.filter((item) =>
              isNavItemVisible(item, hasPermission)
            )

            // Suppress a section entirely (header included) when all its items
            // are filtered out by permission (Req 3.4).
            if (visibleItems.length === 0) {
              return null
            }

            const showTitle = section.titleKey && (!collapsed || expanded)
            const isCollapsible =
              section.titleKey === COLLAPSIBLE_SECTION_KEY && showTitle
            // Hide items only when the collapsible section is toggled closed.
            const itemsHidden = isCollapsible && !dictionariesOpen

            return (
              <div key={sectionIndex} className="mb-4">
                {/* Section title — collapsible toggle for the Dictionaries section */}
                {isCollapsible ? (
                  <button
                    type="button"
                    onClick={() => setDictionariesOpen((open) => !open)}
                    aria-expanded={dictionariesOpen}
                    className="mb-1 flex w-full items-center justify-between px-3 text-xs font-medium uppercase tracking-wider text-muted-foreground hover:text-foreground"
                  >
                    <span>{t(section.titleKey as string)}</span>
                    <ChevronDown
                      className={cn(
                        'h-4 w-4 shrink-0 transition-transform',
                        !dictionariesOpen && '-rotate-90'
                      )}
                      aria-hidden="true"
                    />
                  </button>
                ) : (
                  showTitle && (
                    <p className="mb-1 px-3 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t(section.titleKey as string)}
                    </p>
                  )
                )}

                {/* Section items */}
                {!itemsHidden && (
                  <div className="flex flex-col gap-0.5">
                    {visibleItems.map((item) => (
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
                )}
              </div>
            )
          })}
        </nav>

        {/* User footer */}
        <div className="border-t border-border">
          <UserFooter collapsed={collapsed && !expanded} />
        </div>
      </aside>
    </>
  )
}
