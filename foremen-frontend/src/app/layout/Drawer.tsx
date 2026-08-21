import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'

import { cn } from '@/lib/utils'
import { NAV_CONFIG } from '@/config/navigation'
import { NavItem } from '@/app/layout/NavItem'

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
          {NAV_CONFIG.map((section, sectionIndex) => (
            <div key={sectionIndex} className="mb-4">
              {/* Section title */}
              {section.titleKey && (
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
                    variant="drawer"
                  />
                ))}
              </div>
            </div>
          ))}
        </nav>
      </aside>
    </div>
  )
}
