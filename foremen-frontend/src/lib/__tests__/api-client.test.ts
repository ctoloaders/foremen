// Feature: FOR-03-06-frontend-auth — Api_Client header assembly and error surface (unit tests, task 4.2)
//
// Covers Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 13.7:
//  - Authorization header present/absent by the Auth_Store access token.
//  - Accept-Language `ru` when foremen-locale is `ru`, else `pl` fallback.
//  - Content-Type: application/json set when a JSON body is present.
//  - If-None-Match passed through when `ifNoneMatch` is supplied.
//  - Verbatim backend message surfaced on a non-OK JSON body.
//  - Generic fallback (auth.error.generic) on a non-JSON body without throwing.
//  - 304 with parse304AsSuccess resolves the NOT_MODIFIED sentinel as success.
//
// These tests exercise ONLY the header/response behavior delivered by task 4.1;
// they do not depend on the 401 refresh/retry path (task 5.1).

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

// --- Response fakes ---------------------------------------------------------

/**
 * Builds a minimal `Response`-like object with a JSON body. `json()` resolves
 * with the given value, mirroring a well-formed backend response.
 */
function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response
}

/**
 * Builds a `Response`-like object whose `json()` rejects, simulating a non-JSON
 * (e.g. HTML/empty) body. Used to assert the generic-fallback error path.
 */
function nonJsonResponse(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.reject(new SyntaxError('Unexpected token < in JSON')),
  } as unknown as Response
}

// --- localStorage stub ------------------------------------------------------

/**
 * Installs an in-memory localStorage stub seeded with the given entries.
 * Used to drive the Accept-Language resolution via `foremen-locale`.
 */
