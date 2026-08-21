# Implementation Plan: App Shell (FOR-02-02)

## Overview

Implement the Foremen admin panel App Shell — the root SPA layout with sidebar navigation, top bar, responsive breakpoints (desktop/tablet/mobile), routing with lazy-loaded placeholder pages, and skeleton loading. Components are built incrementally: config and hooks first, then layout components, then routing and pages.

## Tasks

- [x] 1. Navigation configuration and utility hooks
  - [x] 1.1 Create centralized navigation configuration
    - Create `src/config/navigation.ts` with `NavItemConfig`, `NavSectionConfig` interfaces and `NAV_CONFIG` array
    - Include all routes: Dashboard, Projekty, Pomieszczenia, Kosztorys, Materiały, Finanse, Dostawy, Użytkownicy
    - Set `bottomNav: true` for Dashboard, Projekty, Materiały, Finanse, Użytkownicy
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 1.2 Create `useBreakpoint` hook
    - Create `src/hooks/useBreakpoint.ts` returning `'mobile' | 'tablet' | 'desktop'`
    - Use `window.matchMedia` for efficient viewport tracking
    - Breakpoints: <768px = mobile, 768–1024px = tablet, >1024px = desktop
    - _Requirements: 1.6, 3.1, 4.1_

  - [x] 1.3 Create `usePageMeta` hook
    - Create `src/hooks/usePageMeta.ts` returning `{ titleKey: string, action?: { labelKey: string, onClick: () => void } }`
    - Derive title from current route path by looking up NAV_CONFIG
    - Fallback to generic title if route not in config
    - _Requirements: 5.1, 5.5, 5.6_

  - [x] 1.4 Update i18n locale files with navigation and page keys
    - Extend `src/locales/pl.json` with nav items (rooms, estimate, materials, finances, deliveries, users), nav sections (warehouse, system), pages namespace, topBar, and skeleton keys
    - Extend `src/locales/ru.json` with corresponding Russian translations
    - _Requirements: 9.1, 9.4, 9.5_

  - [x] 1.5 Update `useUIStore` to persist locale in localStorage
    - Modify `src/stores/ui-store.ts` to read initial locale from localStorage (fallback to 'pl')
    - Persist locale to localStorage on `setLocale` call (wrapped in try/catch)
    - _Requirements: 9.3, 10.1_

- [x] 2. Checkpoint — Verify config and hooks
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Core layout components
  - [x] 3.1 Create `NavItem` component
    - Create `src/app/layout/NavItem.tsx` with props: icon, labelKey, path, active, collapsed, variant
    - Use dynamic Lucide icon resolution with fallback to `Circle` icon for unknown names
    - Support variants: sidebar, bottom-nav, drawer
    - Use `useTranslation` for label rendering
    - _Requirements: 2.5, 8.5, 8.6, 4.4_

  - [x] 3.2 Create `UserFooter` component
    - Create `src/app/layout/UserFooter.tsx` displaying user avatar (initials), name, and role
    - Use placeholder data (e.g., "Jan Kowalski", "Administrator") until auth is implemented
    - _Requirements: 2.4_

  - [x] 3.3 Create `Sidebar` component
    - Create `src/app/layout/Sidebar.tsx` with collapsed/visible props
    - Render header with brand icon «F» (white 28×28 square, 6px border-radius) and «Foremen» text
    - Render nav sections from NAV_CONFIG with section titles
    - Render UserFooter at bottom
    - Support collapsed mode (64px, icons only) for tablet
    - Support hover/tap expansion on tablet with ≤300ms animation, dimmed backdrop overlay
    - Apply active highlighting with var(--secondary) background
    - Right border colored var(--border)
    - _Requirements: 2.1, 2.2, 2.3, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 3.4 Create `TopBar` component
    - Create `src/app/layout/TopBar.tsx`
    - Display current page title (from `usePageMeta`) on the left with text-overflow ellipsis
    - Display language switch button on the right showing «PL» or «RU»
    - On click, toggle locale via `useUIStore.setLocale` and `i18n.changeLanguage`
    - Conditionally display primary action button when page defines one
    - On mobile: include hamburger menu button triggering `toggleSidebar`
    - Bottom border colored var(--border)
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 4.5_

  - [x] 3.5 Create `BottomNav` component
    - Create `src/app/layout/BottomNav.tsx`
    - Render only NAV_CONFIG items with `bottomNav: true` (5 items)
    - Each item: icon + route, min touch target 44×44px
    - Highlight active item with var(--foreground), inactive with var(--muted-foreground)
    - Fixed position at bottom of screen
    - Navigate on tap using React Router
    - _Requirements: 4.2, 4.3, 4.4, 4.9_

  - [x] 3.6 Create `Drawer` component
    - Create `src/app/layout/Drawer.tsx` with open/onClose props
    - Slide-in panel from left with full nav config (all sections and items)
    - Dimmed backdrop (onClick closes drawer)
    - Close button in the drawer
    - _Requirements: 4.6, 4.7_

