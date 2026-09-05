// Feature: FOR-03-07-menu-visibility, Property 6: The route guard renders iff the requirement is absent or granted
import { render, screen, cleanup } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fc from 'fast-check'

import { PermissionGuard } from '@/app/guards/PermissionGuard'
import { requirementForPath } from '@/config/route-permissions'
import type { CurrentUser } from '@/stores/auth-store'
import { useAuthStore } from '@/stores/auth-store'
import {
  getLastAllowedLocation,
  getDeniedLocation,
  recordAllowedLocation,
  recordDeniedLocation,
} from '@/lib/last-allowed-location'
import { captureReturnLocation } from '@/lib/return-location'

// Return_Location (FOR-03-06): mock so we can assert the `/403` redirect NEVER
// captures a Return_Location (Req 4.7), without touching real sessionStorage.
vi.mock('@/lib/return-location', () => ({
  captureReturnLocation: vi.fn(),
  consumeReturnLocation: vi.fn(() => null),
  clearReturnLocation: vi.fn(),
}))

const mockedCapture = vi.mocked(captureReturnLocation)

// --- Guarded paths + their known requirements --------------------------------

// Paths WITH a requirement (from the Route_Requirement_Map, derived from
// NAV_CONFIG). Verified live via requirementForPath so the test stays in sync
// with the config rather than hardcoding the matrix.
const GUARDED_PATHS = [
  '/users',
  '/roles',
  '/audit',
  '/projects',
  '/rooms',
  '/estimate',
  '/materials',
  '/finances',
  '/deliveries',
] as const

// Paths WITHOUT a requirement — authenticated access is sufficient.
const UNRESTRICTED_PATHS = ['/', '/settings/appearance', '/403', '/anything-unmapped'] as const

// --- Test rendering harness --------------------------------------------------

/**
 * Renders the PermissionGuard as a layout route wrapping a set of protected
 * child routes so `<Outlet/>` renders a detectable page sentinel, and a
 * separate `/403` route so a `<Navigate to="/403">` redirect resolves to its
 * own detectable sentinel. The guard reads the *matched* location, exactly as
 * in the real router.
 */
