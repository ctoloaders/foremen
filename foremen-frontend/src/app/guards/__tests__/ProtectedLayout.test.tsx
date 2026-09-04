import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'

import { ProtectedLayout } from '@/app/guards/ProtectedLayout'
import { captureReturnLocation } from '@/lib/return-location'
import { useAuthStore } from '@/stores/auth-store'

// i18n: keep the real module (i18n.ts wires initReactI18next) but override
// useTranslation so `t` returns the key verbatim for assertions.
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
      i18n: { changeLanguage: vi.fn() },
    }),
  }
})

// Return_Location: assert captureReturnLocation is called without touching storage.
vi.mock('@/lib/return-location', () => ({
  captureReturnLocation: vi.fn(),
}))

// AppShell: replace the whole shell with a lightweight sentinel so the test
// does not pull the real layout tree.
vi.mock('@/app/layout/AppShell', () => ({
  AppShell: () => <div data-testid="app-shell">app-shell</div>,
}))

const mockedCapture = vi.mocked(captureReturnLocation)

/**
 * Renders ProtectedLayout at `initialPath` alongside a `/login` route so a
 * redirect resolves to a detectable sentinel.
 */
function renderGuard(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/login" element={<div data-testid="login-page">login</div>} />
        <Route path="/*" element={<ProtectedLayout />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedLayout (Auth_Guard)', () => {
  beforeEach(() => {
    mockedCapture.mockClear()
    // Reset to a known baseline before each test; individual tests override.
    useAuthStore.setState({
      user: null,
      accessToken: null,
      refreshToken: null,
      isAuthenticated: false,
      hydrationStatus: 'pending',
    })
  })

  it('renders the loading indicator and does not redirect while hydration is pending (Req 9.4)', () => {
    useAuthStore.setState({ hydrationStatus: 'pending', isAuthenticated: false })

    renderGuard('/projects')

    // Loading indication present (role=status and the guard.loading text).
    const status = screen.getByRole('status')
    expect(status).toBeInTheDocument()
    expect(screen.getByText('auth.guard.loading')).toBeInTheDocument()

    // No redirect to /login and no AppShell while undetermined.
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
    expect(screen.queryByTestId('app-shell')).not.toBeInTheDocument()
    expect(mockedCapture).not.toHaveBeenCalled()
  })

  it('captures the Return_Location and redirects to /login while unauthenticated (Req 9.2)', () => {
    useAuthStore.setState({ hydrationStatus: 'done', isAuthenticated: false })

    renderGuard('/projects/42?tab=members')

    // Navigation landed on /login.
    expect(screen.getByTestId('login-page')).toBeInTheDocument()
    expect(screen.queryByTestId('app-shell')).not.toBeInTheDocument()

    // Return_Location captured with the attempted path + query.
    expect(mockedCapture).toHaveBeenCalledTimes(1)
    expect(mockedCapture).toHaveBeenCalledWith('/projects/42?tab=members')
  })

  it('renders the AppShell while authenticated (Req 9.3)', () => {
    useAuthStore.setState({
      hydrationStatus: 'done',
      isAuthenticated: true,
      accessToken: 'token',
      user: {
        id: 1,
        name: 'Test User',
        email: 'test@example.com',
        roleCode: 'ADMIN',
        permissions: [],
      },
    })

    renderGuard('/projects')

    expect(screen.getByTestId('app-shell')).toBeInTheDocument()
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
    expect(mockedCapture).not.toHaveBeenCalled()
  })
})
