import React, { Suspense, useEffect } from 'react'
import { createBrowserRouter, Outlet, useNavigate } from 'react-router-dom'

import { PermissionGuard } from '@/app/guards/PermissionGuard'
import { ProtectedLayout } from '@/app/guards/ProtectedLayout'
import { RouteErrorBoundary } from '@/app/layout/RouteErrorBoundary'
import { SkeletonPage } from '@/components/ui/SkeletonPage'
import { registerNavigate } from '@/lib/api-client'

// Lazy-loaded page components
const DashboardPage = React.lazy(() => import('@/app/pages/DashboardPage'))
const ProjectsPage = React.lazy(() => import('@/app/pages/ProjectsPage'))
const RoomsPage = React.lazy(() => import('@/app/pages/RoomsPage'))
const EstimatePage = React.lazy(() => import('@/app/pages/EstimatePage'))
const MaterialsPage = React.lazy(() => import('@/app/pages/MaterialsPage'))
const FinancesPage = React.lazy(() => import('@/app/pages/FinancesPage'))
const DeliveriesPage = React.lazy(() => import('@/app/pages/DeliveriesPage'))
const UsersPage = React.lazy(() => import('@/features/users/UsersPage'))
const RolesPage = React.lazy(() => import('@/features/roles/RolesPage'))
const MeasurementUnitsPage = React.lazy(
  () => import('@/features/measurement-units/MeasurementUnitsPage')
)
const CurrenciesPage = React.lazy(() => import('@/features/currencies/CurrenciesPage'))
const VatRatesPage = React.lazy(() => import('@/features/vat-rates/VatRatesPage'))
const RoomTypesPage = React.lazy(() => import('@/features/room-types/RoomTypesPage'))
const WorkCategoriesPage = React.lazy(
  () => import('@/features/work-categories/WorkCategoriesPage')
)
const DeliveryCategoriesPage = React.lazy(
  () => import('@/features/delivery-categories/DeliveryCategoriesPage')
)
const AuditPage = React.lazy(() => import('@/features/audit/AuditPage'))
const SettingsAppearancePage = React.lazy(
  () => import('@/features/settings/SettingsAppearancePage')
)
const NotFoundPage = React.lazy(() => import('@/app/pages/NotFoundPage'))
const ForbiddenPage = React.lazy(() => import('@/app/pages/ForbiddenPage'))

// Public auth pages (Public_Routes) — live OUTSIDE the AppShell / ProtectedLayout.
// These are lightweight placeholders until FOR-03-06 tasks 11/12/13 replace them
// with the real LoginPage / SetPasswordPage / OtpLoginPage.
const LoginPage = React.lazy(() => import('@/app/auth/LoginPage'))
const SetPasswordPage = React.lazy(() => import('@/app/auth/SetPasswordPage'))
const OtpLoginPage = React.lazy(() => import('@/app/auth/OtpLoginPage'))

function SuspenseWrapper({ children }: { children: React.ReactNode }) {
  return (
    <RouteErrorBoundary>
      <Suspense fallback={<SkeletonPage />}>
        {children}
      </Suspense>
    </RouteErrorBoundary>
  )
}

/**
 * NavigationRegistrar — mounted once inside the router tree (where `useNavigate`
 * is available) and hands the router's SPA navigation callback to the
 * Api_Client via {@link registerNavigate}. This lets the (non-React) Api_Client
 * module redirect to `/login` on forced logout using client-side navigation
 * instead of a full-page `window.location.assign` (Req 3.5, 3.6).
 *
 * Renders an `<Outlet/>` so it can wrap the whole route tree as a layout route
 * without affecting the rendered output.
 */
function NavigationRegistrar() {
  const navigate = useNavigate()

  useEffect(() => {
    registerNavigate((to) => navigate(to))
  }, [navigate])

  return <Outlet />
}

