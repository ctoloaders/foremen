// Feature: FOR-03-06-frontend-auth — hydrate branches and logout (unit tests)
//
// Covers Auth_Store.hydrate() branches and logout() (task 7.2):
//  - hydrate: no stored token -> hydrationStatus='done', unauthenticated, getMe NOT called (Req 4.4)
//  - hydrate: getMe 200 -> authenticated with the returned user (Req 4.2)
//  - hydrate: getMe 304 (NOT_MODIFIED) -> uses Me_Cache.currentUser (Req 13.3)
//  - hydrate: getMe throws (unrecoverable) -> clearSession() + unauthenticated (Req 4.3)
//  - hydrate: every token-present branch ends hydrationStatus='done' (Req 4.5)
//  - logout: clears the session regardless of the logout response status (Req 8.2)
//  - logout: on network failure (authApi.logout rejects) still clears + invalidateMeCache (Req 8.4)

import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { NotModifiedSentinel } from '@/lib/api-client'
import type { CurrentUser } from '@/stores/auth-store'

// --- Mocks -------------------------------------------------------------------
// Mock the auth-api layer so hydrate/login/logout never hit the real network.
vi.mock('@/app/auth/api/auth-api', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  getMe: vi.fn(),
}))

// Mock Me_Cache so we control the 304 cached-user branch and can assert
// invalidateMeCache is called on logout.
vi.mock('@/lib/me-cache', () => ({
  getMeCache: vi.fn(() => ({ currentUser: null, etag: null })),
  invalidateMeCache: vi.fn(),
}))

// Imported for their mocked forms.
import * as authApi from '@/app/auth/api/auth-api'
import { getMeCache, invalidateMeCache } from '@/lib/me-cache'

// --- Fixtures ----------------------------------------------------------------

const ACCESS_TOKEN_KEY = 'foremen-access-token'
const REFRESH_TOKEN_KEY = 'foremen-refresh-token'

const sampleUser: CurrentUser = {
  id: 42,
  name: 'Test User',
  email: 'test@example.com',
  roleCode: 'ADMIN',
  permissions: [{ resource: 'users', operations: ['READ'] }],
}

const cachedUser: CurrentUser = {
  id: 7,
  name: 'Cached User',
  email: 'cached@example.com',
  roleCode: 'CLIENT',
  permissions: [],
}

/**
 * Installs an in-memory localStorage stub, optionally pre-seeded with tokens,
 * and returns its backing map.
 */
function installMemoryStorage(seed?: Record<string, string>): Map<string, string> {
  const backing = new Map<string, string>(Object.entries(seed ?? {}))
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
 * Fresh import of the store after resetting modules so its initial state
 * (`hydrationStatus='pending'`) is clean for each test. Also returns the
 * `NOT_MODIFIED` sentinel from the SAME (post-reset) api-client module
 * instance the store observes, so the store's `result === NOT_MODIFIED`
 * identity check matches what the mocked `getMe` resolves.
 */
async function freshStore() {
  vi.resetModules()
  const { NOT_MODIFIED } = await import('@/lib/api-client')
  const mod = await import('@/stores/auth-store')
  // `unique symbol` widens to `symbol` when carried on an object property, so
  // callers re-narrow via the exported `NotModifiedSentinel` type when needed.
  return { useAuthStore: mod.useAuthStore, NOT_MODIFIED }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  // Default cache: empty. Individual tests override via mockReturnValue.
  vi.mocked(getMeCache).mockReturnValue({ currentUser: null, etag: null })
})

// --- hydrate: no stored token ------------------------------------------------

describe('hydrate: no stored access token (Req 4.4)', () => {
  it('resolves as done + unauthenticated without calling getMe', async () => {
    installMemoryStorage() // empty storage
    const { useAuthStore } = await freshStore()

    await useAuthStore.getState().hydrate()

    const state = useAuthStore.getState()
    expect(state.hydrationStatus).toBe('done')
    expect(state.isAuthenticated).toBe(false)
    expect(state.user).toBeNull()
    expect(authApi.getMe).not.toHaveBeenCalled()
  })
})

// --- hydrate: getMe 200 ------------------------------------------------------

describe('hydrate: getMe returns 200 (Req 4.2, 4.5)', () => {
  it('populates the user, marks authenticated, and finishes done', async () => {
    installMemoryStorage({
      [ACCESS_TOKEN_KEY]: 'access-abc',
      [REFRESH_TOKEN_KEY]: 'refresh-xyz',
    })
    const { useAuthStore } = await freshStore()
    vi.mocked(authApi.getMe).mockResolvedValue(sampleUser)

    await useAuthStore.getState().hydrate()

    const state = useAuthStore.getState()
    expect(authApi.getMe).toHaveBeenCalledTimes(1)
    expect(state.user).toEqual(sampleUser)
    expect(state.accessToken).toBe('access-abc')
    expect(state.isAuthenticated).toBe(true)
    expect(state.hydrationStatus).toBe('done')
  })
})

// --- hydrate: getMe 304 (NOT_MODIFIED) --------------------------------------

