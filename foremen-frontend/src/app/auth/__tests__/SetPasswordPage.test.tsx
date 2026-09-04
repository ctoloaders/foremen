import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Unit tests for SetPasswordPage (FOR-03-06 task 12.2).
 *
 * These assert BEHAVIOR, not markup:
 *  - a missing `?token=` shows an invalid-link message and never submits to the
 *    backend (`authApi.setPassword` is not called) (Req 6.2);
 *  - the 8..72 length policy and the confirm-mismatch rule block submission
 *    (Req 6.4, 6.5);
 *  - a 400 `ApiError` message from the backend is surfaced at form level
 *    (Req 6.8).
 *
 * The token is provided via the router (`MemoryRouter initialEntries`) so the
 * real `useSearchParams` reads it. `authApi`, `useAuthRedirect`, and the
 * Auth_Store setters are stubbed.
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

// --- Mock useAuthRedirect (no real navigation) ---
const mockRedirect = vi.fn()
vi.mock('@/app/auth/hooks/useAuthRedirect', () => ({
  useAuthRedirect: () => mockRedirect,
}))

// --- Mock the auth-api layer: setPassword is the driven seam ---
const mockSetPassword = vi.fn()
vi.mock('@/app/auth/api/auth-api', () => ({
  setPassword: (...args: unknown[]) => mockSetPassword(...args),
}))

// --- Mock the Auth_Store: the page reads setTokens + refreshIdentity ---
const mockSetTokens = vi.fn()
const mockRefreshIdentity = vi.fn()
const storeState = {
  setTokens: mockSetTokens,
  refreshIdentity: mockRefreshIdentity,
}

vi.mock('@/stores/auth-store', () => ({
  useAuthStore: (selector: (state: typeof storeState) => unknown) =>
    selector(storeState),
}))

// Import the real ApiError so `instanceof ApiError` in the page holds true.
import { ApiError } from '@/lib/api-client'
import SetPasswordPage from '../SetPasswordPage'

/** Render the page at `/auth/set-password` with the given (optional) query. */
function renderPage(query = '') {
  return render(
    <MemoryRouter initialEntries={[`/auth/set-password${query}`]}>
      <SetPasswordPage />
    </MemoryRouter>,
  )
}

describe('SetPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockRefreshIdentity.mockResolvedValue(undefined)
  })

  describe('missing token (Req 6.2)', () => {
    it('shows an invalid-link message and renders no submit control', () => {
      renderPage() // no ?token=

      expect(
        screen.getByText('auth.setPassword.invalidLink'),
      ).toBeInTheDocument()
      // No form/submit means the backend can never be called.
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      expect(mockSetPassword).not.toHaveBeenCalled()
    })

    it('treats an empty token value as missing', () => {
      renderPage('?token=')

      expect(
        screen.getByText('auth.setPassword.invalidLink'),
      ).toBeInTheDocument()
      expect(mockSetPassword).not.toHaveBeenCalled()
    })
  })

  describe('password validation (Req 6.4, 6.5)', () => {
    it('blocks submission when the password is shorter than 8 characters', async () => {
      const user = userEvent.setup()
      renderPage('?token=abc123')

      await user.type(screen.getByLabelText('auth.setPassword.newPassword'), 'short')
      await user.type(
        screen.getByLabelText('auth.setPassword.confirmPassword'),
        'short',
      )
      await user.click(
        screen.getByRole('button', { name: 'auth.setPassword.submit' }),
      )

      await waitFor(() => {
        expect(
          screen.getByText('auth.setPassword.validation.length'),
        ).toBeInTheDocument()
      })
      expect(mockSetPassword).not.toHaveBeenCalled()
    })

    it('blocks submission when confirm does not match', async () => {
      const user = userEvent.setup()
      renderPage('?token=abc123')

      await user.type(
        screen.getByLabelText('auth.setPassword.newPassword'),
        'valid-password',
      )
      await user.type(
        screen.getByLabelText('auth.setPassword.confirmPassword'),
        'different-password',
      )
      await user.click(
        screen.getByRole('button', { name: 'auth.setPassword.submit' }),
      )

      await waitFor(() => {
        expect(
          screen.getByText('auth.setPassword.validation.mismatch'),
        ).toBeInTheDocument()
      })
      expect(mockSetPassword).not.toHaveBeenCalled()
    })

    it('submits with the token and password when the form is valid', async () => {
      const user = userEvent.setup()
      mockSetPassword.mockResolvedValue({
        accessToken: 'a',
        refreshToken: 'r',
        expiresIn: 900,
      })

      renderPage('?token=abc123')

      await user.type(
        screen.getByLabelText('auth.setPassword.newPassword'),
        'valid-password',
      )
      await user.type(
        screen.getByLabelText('auth.setPassword.confirmPassword'),
        'valid-password',
      )
      await user.click(
        screen.getByRole('button', { name: 'auth.setPassword.submit' }),
      )

      await waitFor(() => {
        expect(mockSetPassword).toHaveBeenCalledWith('abc123', 'valid-password')
      })
      // Auto-login: tokens recorded, identity refreshed, then redirect.
      await waitFor(() => {
        expect(mockSetTokens).toHaveBeenCalled()
        expect(mockRefreshIdentity).toHaveBeenCalled()
        expect(mockRedirect).toHaveBeenCalled()
      })
    })
  })

  describe('backend error surfacing (Req 6.8)', () => {
    it('surfaces a 400 ApiError message at form level and stays on the page', async () => {
      const user = userEvent.setup()
      mockSetPassword.mockRejectedValue(
        new ApiError(400, 'Link wygasł lub został już użyty', 'error.auth.token.invalid'),
      )

      renderPage('?token=expired-token')

      await user.type(
        screen.getByLabelText('auth.setPassword.newPassword'),
        'valid-password',
      )
      await user.type(
        screen.getByLabelText('auth.setPassword.confirmPassword'),
        'valid-password',
      )
      await user.click(
        screen.getByRole('button', { name: 'auth.setPassword.submit' }),
      )

      await waitFor(() => {
        expect(mockSetPassword).toHaveBeenCalledTimes(1)
      })

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('Link wygasł lub został już użyty')
      // No auto-login / redirect on error.
      expect(mockSetTokens).not.toHaveBeenCalled()
      expect(mockRedirect).not.toHaveBeenCalled()
    })
  })
})