export const router = createBrowserRouter([
  {
    // Layout route: registers the navigation callback for the whole tree.
    element: <NavigationRegistrar />,
    children: [
      // Public_Routes — reachable without authentication, outside AppShell.
      {
        path: '/login',
        element: <SuspenseWrapper><LoginPage /></SuspenseWrapper>,
      },
      {
        path: '/auth/set-password',
        element: <SuspenseWrapper><SetPasswordPage /></SuspenseWrapper>,
      },
      {
        path: '/auth/otp',
        element: <SuspenseWrapper><OtpLoginPage /></SuspenseWrapper>,
      },
      // Protected routes — gated by the Auth_Guard; ProtectedLayout renders the
      // AppShell (which owns its own <Outlet/>) only when authenticated.
      {
        path: '/',
        element: <ProtectedLayout />,
        // Pathless layout route: the Permission_Route_Guard (FOR-03-07) wraps
        // the protected children so it sees the matched child path and gates it
        // against the Route_Requirement_Map, redirecting to `/403` on denial.
        // It runs strictly after ProtectedLayout's authentication, so anonymous
        // users still go to `/login` upstream.
        children: [
          {
            element: <PermissionGuard />,
            children: [
              {
                index: true,
                element: <SuspenseWrapper><DashboardPage /></SuspenseWrapper>,
              },
              {
                path: 'projects',
                element: <SuspenseWrapper><ProjectsPage /></SuspenseWrapper>,
              },
              {
                path: 'rooms',
                element: <SuspenseWrapper><RoomsPage /></SuspenseWrapper>,
              },
              {
                path: 'estimate',
                element: <SuspenseWrapper><EstimatePage /></SuspenseWrapper>,
              },
              {
                path: 'materials',
                element: <SuspenseWrapper><MaterialsPage /></SuspenseWrapper>,
              },
              {
                path: 'finances',
                element: <SuspenseWrapper><FinancesPage /></SuspenseWrapper>,
              },
              {
                path: 'deliveries',
                element: <SuspenseWrapper><DeliveriesPage /></SuspenseWrapper>,
              },
              {
                path: 'users',
                element: <SuspenseWrapper><UsersPage /></SuspenseWrapper>,
              },
              {
                path: 'roles',
                element: <SuspenseWrapper><RolesPage /></SuspenseWrapper>,
              },
              {
                path: 'measurement-units',
                element: <SuspenseWrapper><MeasurementUnitsPage /></SuspenseWrapper>,
              },
              {
                path: 'currencies',
                element: <SuspenseWrapper><CurrenciesPage /></SuspenseWrapper>,
              },
              {
                path: 'vat-rates',
                element: <SuspenseWrapper><VatRatesPage /></SuspenseWrapper>,
              },
              {
                path: 'room-types',
                element: <SuspenseWrapper><RoomTypesPage /></SuspenseWrapper>,
              },
              {
                path: 'work-categories',
                element: <SuspenseWrapper><WorkCategoriesPage /></SuspenseWrapper>,
              },
              {
                path: 'delivery-categories',
                element: <SuspenseWrapper><DeliveryCategoriesPage /></SuspenseWrapper>,
              },
              {
                path: 'audit',
                element: <SuspenseWrapper><AuditPage /></SuspenseWrapper>,
              },
              {
                path: 'settings/appearance',
                element: <SuspenseWrapper><SettingsAppearancePage /></SuspenseWrapper>,
              },
              // Forbidden_Page (`/403`) — a protected child WITHOUT a
              // requirement, so it renders inside the AppShell for any
              // authenticated user (ROUTE_REQUIREMENTS has no `/403` entry, so
              // PermissionGuard lets it through). Req 5.1, 5.4, 5.7.
              {
                path: '403',
                element: <SuspenseWrapper><ForbiddenPage /></SuspenseWrapper>,
              },
              // Catch-all NotFound — requirement-free; unmapped paths resolve
              // to no requirement in the Route_Requirement_Map.
              {
                path: '*',
                element: <SuspenseWrapper><NotFoundPage /></SuspenseWrapper>,
              },
            ],
          },
        ],
      },
    ],
  },
])