describe('hydrate: getMe returns NOT_MODIFIED (Req 13.3, 4.5)', () => {
  it('reuses Me_Cache.currentUser and finishes done', async () => {
    installMemoryStorage({
      [ACCESS_TOKEN_KEY]: 'access-abc',
      [REFRESH_TOKEN_KEY]: 'refresh-xyz',
    })
    const { useAuthStore, NOT_MODIFIED } = await freshStore()
    vi.mocked(authApi.getMe).mockResolvedValue(NOT_MODIFIED as NotModifiedSentinel)
    vi.mocked(getMeCache).mockReturnValue({ currentUser: cachedUser, etag: 'etag-1' })

    await useAuthStore.getState().hydrate()

    const state = useAuthStore.getState()
    expect(state.user).toEqual(cachedUser)
    expect(state.isAuthenticated).toBe(true)
    expect(state.hydrationStatus).toBe('done')
  })

  it('clears the session when the 304 cache is empty (unrecoverable) and finishes done', async () => {
    installMemoryStorage({
      [ACCESS_TOKEN_KEY]: 'access-abc',
      [REFRESH_TOKEN_KEY]: 'refresh-xyz',
    })
    const { useAuthStore, NOT_MODIFIED } = await freshStore()
    vi.mocked(authApi.getMe).mockResolvedValue(NOT_MODIFIED as NotModifiedSentinel)
    vi.mocked(getMeCache).mockReturnValue({ currentUser: null, etag: 'etag-1' })

    await useAuthStore.getState().hydrate()

    const state = useAuthStore.getState()
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.isAuthenticated).toBe(false)
    expect(state.hydrationStatus).toBe('done')
  })
})

// --- hydrate: getMe throws (unrecoverable) ----------------------------------

describe('hydrate: getMe throws an unrecoverable error (Req 4.3, 4.5)', () => {
  it('clears the session, treats the user as unauthenticated, and finishes done', async () => {
    const storage = installMemoryStorage({
      [ACCESS_TOKEN_KEY]: 'access-abc',
      [REFRESH_TOKEN_KEY]: 'refresh-xyz',
    })
    const { useAuthStore } = await freshStore()
    vi.mocked(authApi.getMe).mockRejectedValue(new Error('401 refresh failed'))

    await useAuthStore.getState().hydrate()

    const state = useAuthStore.getState()
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.refreshToken).toBeNull()
    expect(state.isAuthenticated).toBe(false)
    expect(state.hydrationStatus).toBe('done')
    // clearSession removed the persisted tokens too.
    expect(storage.has(ACCESS_TOKEN_KEY)).toBe(false)
    expect(storage.has(REFRESH_TOKEN_KEY)).toBe(false)
  })
})

// --- logout ------------------------------------------------------------------

describe('logout clears the session regardless of the logout response (Req 8.2)', () => {
  it('clears session and invalidates Me_Cache when logout resolves', async () => {
    const storage = installMemoryStorage({
      [ACCESS_TOKEN_KEY]: 'access-abc',
      [REFRESH_TOKEN_KEY]: 'refresh-xyz',
    })
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
    const { useAuthStore } = await freshStore()

    // Establish an authenticated session.
    useAuthStore.setState({
      user: sampleUser,
      accessToken: 'access-abc',
      refreshToken: 'refresh-xyz',
      isAuthenticated: true,
    })

    await useAuthStore.getState().logout()

    expect(authApi.logout).toHaveBeenCalledWith('refresh-xyz')
    const state = useAuthStore.getState()
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.refreshToken).toBeNull()
    expect(state.isAuthenticated).toBe(false)
    expect(invalidateMeCache).toHaveBeenCalledTimes(1)
    expect(storage.has(ACCESS_TOKEN_KEY)).toBe(false)
    expect(storage.has(REFRESH_TOKEN_KEY)).toBe(false)
  })
})

describe('logout clears the session on network failure (Req 8.4)', () => {
  it('still clears session and invalidates Me_Cache when authApi.logout rejects', async () => {
    const storage = installMemoryStorage({
      [ACCESS_TOKEN_KEY]: 'access-abc',
      [REFRESH_TOKEN_KEY]: 'refresh-xyz',
    })
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network down'))
    const { useAuthStore } = await freshStore()

    useAuthStore.setState({
      user: sampleUser,
      accessToken: 'access-abc',
      refreshToken: 'refresh-xyz',
      isAuthenticated: true,
    })

    // logout swallows the network error and never rejects to the caller.
    await expect(useAuthStore.getState().logout()).resolves.toBeUndefined()

    expect(authApi.logout).toHaveBeenCalledWith('refresh-xyz')
    const state = useAuthStore.getState()
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.refreshToken).toBeNull()
    expect(state.isAuthenticated).toBe(false)
    expect(invalidateMeCache).toHaveBeenCalledTimes(1)
    expect(storage.has(ACCESS_TOKEN_KEY)).toBe(false)
    expect(storage.has(REFRESH_TOKEN_KEY)).toBe(false)
  })
})
