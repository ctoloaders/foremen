import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { BottomNav } from '@/app/layout/BottomNav'
import { NAV_CONFIG } from '@/config/navigation'
import type { CurrentUser } from '@/stores/auth-store'
import { useAuthStore } from '@/stores/auth-store'

// Keep the real module (i18n.ts wires initReactI18next, pulled in transitively
// via the Auth_Store → api-client → i18n import chain) but override
// useTranslation so `t` returns the key verbatim for assertions.
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({ t: (key: string) => key, i18n: { changeLanguage: vi.fn() } }),
  }
})

/** The bottom-nav items an ADMIN (all permissions) would see — the full set. */
const ALL_BOTTOM_NAV_ITEMS = NAV_CONFIG.flatMap((section) => section.items).filter(
  (item) => item.bottomNav
)

/** Seeds the Auth_Store with a Current_User so `usePermission()` resolves grants. */
function seedUser(roleCode: string, permissions: CurrentUser['permissions']): void {
  useAuthStore.setState({
    user: {
      id: 1,
      name: 'Test User',
      email: 'test@example.com',
      roleCode,
      permissions,
    },
  })
}

function renderBottomNav() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <BottomNav />
    </MemoryRouter>
  )
}

describe('BottomNav', () => {
  beforeEach(() => {
    act(() => useAuthStore.setState({ user: null }))
  })

  afterEach(() => {
    act(() => useAuthStore.setState({ user: null }))
  })

  it('ADMIN sees every bottomNav item (permission bypass)', () => {
    seedUser('ADMIN', [])
    renderBottomNav()

    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(ALL_BOTTOM_NAV_ITEMS.length)
    // Every configured bottom-nav label is rendered for an ADMIN.
    ALL_BOTTOM_NAV_ITEMS.forEach((item) => {
      expect(screen.getByText(item.labelKey)).toBeInTheDocument()
    })
  })

  it('a limited role sees only the unrestricted + granted bottomNav items (a subset)', () => {
    // Granted only USERS:READ → sees the unrestricted dashboard and /users, but
    // not the other permission-gated bottom-nav items.
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['READ'] }])
    renderBottomNav()

    const links = screen.getAllByRole('link')
    // Strictly fewer than the full ADMIN set.
    expect(links.length).toBeLessThan(ALL_BOTTOM_NAV_ITEMS.length)

    // The unrestricted dashboard and the granted /users item are present.
    expect(screen.getByText('nav.dashboard')).toBeInTheDocument()
    expect(screen.getByText('nav.users')).toBeInTheDocument()

    // A permission-gated item the role lacks is not rendered.
    expect(screen.queryByText('nav.projects')).not.toBeInTheDocument()
  })

  it('an authenticated role with no grants sees only unrestricted bottomNav items', () => {
    seedUser('CLIENT', [])
    renderBottomNav()

    const unrestrictedCount = ALL_BOTTOM_NAV_ITEMS.filter(
      (item) => item.requiredPermission == null
    ).length

    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(unrestrictedCount)
    expect(screen.getByText('nav.dashboard')).toBeInTheDocument()
    expect(screen.queryByText('nav.users')).not.toBeInTheDocument()
  })

  it('each rendered item is a link element', () => {
    seedUser('ADMIN', [])
    renderBottomNav()

    const links = screen.getAllByRole('link')
    expect(links.length).toBeGreaterThan(0)
    links.forEach((link) => {
      expect(link.tagName).toBe('A')
    })
  })
})
