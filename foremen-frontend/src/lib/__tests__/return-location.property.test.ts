// Feature: FOR-03-06-frontend-auth, Property 4: Return_Location round-trips for protected paths and is never a public route
import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fc from 'fast-check'
import {
  captureReturnLocation,
  consumeReturnLocation,
  clearReturnLocation,
} from '@/lib/return-location'

// --- Mocks ---

beforeEach(() => {
  // In-memory sessionStorage so capture/consume never touch the real one and
  // each property run starts from a clean, isolated store.
  const store: Record<string, string> = {}
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => {
      store[key] = value
    },
    removeItem: (key: string) => {
      delete store[key]
    },
    clear: () => {
      Object.keys(store).forEach((k) => delete store[k])
    },
  })
})

// --- Arbitraries ---

/** The three Public_Routes that must never be captured as a Return_Location. */
const PUBLIC_ROUTES = ['/login', '/auth/set-password', '/auth/otp'] as const

/**
 * An arbitrary protected path-plus-query: a non-public leading path segment,
 * optional deeper segments, and an optional query string / hash. Constrained so
 * the path portion is never one of the exact Public_Routes.
 */
const segmentArb = fc
  .string({ minLength: 1, maxLength: 12 })
  .map((s) => s.replace(/[/?#]/g, '')) // keep segments free of delimiters
  .filter((s) => s.length > 0)

const protectedPathArb: fc.Arbitrary<string> = fc
  .tuple(
    fc.array(segmentArb, { minLength: 1, maxLength: 4 }),
    fc.option(fc.string({ maxLength: 20 }).map((q) => q.replace(/#/g, '')), {
      nil: '',
    }),
    fc.option(fc.string({ maxLength: 10 }), { nil: '' }),
  )
  .map(([segments, query, hash]) => {
    const path = '/' + segments.join('/')
    const q = query ? `?${query}` : ''
    const h = hash ? `#${hash}` : ''
    return path + q + h
  })
  .filter((full) => {
    // Ensure the *path portion* is not an exact Public_Route.
    const path = full.split(/[?#]/)[0] ?? full
    return !(PUBLIC_ROUTES as readonly string[]).includes(path)
  })

/**
 * An arbitrary public-route path: one of the exact Public_Routes with an
 * optional query string and/or hash appended (the exclusion is on the path
 * portion, so a query must not defeat it).
 */
const publicPathArb: fc.Arbitrary<string> = fc
  .tuple(
    fc.constantFrom(...PUBLIC_ROUTES),
    fc.option(fc.string({ maxLength: 20 }).map((q) => q.replace(/#/g, '')), {
      nil: '',
    }),
    fc.option(fc.string({ maxLength: 10 }), { nil: '' }),
  )
  .map(([path, query, hash]) => {
    const q = query ? `?${query}` : ''
    const h = hash ? `#${hash}` : ''
    return path + q + h
  })

/**
 * Feature: FOR-03-06-frontend-auth, Property 4: Return_Location round-trips for
 * protected paths and is never a public route.
 *
 * For any protected path: capture then consume returns exactly the same value,
 * and a second consume returns null (read-once). For any public-route path:
 * capture is a no-op, so consume returns null.
 *
 * **Validates: Requirements 3.8, 9.6, 12.1, 12.3, 12.4, 12.5, 12.6**
 */
describe('Feature: FOR-03-06-frontend-auth, Property 4: Return_Location round-trips for protected paths and is never a public route', () => {
  it('captures and returns a protected path verbatim, then null on the second consume', () => {
    fc.assert(
      fc.property(protectedPathArb, (path) => {
        clearReturnLocation()

        captureReturnLocation(path)

        // Round-trip: consume returns the same value that was captured.
        expect(consumeReturnLocation()).toBe(path)

        // Read-once: a second consume yields null (already cleared).
        expect(consumeReturnLocation()).toBeNull()
      }),
      { numRuns: 100 },
    )
  })

  it('never captures a public route: consume returns null after capturing a public path', () => {
    fc.assert(
      fc.property(publicPathArb, (path) => {
        clearReturnLocation()

        captureReturnLocation(path)

        // A Public_Route is never stored, so there is nothing to return.
        expect(consumeReturnLocation()).toBeNull()
      }),
      { numRuns: 100 },
    )
  })

  it('a public capture does not overwrite a previously captured protected path', () => {
    fc.assert(
      fc.property(protectedPathArb, publicPathArb, (protectedPath, publicPath) => {
        clearReturnLocation()

        captureReturnLocation(protectedPath)
        // A subsequent public-route capture is a no-op and must not clobber it.
        captureReturnLocation(publicPath)

        expect(consumeReturnLocation()).toBe(protectedPath)
        expect(consumeReturnLocation()).toBeNull()
      }),
      { numRuns: 100 },
    )
  })
})
