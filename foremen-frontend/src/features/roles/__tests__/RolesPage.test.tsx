import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'roles.pageTitle': 'Управление ролями',
        'roles.tabs.list': 'Роли',
        'roles.tabs.matrix': 'Матрица доступов',
      }
      return translations[key] ?? key
    },
  }),
}))

vi.mock('../components/RolesListTab', () => ({
  RolesListTab: () => <div data-testid="roles-list-tab">RolesListTab content</div>,
}))

vi.mock('../components/PermissionMatrixTab', () => ({
  PermissionMatrixTab: () => (
    <div data-testid="permission-matrix-tab">PermissionMatrixTab content</div>
  ),
}))

vi.mock('../components/RoleFormSheet', () => ({
  RoleFormSheet: () => null,
}))

vi.mock('../components/DeleteRoleDialog', () => ({
  DeleteRoleDialog: () => null,
}))

import RolesPage from '../RolesPage'

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

describe('RolesPage', () => {
  it('renders two tabs with correct i18n labels', () => {
    render(<RolesPage />, { wrapper: createWrapper() })

    expect(screen.getByRole('tab', { name: 'Роли' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Матрица доступов' })).toBeInTheDocument()
  })

  it('defaults to "list" tab active', () => {
    render(<RolesPage />, { wrapper: createWrapper() })

    const listTab = screen.getByRole('tab', { name: 'Роли' })
    expect(listTab).toHaveAttribute('aria-selected', 'true')
    expect(listTab).toHaveAttribute('data-state', 'active')

    expect(screen.getByTestId('roles-list-tab')).toBeInTheDocument()
  })

  it('switching to matrix tab renders matrix tab content', async () => {
    const user = userEvent.setup()
    render(<RolesPage />, { wrapper: createWrapper() })

    const matrixTab = screen.getByRole('tab', { name: 'Матрица доступов' })
    await user.click(matrixTab)

    expect(matrixTab).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('permission-matrix-tab')).toBeInTheDocument()
  })

  it('switching back to list tab renders list tab content', async () => {
    const user = userEvent.setup()
    render(<RolesPage />, { wrapper: createWrapper() })

    // Switch to matrix
    await user.click(screen.getByRole('tab', { name: 'Матрица доступов' }))
    expect(screen.getByTestId('permission-matrix-tab')).toBeInTheDocument()

    // Switch back to list
    const listTab = screen.getByRole('tab', { name: 'Роли' })
    await user.click(listTab)

    expect(listTab).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('roles-list-tab')).toBeInTheDocument()
  })
})
