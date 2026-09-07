import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ChevronDown, X } from 'lucide-react'

import { cn } from '@/lib/utils'
import { NAV_CONFIG, isNavItemVisible } from '@/config/navigation'
import { NavItem } from '@/app/layout/NavItem'
import { UserFooter } from '@/app/layout/UserFooter'
import { usePermission } from '@/hooks/usePermission'

/** The section rendered with a collapsible header on the Sidebar/Drawer. */
const COLLAPSIBLE_SECTION_KEY = 'nav.sections.dictionaries'

/**
 * Drawer — Mobile slide-out navigation panel.
 *
 * Slides in from the left with the full navigation menu (all sections and items).
 * Includes a dimmed backdrop that closes the drawer on click, and a close button.
 *
 * Requirements: 4.6, 4.7
 */

export interface DrawerProps {
  open: boolean
  onClose: () => void
}

export function Drawer({ open, onClose }: DrawerProps) {
  const { pathname } = useLocation()
  const { t } = useTranslation()
  const { hasPermission } = usePermission()
  // Dictionaries section is collapsible; default-expanded (Req 3.1, 3.2, 3.4).
  const [dictionariesOpen, setDictionariesOpen] = useState(true)

  return (
    <div className={cn('fixed inset-0 z-50', open ? 'visible' : 'invisible')}>
      {/* Dimmed backdrop */}
      <div
        className={cn(
          'fixed inset-0 bg-black/50 transition-opacity duration-300',
          open ? 'opacity-100' : 'opacity-0'
        )}
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Slide-in panel */}
      <aside
        className={cn(
          'fixed left-0 top-0 bottom-0 z-50 flex w-72 flex-col bg-background transition-transform duration-300',
          open ? 'translate-x-0' : '-translate-x-full'
        )}
      >
        {/* Header: Brand icon + text + close button */}
        <div className="flex items-center justify-between px-4 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-white text-sm font-bold text-black">
              F
            </div>
            <span className="text-lg font-semibold text-foreground">
              Foremen
            </span>
          </div>

          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground"
            aria-label="Close menu"
          >
            <X className="h-5 w-5" />
          </button>
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

            const isCollapsible = section.titleKey === COLLAPSIBLE_SECTION_KEY
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
                  section.titleKey && (
                    <p className="mb-1 px-3 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t(section.titleKey)}
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
                        variant="drawer"
                      />
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </nav>

        {/* User footer with log-out menu */}
        <div className="border-t border-border">
          <UserFooter />
        </div>
      </aside>
    </div>
  )
}
