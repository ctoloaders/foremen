import { render, screen, fireEvent, within, act } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AppShell } from '@/app/layout/AppShell'
import { useAuthStore } from '@/stores/auth-store'
import { useUIStore } from '@/stores/ui-store'
import i18n from '@/lib/i18n'

// Mock useBreakpoint to control viewport in tests
const mockUseBreakpoint = vi.fn<() => 'desktop' | 'tablet' | 'mobile'>(() => 'desktop')

vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockUseBreakpoint(),
}))

// Mock theme hooks — theme application is tested separately
vi.mock('@/hooks/useThemeApplicator', () => ({
  useThemeApplicator: () => {},
}))

vi.mock('@/hooks/useThemeSync', () => ({
  useThemeSync: () => {},
}))

function renderAppShell(initialPath = '/') {
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element: <AppShell />,
        children: [
          { index: true, element: <div data-testid="dashboard-content">Dashboard Content</div> },
          { path: 'projects', element: <div data-testid="projects-content">Projects Content</div> },
          { path: 'materials', element: <div data-testid="materials-content">Materials Content</div> },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  )
  return render(<RouterProvider router={router} />)
}

describe('AppShell Integration Tests', () => {
  beforeEach(() => {
    // Reset breakpoint mock
    mockUseBreakpoint.mockReturnValue('desktop')

    // Reset store state
    useUIStore.setState({ sidebarOpen: true, locale: 'pl' })

    // Seed an ADMIN user so permission-gated nav items (e.g. "Projekty") are
    // visible. FOR-03-07 filters the Sidebar/Drawer/BottomNav by permission, and
    // ADMIN bypasses the matrix, so the full navigation renders for these
    // shell-level integration assertions.
    useAuthStore.setState({
      user: {
        id: 1,
        name: 'Jan Kowalski',
        email: 'admin@example.com',
        roleCode: 'ADMIN',
        permissions: [],
      },
    })

    // Reset i18n to PL
    void i18n.changeLanguage('pl')
  })

  describe('Desktop renders Sidebar, not BottomNav', () => {
    it('renders Sidebar with Foremen branding on desktop', () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      renderAppShell()

      // Sidebar should be present — it renders an aside element with brand text
      const aside = document.querySelector('aside')
      expect(aside).not.toBeNull()
      expect(screen.getByText('Foremen')).toBeInTheDocument()

      // Sidebar navigation items should be visible
      const sidebarNav = within(aside!)
      expect(sidebarNav.getByText('Panel')).toBeInTheDocument()
    })

    it('does NOT render BottomNav on desktop', () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      renderAppShell()

      // BottomNav has a fixed bottom-0 nav; on desktop it shouldn't be rendered
      const navElements = screen.getAllByRole('navigation')
      const bottomNav = navElements.find((el) => el.classList.contains('bottom-0'))
      expect(bottomNav).toBeUndefined()
    })
  })

  describe('Mobile renders BottomNav, not Sidebar', () => {
    it('renders BottomNav on mobile', () => {
      mockUseBreakpoint.mockReturnValue('mobile')
      useUIStore.setState({ sidebarOpen: false })
      renderAppShell()

      // BottomNav renders a nav element with bottom-0 class
      const navElements = screen.getAllByRole('navigation')
      const bottomNav = navElements.find((el) => el.classList.contains('bottom-0'))
      expect(bottomNav).toBeDefined()
    })

    it('does NOT render Sidebar aside element on mobile', () => {
      mockUseBreakpoint.mockReturnValue('mobile')
      useUIStore.setState({ sidebarOpen: false })
      renderAppShell()

      // On mobile, the AppShell does not render <Sidebar> at all.
      // The only aside is from the Drawer, which should be hidden (-translate-x-full)
      const asides = document.querySelectorAll('aside')
      asides.forEach((aside) => {
        // Drawer aside should be off-screen when sidebarOpen is false
        expect(aside.className).toContain('-translate-x-full')
      })
    })
  })

  describe('Tablet renders collapsed Sidebar (icons only)', () => {
    it('renders Sidebar in collapsed mode on tablet (w-16)', () => {
      mockUseBreakpoint.mockReturnValue('tablet')
      renderAppShell()

      const aside = document.querySelector('aside')
      expect(aside).not.toBeNull()
      expect(aside!.className).toContain('w-16')
    })

    it('does NOT show Foremen text label when collapsed', () => {
      mockUseBreakpoint.mockReturnValue('tablet')
      renderAppShell()

      // In collapsed mode the "Foremen" text is hidden
      expect(screen.queryByText('Foremen')).not.toBeInTheDocument()
    })
  })

  describe('Drawer opens/closes in response to store state changes', () => {
    it('Drawer is visible when sidebarOpen=true on mobile', () => {
      mockUseBreakpoint.mockReturnValue('mobile')
      useUIStore.setState({ sidebarOpen: true })
      renderAppShell()

      // The drawer aside should have translate-x-0 (visible)
      const drawerAside = document.querySelector('aside')
      expect(drawerAside).not.toBeNull()
      expect(drawerAside!.className).toContain('translate-x-0')
    })

    it('Drawer is hidden when sidebarOpen=false on mobile', () => {
      mockUseBreakpoint.mockReturnValue('mobile')
      useUIStore.setState({ sidebarOpen: false })
      renderAppShell()

      // The drawer aside should be off-screen
      const drawerAside = document.querySelector('aside')
      expect(drawerAside).not.toBeNull()
      expect(drawerAside!.className).toContain('-translate-x-full')
    })

    it('Drawer opens when hamburger button is clicked', () => {
      mockUseBreakpoint.mockReturnValue('mobile')
      useUIStore.setState({ sidebarOpen: false })
      renderAppShell()

      // Click hamburger menu button
      const hamburger = screen.getByLabelText('Menu')
      fireEvent.click(hamburger)

      // After click, store toggles sidebarOpen — drawer should show
      const drawerAside = document.querySelector('aside')
      expect(drawerAside!.className).toContain('translate-x-0')
    })

    it('Drawer closes when close button is clicked', () => {
      mockUseBreakpoint.mockReturnValue('mobile')
      useUIStore.setState({ sidebarOpen: true })
      renderAppShell()

      // Click close button in drawer
      const closeButton = screen.getByLabelText('Close menu')
      fireEvent.click(closeButton)

      // After click, store toggles sidebarOpen — drawer should be hidden
      const drawerAside = document.querySelector('aside')
      expect(drawerAside!.className).toContain('-translate-x-full')
    })
  })

  describe('Navigation items trigger route changes', () => {
    it('clicking a sidebar nav item navigates to the correct route', () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      renderAppShell()

      // Click "Projekty" nav item in sidebar
      const aside = document.querySelector('aside')!
      const sidebarScope = within(aside)
      const projectsLink = sidebarScope.getByRole('link', { name: /Projekty/i })
      fireEvent.click(projectsLink)

      // The projects content should now be rendered
      expect(screen.getByTestId('projects-content')).toBeInTheDocument()
    })

    it('clicking a BottomNav item navigates on mobile', () => {
      mockUseBreakpoint.mockReturnValue('mobile')
      useUIStore.setState({ sidebarOpen: false })
      renderAppShell()

      // BottomNav should show the "Projekty" item (bottomNav: true)
      // Target the bottom nav specifically
      const navElements = screen.getAllByRole('navigation')
      const bottomNav = navElements.find((el) => el.classList.contains('bottom-0'))!
      const bottomNavScope = within(bottomNav)
      const projectsLink = bottomNavScope.getByRole('link', { name: /Projekty/i })
      fireEvent.click(projectsLink)

      expect(screen.getByTestId('projects-content')).toBeInTheDocument()
    })
  })

  describe('Language switch updates all rendered labels', () => {
    it('switching language from PL to RU updates nav labels', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      renderAppShell()

      // Initially labels are in Polish — check within sidebar
      const aside = document.querySelector('aside')!
      const sidebarScope = within(aside)
      expect(sidebarScope.getByText('Panel')).toBeInTheDocument()
      expect(sidebarScope.getByText('Projekty')).toBeInTheDocument()

      // Click the language button (shows "PL")
      const langButton = screen.getByText('PL')
      await act(async () => {
        fireEvent.click(langButton)
      })

      // After switch, sidebar labels should be in Russian
      expect(sidebarScope.getByText('Панель')).toBeInTheDocument()
      expect(sidebarScope.getByText('Проекты')).toBeInTheDocument()
      // Language button should now show "RU"
      expect(screen.getByText('RU')).toBeInTheDocument()
    })

    it('switching language updates page title in TopBar', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      renderAppShell()

      // Initially at /, title should be "Panel"
      const title = screen.getByRole('heading', { level: 1 })
      expect(title).toHaveTextContent('Panel')

      // Switch language
      const langButton = screen.getByText('PL')
      await act(async () => {
        fireEvent.click(langButton)
      })

      // Title should update to Russian
      expect(title).toHaveTextContent('Панель')
    })
  })
})
