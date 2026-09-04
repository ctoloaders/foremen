import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Unit tests for LoginPage (FOR-03-06 task 11.2).
 *
 * These assert BEHAVIOR, not markup:
 *  - empty-field validation blocks submission and never calls the backend
 *    (Req 5.2);
 *  - while the login promise is pending the submit control is disabled /
 *    aria-busy (Req 5.7);
 *  - a 401/403 `ApiError` message from the backend is surfaced at form level
 *    (Req 5.6, 10.3).
 *
 * `useAuthStore.login` is the seam we drive: the LoginPage calls it and
 * surfaces its rejection. `GoogleSignInButton` and `useAuthRedirect` are
 * stubbed so the test does not pull the GIS wrapper or a real router redirect.
 */

// --- i18n: return the key verbatim so we can assert on stable keys.
//     Keep the real module (i18n.ts needs `initReactI18next`) and only
//     override the `useTranslation` hook. ---
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({ t: (key: string) => key }),
  }
})

// --- Stub GoogleSignInButton so the GIS wrapper is never imported ---
vi.mock('@/app/auth/components/GoogleSignInButton', () => ({
  GoogleSignInButton: () => <div data-testid="google-signin-stub" />,
}))

// --- Mock useAuthRedirect (no real navigation) ---
const mockRedirect = vi.fn()
vi.mock('@/app/auth/hooks/useAuthRedirect', () => ({
  useAuthRedirect: () => mockRedirect,
}))

// --- Mock the Auth_Store: login is the driven seam; the page also reads
//     isAuthenticated + hydrationStatus for the already-authenticated effect. ---
const mockLogin = vi.fn()
let storeState: {
  login: typeof mockLogin
  isAuthenticated: boolean
  hydrationStatus: 'pending' | 'done'
}

vi.mock('@/stores/auth-store', () => ({
  useAuthStore: (selector: (state: typeof storeState) => unknown) =>
    selector(storeState),
}))

// Import the real ApiError so `instanceof ApiError` in the page holds true.
import { ApiError } from '@/lib/api-client'
import LoginPage from '../LoginPage'

function renderPage() {
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    storeState = {
      login: mockLogin,
      isAuthenticated: false,
      hydrationStatus: 'done',
    }
  })

  describe('empty-field validation (Req 5.2)', () => {
    it('blocks submission and does not call login when both fields are empty', async () => {
      const user = userEvent.setup()
      renderPage()

      await user.click(screen.getByRole('button', { name: 'auth.login.submit' }))

      // Field-level validation messages surface (email + password required).
      await waitFor(() => {
        expect(
          screen.getByText('auth.login.validation.emailRequired'),
        ).toBeInTheDocument()
      })
      expect(
        screen.getByText('auth.login.validation.passwordRequired'),
      ).toBeInTheDocument()

      // Backend was never called (Req 5.2).
      expect(mockLogin).not.toHaveBeenCalled()
    })

    it('blocks submission and does not call login when the password is empty', async () => {
      const user = userEvent.setup()
      renderPage()

      await user.type(screen.getByLabelText('auth.login.email'), 'user@example.com')
      await user.click(screen.getByRole('button', { name: 'auth.login.submit' }))

      await waitFor(() => {
        expect(
          screen.getByText('auth.login.validation.passwordRequired'),
        ).toBeInTheDocument()
      })
      expect(mockLogin).not.toHaveBeenCalled()
    })
  })

  describe('in-flight disables submit (Req 5.7)', () => {
    it('disables the submit control while the login promise is pending', async () => {
      const user = userEvent.setup()

      // Keep login pending so we can observe the in-flight state.
      let resolveLogin: () => void = () => {}
      mockLogin.mockImplementation(
        () =>
          new Promise<void>((resolve) => {
            resolveLogin = resolve
          }),
      )

      renderPage()

      await user.type(screen.getByLabelText('auth.login.email'), 'user@example.com')
      await user.type(screen.getByLabelText('auth.login.password'), 'password123')

      // Before submit the control shows the idle label.
      const submit = screen.getByRole('button', { name: 'auth.login.submit' })
      await user.click(submit)

      await waitFor(() => {
        const control = screen.getByRole('button', {
          name: 'auth.login.submitting',
        })
        expect(control).toBeDisabled()
        expect(control).toHaveAttribute('aria-busy', 'true')
      })

      // Let the request settle so the test does not leak a pending promise.
      resolveLogin()
      await waitFor(() => {
        expect(mockLogin).toHaveBeenCalledWith('user@example.com', 'password123')
      })
    })
  })

  describe('backend error surfacing (Req 5.6, 10.3)', () => {
    it('surfaces a 401 ApiError message at form level', async () => {
      const user = userEvent.setup()
      mockLogin.mockRejectedValue(
        new ApiError(401, 'Nieprawidłowe dane logowania', 'error.auth.invalid.credentials'),
      )

      renderPage()

      await user.type(screen.getByLabelText('auth.login.email'), 'user@example.com')
      await user.type(screen.getByLabelText('auth.login.password'), 'wrong-password')
      await user.click(screen.getByRole('button', { name: 'auth.login.submit' }))

      await waitFor(() => {
        expect(mockLogin).toHaveBeenCalledTimes(1)
      })

      // The server-localized message renders in the form-level alert region.
      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('Nieprawidłowe dane logowania')
      expect(mockRedirect).not.toHaveBeenCalled()
    })

    it('surfaces a 403 ApiError message at form level', async () => {
      const user = userEvent.setup()
      mockLogin.mockRejectedValue(
        new ApiError(403, 'Konto nie zostało aktywowane', 'error.auth.account.not.activated'),
      )

      renderPage()

      await user.type(screen.getByLabelText('auth.login.email'), 'invited@example.com')
      await user.type(screen.getByLabelText('auth.login.password'), 'password123')
      await user.click(screen.getByRole('button', { name: 'auth.login.submit' }))

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('Konto nie zostało aktywowane')
      expect(mockRedirect).not.toHaveBeenCalled()
    })

    it('navigates via useAuthRedirect on a successful login', async () => {
      const user = userEvent.setup()
      // A successful login flips the store's isAuthenticated to true; the page
      // redirects off that transition (single redirect source, so the
      // Return_Location is consumed exactly once — Req 12.3, 12.4).
      mockLogin.mockImplementation(async () => {
        storeState.isAuthenticated = true
      })

      const { rerender } = renderPage()

      await user.type(screen.getByLabelText('auth.login.email'), 'user@example.com')
      await user.type(screen.getByLabelText('auth.login.password'), 'password123')
      await user.click(screen.getByRole('button', { name: 'auth.login.submit' }))

      await waitFor(() => {
        expect(mockLogin).toHaveBeenCalledTimes(1)
      })

      // Re-render so the effect observes the post-login authenticated state
      // (the mocked store object is not reactive on its own).
      rerender(
        <MemoryRouter>
          <LoginPage />
        </MemoryRouter>,
      )

      await waitFor(() => {
        expect(mockRedirect).toHaveBeenCalledTimes(1)
      })
    })
  })
})
