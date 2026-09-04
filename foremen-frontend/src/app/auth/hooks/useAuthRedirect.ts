import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { consumeReturnLocation } from '@/lib/return-location'

/**
 * Resolves and performs the post-authentication redirect target.
 *
 * The returned function is called by the auth pages (LoginPage,
 * SetPasswordPage, OtpLoginPage, GoogleSignInButton) after a successful auth
 * transition. It consumes any captured Return_Location (reading it and
 * clearing it in one step via `consumeReturnLocation()`) and navigates there;
 * when no Return_Location is present it falls back to the application home
 * route `/` (Req 12.3, 12.4, 12.6).
 *
 * The navigation uses `replace` so the auth page is not left on the history
 * stack (a Back press should not return the user to `/login`).
 */
export function useAuthRedirect(): () => void {
  const navigate = useNavigate()

  return useCallback(() => {
    const target = consumeReturnLocation() ?? '/'
    navigate(target, { replace: true })
  }, [navigate])
}