- [x] 4. Checkpoint — Verify layout components
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Skeleton page and error boundary
  - [x] 5.1 Create `SkeletonPage` component
    - Create `src/components/ui/SkeletonPage.tsx`
    - Render title placeholder (40–60% width) and subtitle (60–80% width)
    - Render 3–6 shimmer-animated placeholder cards with rectangular blocks
    - Use CSS pulse animation with 1.5–2s cycle
    - Responsive: 1 column on mobile, 2–3 columns on desktop
    - _Requirements: 7.1, 7.2, 7.3, 7.5_

  - [x] 5.2 Create `RouteErrorBoundary` component
    - Create `src/app/layout/RouteErrorBoundary.tsx`
    - Catch chunk load failures (dynamic import errors)
    - Display "Loading failed" message with a "Retry" button
    - Reset error state on navigation (location change)
    - _Requirements: 6.6_

- [x] 6. App Shell layout and routing
  - [x] 6.1 Create `AppShell` layout component
    - Create `src/app/layout/AppShell.tsx`
    - Compose Sidebar, TopBar, BottomNav, Drawer, and `<Outlet />` for main content
    - Use `useBreakpoint` to conditionally render components per breakpoint
    - Use `useUIStore.sidebarOpen` to control sidebar visibility / drawer state
    - Apply dark theme background #09090b, Inter font, min-height 100vh
    - Main content positioned right of sidebar with 24px padding
    - CSS transition 200ms for sidebar toggle
    - On desktop (sidebarOpen=false): main content occupies full width
    - On mobile: main content uses single-column 100% width
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 4.8_

  - [x] 6.2 Create placeholder page components
    - Create `src/app/pages/DashboardPage.tsx`, `ProjectsPage.tsx`, `RoomsPage.tsx`, `EstimatePage.tsx`, `MaterialsPage.tsx`, `FinancesPage.tsx`, `DeliveriesPage.tsx`, `UsersPage.tsx`
    - Each displays its page title and description from i18n (pages namespace)
    - _Requirements: 7.4, 9.4_

  - [x] 6.3 Create `NotFoundPage` component
    - Create `src/app/pages/NotFoundPage.tsx`
    - Display «404 — Strona nie znaleziona» (i18n key)
    - Include a navigation link to return to Dashboard (/)
    - _Requirements: 6.5_

  - [x] 6.4 Wire up router with lazy loading and App Shell layout
    - Update `src/app/router.tsx` with `createBrowserRouter`
    - Use `React.lazy` for all page components
    - Wrap lazy components in `React.Suspense` with `SkeletonPage` fallback
    - Wrap in `RouteErrorBoundary` for chunk load failures
    - Set `AppShell` as root layout element for all routes (including 404)
    - Define catch-all `*` route for NotFoundPage
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 7.4, 7.6_

- [x] 7. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Unit and integration tests
  - [x] 8.1 Write unit tests for navigation config and hooks
    - Test `NAV_CONFIG` structure validation (required fields, paths start with `/`)
    - Test `useBreakpoint` returns correct breakpoint for viewport widths
    - Test `usePageMeta` returns correct title key for given routes
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 8.2 Write unit tests for layout components
    - Test `NavItem` renders correct icon, label, and active state
    - Test `BottomNav` renders only items with `bottomNav: true`
    - Test `TopBar` language switcher calls setLocale with opposite locale
    - Test `NotFoundPage` renders 404 message and link to dashboard
    - Test `SkeletonPage` renders placeholder cards
    - _Requirements: 2.5, 4.2, 5.2, 5.3, 6.5, 7.1_

  - [x] 8.3 Write integration tests for AppShell
    - Test `AppShell` renders Sidebar on desktop, BottomNav on mobile
    - Test `Sidebar` collapsed mode on tablet renders icons only
    - Test `Drawer` opens/closes in response to store state changes
    - Test navigation items trigger route changes
    - Test language switch updates all rendered labels
    - _Requirements: 1.1, 3.1, 4.1, 4.6, 9.2, 10.2_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The design explicitly states that property-based testing does not apply to this UI feature (layout/rendering)
- Unit and integration tests use Vitest + React Testing Library
- The existing `useUIStore` is consumed directly — no schema changes needed beyond localStorage persistence
- All text labels are rendered via i18next keys for PL/RU support

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "1.5"] },
    { "id": 1, "tasks": ["3.1", "3.2", "5.1", "5.2"] },
    { "id": 2, "tasks": ["3.3", "3.4", "3.5", "3.6"] },
    { "id": 3, "tasks": ["6.1", "6.2", "6.3"] },
    { "id": 4, "tasks": ["6.4"] },
    { "id": 5, "tasks": ["8.1", "8.2", "8.3"] }
  ]
}
```
