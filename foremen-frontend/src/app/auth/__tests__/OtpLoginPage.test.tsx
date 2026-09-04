// Task 13.5: Unit tests for OtpLoginPage
// Requirements: 7.17 (resend cooldown constant), 7.18 (cooldown disables the
//               resend control then re-enables at zero)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

// --- i18n mock: return the key so DOM assertions are deterministic ---------
// `initReactI18next` is stubbed too because api-client transitively imports
// `@/lib/i18n`, which registers this plugin at module load.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

// --- authApi mock: control otpRequest/otpVerify resolution -----------------
const otpRequest = vi.fn<(email: string) => Promise<void>>()
const otpVerify = vi.fn()
vi.mock('@/app/auth/api/auth-api', () => ({
  otpRequest: (email: string) => otpRequest(email),
  otpVerify: (email: string, code: string) => otpVerify(email, code),
}))

// --- useAuthStore mock: selector-style access to setTokens/refreshIdentity -
const setTokens = vi.fn()
const refreshIdentity = vi.fn().mockResolvedValue(undefined)
vi.mock('@/stores/auth-store', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ setTokens, refreshIdentity }),
}))

// --- useAuthRedirect mock: no-op navigation --------------------------------
const redirect = vi.fn()
vi.mock('@/app/auth/hooks/useAuthRedirect', () => ({
  useAuthRedirect: () => redirect,
}))

import OtpLoginPage, {
  OTP_RESEND_COOLDOWN_SECONDS,
} from '@/app/auth/OtpLoginPage'

function renderPage() {
  return render(
    <MemoryRouter>
      <OtpLoginPage />
    </MemoryRouter>,
  )
}

/**
 * Advance the email step to the code step by entering an email and submitting.
 * Uses fireEvent (synchronous) so it stays reliable under fake timers, then
 * flushes the pending otpRequest microtask.
 */
async function advanceToCodeStep() {
  const emailInput = screen.getByRole('textbox')
  fireEvent.change(emailInput, { target: { value: 'alice@example.com' } })
  fireEvent.click(
    screen.getByRole('button', { name: 'auth.otp.emailStep.requestCode' }),
  )
  // Flush the pending otpRequest microtask so the step transition happens.
  await act(async () => {
    await Promise.resolve()
  })
}

beforeEach(() => {
  otpRequest.mockReset().mockResolvedValue(undefined)
  otpVerify.mockReset()
  setTokens.mockReset()
  redirect.mockReset()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('OtpLoginPage resend cooldown (Req 7.17, 7.18)', () => {
  it('disables the resend control while the cooldown counts down and re-enables it at zero', async () => {
    vi.useFakeTimers()

    renderPage()

    await advanceToCodeStep()

    // We should now be on the code step and otpRequest resolved once.
    expect(otpRequest).toHaveBeenCalledTimes(1)

    const resendButton = screen.getByRole('button', {
      name: /auth\.otp\.resend/,
    })

    // Immediately after reaching the code step the cooldown is active, so the
    // resend control is disabled.
    expect(resendButton).toBeDisabled()

    // Advance through the full cooldown; the control must re-enable at zero.
    // Wrapped in act() because each tick updates OtpLoginPage state.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(OTP_RESEND_COOLDOWN_SECONDS * 1000)
    })

    expect(resendButton).toBeEnabled()
  })
})