function installStorage(entries: Record<string, string> = {}): Map<string, string> {
  const backing = new Map<string, string>(Object.entries(entries))
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
 * Installs a `fetch` stub that always resolves the given response, and returns
 * the mock so tests can assert the URL, method, and headers it was called with.
 */
function installFetch(response: Response) {
  const fetchMock = vi.fn(
    (_input?: unknown, _init?: RequestInit): Promise<Response> =>
      Promise.resolve(response),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** Reads the headers object the single fetch call was invoked with. */
function headersOf(fetchMock: ReturnType<typeof installFetch>): Record<string, string> {
  const init = fetchMock.mock.calls[0]?.[1] as RequestInit
  return init.headers as Record<string, string>
}

beforeEach(() => {
  vi.resetModules()
  vi.unstubAllGlobals()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// --- Authorization header (Req 2.1, 2.2) ------------------------------------

describe('Api_Client: Authorization header by Auth_Store access token (Req 2.1, 2.2)', () => {
  it('attaches Authorization: Bearer <token> when the store has an access token', async () => {
    installStorage()
    const fetchMock = installFetch(jsonResponse(200, { ok: true }))

    const { apiRequest } = await import('@/lib/api-client')
    const { useAuthStore } = await import('@/stores/auth-store')
    useAuthStore.getState().setTokens({
      accessToken: 'token-123',
      refreshToken: 'refresh-123',
      expiresIn: 3600,
    })

    await apiRequest('/api/some')

    expect(headersOf(fetchMock).Authorization).toBe('Bearer token-123')
  })

  it('omits the Authorization header when the store has no access token', async () => {
    installStorage()
    const fetchMock = installFetch(jsonResponse(200, { ok: true }))

    const { apiRequest } = await import('@/lib/api-client')

    await apiRequest('/api/some')

    expect(headersOf(fetchMock).Authorization).toBeUndefined()
  })
})

// --- Accept-Language header (Req 2.3) ---------------------------------------

describe('Api_Client: Accept-Language ru/pl fallback (Req 2.3)', () => {
  it('sends Accept-Language: ru when foremen-locale is ru', async () => {
    installStorage({ 'foremen-locale': 'ru' })
    const fetchMock = installFetch(jsonResponse(200, {}))

    const { apiRequest } = await import('@/lib/api-client')
    await apiRequest('/api/some')

    expect(headersOf(fetchMock)['Accept-Language']).toBe('ru')
  })

  it('falls back to Accept-Language: pl when foremen-locale is unset', async () => {
    installStorage()
    const fetchMock = installFetch(jsonResponse(200, {}))

    const { apiRequest } = await import('@/lib/api-client')
    await apiRequest('/api/some')

    expect(headersOf(fetchMock)['Accept-Language']).toBe('pl')
  })

  it('falls back to Accept-Language: pl for any non-ru locale value', async () => {
    installStorage({ 'foremen-locale': 'en' })
    const fetchMock = installFetch(jsonResponse(200, {}))

    const { apiRequest } = await import('@/lib/api-client')
    await apiRequest('/api/some')

    expect(headersOf(fetchMock)['Accept-Language']).toBe('pl')
  })
})

// --- Content-Type header (Req 2.4) ------------------------------------------

describe('Api_Client: Content-Type on JSON body (Req 2.4)', () => {
  it('sets Content-Type: application/json when a JSON body is present', async () => {
    installStorage()
    const fetchMock = installFetch(jsonResponse(200, {}))

    const { apiRequest } = await import('@/lib/api-client')
    await apiRequest('/api/some', { method: 'POST', body: { email: 'a@b.c' } })

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect((init.headers as Record<string, string>)['Content-Type']).toBe(
      'application/json',
    )
    // The body is serialized as JSON.
    expect(init.body).toBe(JSON.stringify({ email: 'a@b.c' }))
  })

  it('does not set Content-Type when there is no body', async () => {
    installStorage()
    const fetchMock = installFetch(jsonResponse(200, {}))

    const { apiRequest } = await import('@/lib/api-client')
    await apiRequest('/api/some')

    expect(headersOf(fetchMock)['Content-Type']).toBeUndefined()
  })
})

// --- If-None-Match pass-through (Req 13.2 header wiring, exercised via 13.7) -

describe('Api_Client: If-None-Match pass-through', () => {
  it('attaches If-None-Match when ifNoneMatch is supplied', async () => {
    installStorage()
    const fetchMock = installFetch(jsonResponse(200, {}))

    const { apiRequest } = await import('@/lib/api-client')
    await apiRequest('/api/auth/me', { ifNoneMatch: '"etag-abc"' })

    expect(headersOf(fetchMock)['If-None-Match']).toBe('"etag-abc"')
  })

  it('omits If-None-Match when ifNoneMatch is not supplied', async () => {
    installStorage()
    const fetchMock = installFetch(jsonResponse(200, {}))

    const { apiRequest } = await import('@/lib/api-client')
    await apiRequest('/api/auth/me')

    expect(headersOf(fetchMock)['If-None-Match']).toBeUndefined()
  })
})

// --- Error surface: verbatim backend message (Req 2.5) ----------------------

describe('Api_Client: verbatim backend message on non-OK (Req 2.5)', () => {
  it('throws ApiError carrying the status and the backend message verbatim', async () => {
    installStorage()
    installFetch(
      jsonResponse(401, {
        status: 401,
        error: 'Unauthorized',
        message: 'Nieprawidłowy e-mail lub hasło',
        path: '/api/auth/login',
      }),
    )

    const { apiRequest, ApiError } = await import('@/lib/api-client')

    await expect(apiRequest('/api/auth/login', { method: 'POST', body: {} })).rejects.toSatisfy(
      (err: unknown) => {
        expect(err).toBeInstanceOf(ApiError)
        const apiErr = err as InstanceType<typeof ApiError>
        expect(apiErr.status).toBe(401)
        expect(apiErr.message).toBe('Nieprawidłowy e-mail lub hasło')
        return true
      },
    )
  })

  it('surfaces the message code when the body exposes one', async () => {
    installStorage()
    installFetch(
      jsonResponse(403, {
        status: 403,
        message: 'Konto zostało dezaktywowane',
        code: 'error.auth.account.deactivated',
      }),
    )

    const { apiRequest, ApiError } = await import('@/lib/api-client')

    const err = await apiRequest('/api/auth/login', { method: 'POST', body: {} })
      .then(() => null)
      .catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    const apiErr = err as InstanceType<typeof ApiError>
    expect(apiErr.status).toBe(403)
    expect(apiErr.message).toBe('Konto zostało dezaktywowane')
    expect(apiErr.code).toBe('error.auth.account.deactivated')
  })
})

// --- Error surface: generic fallback on non-JSON body (Req 2.6) -------------

describe('Api_Client: generic fallback on a non-JSON body (Req 2.6)', () => {
  it('throws ApiError with the generic i18n fallback and does not throw a parse exception', async () => {
    installStorage()
    installFetch(nonJsonResponse(500))

    const { apiRequest, ApiError } = await import('@/lib/api-client')
    const i18n = (await import('@/lib/i18n')).default

    const err = await apiRequest('/api/some')
      .then(() => null)
      .catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    const apiErr = err as InstanceType<typeof ApiError>
    expect(apiErr.status).toBe(500)
    // The fallback is the resolved localized string, not the raw key.
    expect(apiErr.message).toBe(i18n.t('auth.error.generic'))
    expect(apiErr.message).not.toBe('auth.error.generic')
  })
})

// --- 304 conditional success sentinel (Req 13.7) ----------------------------

describe('Api_Client: 304 with parse304AsSuccess resolves NOT_MODIFIED (Req 13.7)', () => {
  it('resolves the NOT_MODIFIED sentinel (does not throw) on a 304', async () => {
    installStorage()
    installFetch(jsonResponse(304, undefined))

    const { apiRequest, NOT_MODIFIED } = await import('@/lib/api-client')

    const result = await apiRequest('/api/auth/me', {
      ifNoneMatch: '"etag-abc"',
      parse304AsSuccess: true,
    })

    expect(result).toBe(NOT_MODIFIED)
  })

  it('treats 304 as a non-OK error when parse304AsSuccess is not set', async () => {
    installStorage()
    installFetch(jsonResponse(304, { message: 'not modified' }))

    const { apiRequest, ApiError } = await import('@/lib/api-client')

    const err = await apiRequest('/api/auth/me')
      .then(() => null)
      .catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as InstanceType<typeof ApiError>).status).toBe(304)
  })
})
