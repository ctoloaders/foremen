// Feature: FOR-03-06-frontend-auth — Token_Storage read/write and clear (unit tests)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  ACCESS_TOKEN_KEY,
  REFRESH_TOKEN_KEY,
  type CurrentUser,
  type TokenResponse,
} from '@/stores/auth-store'

// --- Fixtures ---

const sampleTokens: TokenResponse = {
  accessToken: 'access-abc',
  refreshToken: 'refresh-xyz',
  expiresIn: 3600,
}

const sampleUser: CurrentUser = {
  id: 42,
  name: 'Test User',
  email: 'test@example.com',
  roleCode: 'ADMIN',
  permissions: [{ resource: 'users', operations: ['READ'] }],
}

/**
 * Installs an in-memory localStorage stub and returns its backing map so
 * tests can assert what was written/removed.
 */
function installMemoryStorage(): Map<string, string> {
  const backing = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => backing.get(key) ?? null,
    setItem: (key: string, value: string) => {
      backing.set(key, value)
    },
    removeItem: (key: string) => {
      backing.delete(key)
    },
    clear: () => backing.clear(),
  })
  return backing
}

/**
 * Installs a localStorage stub whose setItem/getItem/removeItem all throw,
 * simulating unavailable storage (private browsing / quota exceeded).
 */
function installThrowingStorage(): void {
  const boom = () => {
    throw new Error('localStorage unavailable')
  }
  vi.stubGlobal('localStorage', {
    getItem: boom,
    setItem: boom,
    removeItem: boom,
    clear: boom,
  })
}

beforeEach(() => {
  vi.resetModules()
  vi.unstubAllGlobals()
})

describe('Token_Storage: setTokens writes both fixed localStorage keys (Req 1.3)', () => {
  it('writes accessToken and refreshToken under the fixed keys', async () => {
    const storage = installMemoryStorage()
    const { useAuthStore } = await import('@/stores/auth-store')

    useAuthStore.getState().setTokens(sampleTokens)

    expect(storage.get(ACCESS_TOKEN_KEY)).toBe(sampleTokens.accessToken)
    expect(storage.get(REFRESH_TOKEN_KEY)).toBe(sampleTokens.refreshToken)

    const state = useAuthStore.getState()
    expect(state.accessToken).toBe(sampleTokens.accessToken)
    expect(state.refreshToken).toBe(sampleTokens.refreshToken)
  })

  it('uses the documented fixed key names', () => {
    expect(ACCESS_TOKEN_KEY).toBe('foremen-access-token')
    expect(REFRESH_TOKEN_KEY).toBe('foremen-refresh-token')
  })
})

describe('Token_Storage: clearSession removes keys and resets state (Req 1.4)', () => {
  it('removes both stored tokens and resets user/token/authenticated state', async () => {
    const storage = installMemoryStorage()
    const { useAuthStore } = await import('@/stores/auth-store')

    // Establish an authenticated session first.
    useAuthStore.getState().setTokens(sampleTokens)
    useAuthStore.getState().setUser(sampleUser)
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(storage.has(ACCESS_TOKEN_KEY)).toBe(true)
    expect(storage.has(REFRESH_TOKEN_KEY)).toBe(true)

    useAuthStore.getState().clearSession()

    // Storage keys removed.
    expect(storage.has(ACCESS_TOKEN_KEY)).toBe(false)
    expect(storage.has(REFRESH_TOKEN_KEY)).toBe(false)

    // State reset to unauthenticated.
    const state = useAuthStore.getState()
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.refreshToken).toBeNull()
    expect(state.isAuthenticated).toBe(false)
  })
})

describe('Token_Storage: throwing localStorage keeps the store operating from memory (Req 1.6)', () => {
  it('setTokens does not throw and still updates in-memory state', async () => {
    installThrowingStorage()
    const { useAuthStore } = await import('@/stores/auth-store')

    expect(() => useAuthStore.getState().setTokens(sampleTokens)).not.toThrow()

    const state = useAuthStore.getState()
    expect(state.accessToken).toBe(sampleTokens.accessToken)
    expect(state.refreshToken).toBe(sampleTokens.refreshToken)
  })

  it('clearSession does not throw and still resets in-memory state', async () => {
    installThrowingStorage()
    const { useAuthStore } = await import('@/stores/auth-store')

    // Populate in-memory state (setTokens tolerates the throwing storage).
    useAuthStore.getState().setTokens(sampleTokens)
    useAuthStore.getState().setUser(sampleUser)
    expect(useAuthStore.getState().isAuthenticated).toBe(true)

    expect(() => useAuthStore.getState().clearSession()).not.toThrow()

    const state = useAuthStore.getState()
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.refreshToken).toBeNull()
    expect(state.isAuthenticated).toBe(false)
  })

  it('setUser does not throw under unavailable storage and derives isAuthenticated', async () => {
    installThrowingStorage()
    const { useAuthStore } = await import('@/stores/auth-store')

    useAuthStore.getState().setTokens(sampleTokens)
    expect(() => useAuthStore.getState().setUser(sampleUser)).not.toThrow()
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })
})