function renderGuardAt(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<PermissionGuard />}>
          {/* The forbidden page: has no requirement, so the guard renders it. */}
          <Route path="/403" element={<div data-testid="forbidden-page">403</div>} />
          {/* All other paths render a generic "page" sentinel via the Outlet. */}
          <Route path="*" element={<div data-testid="protected-page">page</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

function seedUser(roleCode: string, permissions: CurrentUser['permissions']): void {
  useAuthStore.setState({
    user: { id: 1, name: 'Test User', email: 'test@example.com', roleCode, permissions },
  })
}

/** True when the guard rendered the protected page (Outlet) rather than /403. */
function renderedProtectedPage(): boolean {
  return screen.queryByTestId('protected-page') != null
}

/** True when the guard redirected to the /403 forbidden page. */
function redirectedTo403(): boolean {
  return screen.queryByTestId('forbidden-page') != null
}

// --- Setup -------------------------------------------------------------------

beforeEach(() => {
  mockedCapture.mockClear()
  useAuthStore.setState({ user: null })
  // Baseline the tracking module so assertions about writes are meaningful.
  recordAllowedLocation('/__baseline__')
  recordDeniedLocation('/__baseline_denied__')
})

afterEach(() => {
  cleanup()
  useAuthStore.setState({ user: null })
})

// --- Arbitraries -------------------------------------------------------------

/** All guarded resource codes present in the map, for generating grant sets. */
const RESOURCE_CODES = [
  'USERS',
  'ROLES',
  'AUDIT',
  'PROJECTS',
  'ROOMS',
  'ESTIMATE',
  'MATERIALS',
  'FINANCES',
  'DELIVERIES',
] as const

const OPERATIONS = ['CREATE', 'READ', 'UPDATE', 'DELETE'] as const

/** An arbitrary permissions array over the known resource/operation codes. */
const permissionsArb: fc.Arbitrary<CurrentUser['permissions']> = fc.array(
  fc.record({
    resource: fc.constantFrom(...RESOURCE_CODES),
    operations: fc.array(fc.constantFrom(...OPERATIONS), { maxLength: 4 }),
  }),
  { maxLength: 6 },
)

/** Any path the guard may see: guarded or unrestricted. */
const anyPathArb = fc.constantFrom(...GUARDED_PATHS, ...UNRESTRICTED_PATHS)

/**
 * The independent oracle for "does this authenticated user's grant satisfy the
 * path requirement" — mirrors the acceptance criteria without reusing the
 * guard's own hook.
 */
function oracleGranted(
  roleCode: string,
  permissions: CurrentUser['permissions'],
  path: string,
): boolean {
  const requirement = requirementForPath(path)
  if (requirement == null) return true // no requirement → always renders
  if (roleCode === 'ADMIN') return true // ADMIN bypass
  return permissions.some(
    (p) => p.resource === requirement.resource && p.operations.includes(requirement.operation),
  )
}

// --- Property 6 --------------------------------------------------------------

/**
 * Feature: FOR-03-07-menu-visibility, Property 6: The route guard renders iff
 * the requirement is absent or granted.
 *
 * For any protected route path and any authenticated Current_User, the
 * PermissionGuard renders the route (Outlet) if and only if the path has no
 * requirement OR hasPermission grants it; otherwise it redirects to `/403`.
 *
 * **Validates: Requirements 4.2, 4.3, 4.4, 4.6**
 */
describe('Feature: FOR-03-07-menu-visibility, Property 6: The route guard renders iff the requirement is absent or granted', () => {
  it('renders the outlet iff (no requirement OR granted); otherwise redirects to /403', () => {
    fc.assert(
      fc.property(
        fc.constantFrom<string>('CLIENT', 'FOREMAN', 'MANAGER'),
        permissionsArb,
        anyPathArb,
        (roleCode, permissions, path) => {
          seedUser(roleCode, permissions)
          const { unmount } = renderGuardAt(path)
          try {
            const expectRender = oracleGranted(roleCode, permissions, path)
            if (expectRender) {
              // Granted / no-requirement → the matched page renders via Outlet.
              // (For `/403` the "protected-page" sentinel is not used; its own
              // forbidden-page sentinel is — either way it is NOT a redirect
              // triggered by denial, which is what this property asserts.)
              if (path === '/403') {
                expect(redirectedTo403()).toBe(true)
              } else {
                expect(renderedProtectedPage()).toBe(true)
              }
            } else {
              // Denied → redirect to /403.
              expect(redirectedTo403()).toBe(true)
              expect(renderedProtectedPage()).toBe(false)
            }
          } finally {
            unmount()
          }
        },
      ),
      { numRuns: 100 },
    )
  })

  it('ADMIN always renders the route for every path (ADMIN bypass)', () => {
    fc.assert(
      fc.property(anyPathArb, (path) => {
        seedUser('ADMIN', [])
        const { unmount } = renderGuardAt(path)
        try {
          if (path === '/403') {
            expect(redirectedTo403()).toBe(true)
          } else {
            expect(renderedProtectedPage()).toBe(true)
          }
        } finally {
          unmount()
        }
      }),
      { numRuns: 100 },
    )
  })
})

// --- Example / unit tests ----------------------------------------------------

describe('PermissionGuard (example cases)', () => {
  it('renders the route when it has no requirement, and records it as Last_Allowed_Location (Req 4.4)', () => {
    seedUser('CLIENT', [])
    renderGuardAt('/settings/appearance')

    expect(renderedProtectedPage()).toBe(true)
    expect(redirectedTo403()).toBe(false)
    expect(getLastAllowedLocation()).toBe('/settings/appearance')
  })

  it('renders a granted route and records it as Last_Allowed_Location (Req 4.3)', () => {
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['READ'] }])
    renderGuardAt('/users')

    expect(renderedProtectedPage()).toBe(true)
    expect(redirectedTo403()).toBe(false)
    expect(getLastAllowedLocation()).toBe('/users')
  })

  it('records the full path+query of a granted route (Req 5.8)', () => {
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['READ'] }])
    renderGuardAt('/users?page=3&sort=name')

    expect(renderedProtectedPage()).toBe(true)
    expect(getLastAllowedLocation()).toBe('/users?page=3&sort=name')
  })

  it('denies an ungranted route, redirects to /403, and records it as Denied_Location (Req 4.2, 4.8)', () => {
    seedUser('CLIENT', []) // no grants
    renderGuardAt('/users')

    expect(redirectedTo403()).toBe(true)
    expect(renderedProtectedPage()).toBe(false)
    expect(getDeniedLocation()).toBe('/users')
  })

  it('never records the /403 page itself as a Last_Allowed_Location (Req 4.4)', () => {
    // Seed a known prior allowed location, then navigate directly to /403.
    recordAllowedLocation('/roles')
    seedUser('MANAGER', [{ resource: 'ROLES', operations: ['READ'] }])
    renderGuardAt('/403')

    // /403 renders (no requirement) but the tracking module ignores it, so the
    // last allowed location stays the earlier page.
    expect(redirectedTo403()).toBe(true)
    expect(getLastAllowedLocation()).toBe('/roles')
  })

  it('ADMIN renders a permission-guarded route (ADMIN bypass, Req 4.6)', () => {
    seedUser('ADMIN', [])
    renderGuardAt('/audit')

    expect(renderedProtectedPage()).toBe(true)
    expect(redirectedTo403()).toBe(false)
    expect(getLastAllowedLocation()).toBe('/audit')
  })

  it('does NOT write a FOR-03-06 Return_Location on the /403 redirect (Req 4.7)', () => {
    seedUser('CLIENT', []) // denied on /users
    renderGuardAt('/users')

    expect(redirectedTo403()).toBe(true)
    // The denial path is purely a PermissionGuard concern; ProtectedLayout owns
    // Return_Location and only for the unauthenticated /login redirect.
    expect(mockedCapture).not.toHaveBeenCalled()
  })

  it('does NOT write a Return_Location even when rendering a granted route', () => {
    seedUser('ADMIN', [])
    renderGuardAt('/users')

    expect(renderedProtectedPage()).toBe(true)
    expect(mockedCapture).not.toHaveBeenCalled()
  })
})
