import React, { Suspense } from 'react'
import { createBrowserRouter } from 'react-router-dom'

import { AppShell } from '@/app/layout/AppShell'
import { RouteErrorBoundary } from '@/app/layout/RouteErrorBoundary'
import { SkeletonPage } from '@/components/ui/SkeletonPage'

// Lazy-loaded page components
const DashboardPage = React.lazy(() => import('@/app/pages/DashboardPage'))
const ProjectsPage = React.lazy(() => import('@/app/pages/ProjectsPage'))
const RoomsPage = React.lazy(() => import('@/app/pages/RoomsPage'))
const EstimatePage = React.lazy(() => import('@/app/pages/EstimatePage'))
const MaterialsPage = React.lazy(() => import('@/app/pages/MaterialsPage'))
const FinancesPage = React.lazy(() => import('@/app/pages/FinancesPage'))
const DeliveriesPage = React.lazy(() => import('@/app/pages/DeliveriesPage'))
const UsersPage = React.lazy(() => import('@/app/pages/UsersPage'))
const NotFoundPage = React.lazy(() => import('@/app/pages/NotFoundPage'))

function SuspenseWrapper({ children }: { children: React.ReactNode }) {
  return (
    <RouteErrorBoundary>
      <Suspense fallback={<SkeletonPage />}>
        {children}
      </Suspense>
    </RouteErrorBoundary>
  )
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
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
        path: '*',
        element: <SuspenseWrapper><NotFoundPage /></SuspenseWrapper>,
      },
    ],
  },
])
