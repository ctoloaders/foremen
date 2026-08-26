import { Outlet } from 'react-router-dom'

import { cn } from '@/lib/utils'
import { useBreakpoint } from '@/hooks/useBreakpoint'
import { useThemeApplicator } from '@/hooks/useThemeApplicator'
import { useThemeSync } from '@/hooks/useThemeSync'
import { useUIStore } from '@/stores/ui-store'
import { Sidebar } from '@/app/layout/Sidebar'
import { TopBar } from '@/app/layout/TopBar'
import { BottomNav } from '@/app/layout/BottomNav'
import { Drawer } from '@/app/layout/Drawer'

/**
 * AppShell — Root layout component wrapping all routes via React Router Outlet.
 *
 * Responsive behavior:
 * - Desktop (>1024px): Sidebar (full, visible=sidebarOpen) + TopBar + Outlet
 * - Tablet (768–1024px): Sidebar (collapsed=true, visible=true) + TopBar + Outlet
 * - Mobile (<768px): TopBar (hamburger) + Outlet + BottomNav + Drawer
 *
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 4.8
 */
export function AppShell() {
  const breakpoint = useBreakpoint()
  const sidebarOpen = useUIStore((s) => s.sidebarOpen)
  const toggleSidebar = useUIStore((s) => s.toggleSidebar)

  // Subscribe to theme store → apply CSS classes/variables on every change
  useThemeApplicator()

  // Sync theme with backend on startup; revert unsaved changes on nav away
  useThemeSync()

  return (
    <div className="min-h-screen bg-background font-sans">
      {/* Sidebar: rendered on desktop and tablet */}
      {breakpoint !== 'mobile' && (
        <Sidebar
          collapsed={breakpoint === 'tablet'}
          visible={breakpoint === 'desktop' ? sidebarOpen : true}
        />
      )}

      {/* Main content wrapper with left offset */}
      <div
        className={cn(
          'flex flex-col transition-[margin-left] duration-200',
          breakpoint === 'desktop' && sidebarOpen && 'ml-60',
          breakpoint === 'tablet' && 'ml-16',
          breakpoint === 'mobile' && 'ml-0 pb-16'
        )}
      >
        <TopBar />
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>

      {/* Mobile: BottomNav + Drawer */}
      {breakpoint === 'mobile' && (
        <>
          <BottomNav />
          <Drawer open={sidebarOpen} onClose={toggleSidebar} />
        </>
      )}
    </div>
  )
}
