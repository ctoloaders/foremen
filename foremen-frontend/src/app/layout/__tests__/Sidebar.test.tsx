import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { Sidebar } from '@/app/layout/Sidebar'
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

const ALL_ITEM_LABELS = NAV_CONFIG.flatMap((section) => section.items).map(
  (item) => item.labelKey
)
const ALL_SECTION_TITLE_KEYS = NAV_CONFIG.map((section) => section.titleKey).filter(
  (key): key is string => key != null
)

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

function renderSidebar() {
  // `visible` + not collapsed → the sidebar renders at full width with headers.
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Sidebar collapsed={false} visible={true} />
    </MemoryRouter>
  )
}

describe('Sidebar permission filtering (FOR-03-07)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  it('ADMIN sees every nav item and every section header (permission bypass)', () => {
    seedUser('ADMIN', [])
    renderSidebar()

    ALL_ITEM_LABELS.forEach((labelKey) => {
      expect(screen.getByText(labelKey)).toBeInTheDocument()
    })
    ALL_SECTION_TITLE_KEYS.forEach((titleKey) => {
      expect(screen.getByText(titleKey)).toBeInTheDocument()
    })
  })

  it('hides items the user is not granted', () => {
    // Granted only USERS:READ.
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['READ'] }])
    renderSidebar()

    // Granted + unrestricted items are visible.
    expect(screen.getByText('nav.users')).toBeInTheDocument()
    expect(screen.getByText('nav.dashboard')).toBeInTheDocument()
    expect(screen.getByText('nav.settings.appearance')).toBeInTheDocument()

    // Ungranted permission-gated items are hidden.
    expect(screen.queryByText('nav.projects')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.materials')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.roles')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.audit')).not.toBeInTheDocument()
  })

  it('suppresses a section header when all of its items are filtered out', () => {
    // Granted only USERS:READ: the "system" section keeps /users (visible),
    // but the "warehouse" section (materials/finances/deliveries) is fully
    // filtered out and its header must not render.
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['READ'] }])
    renderSidebar()

    // System section header shows (it still has a visible item).
    expect(screen.getByText('nav.sections.system')).toBeInTheDocument()
    // Warehouse section header is suppressed (no visible items).
    expect(screen.queryByText('nav.sections.warehouse')).not.toBeInTheDocument()
  })

  it('an authenticated role with no grants sees only the unrestricted items', () => {
    seedUser('CLIENT', [])
    renderSidebar()

    // Unrestricted items remain visible.
    expect(screen.getByText('nav.dashboard')).toBeInTheDocument()
    expect(screen.getByText('nav.settings.appearance')).toBeInTheDocument()

    // Every permission-gated item is hidden.
    expect(screen.queryByText('nav.users')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.projects')).not.toBeInTheDocument()

    // A section with only gated items (warehouse) has no header.
    expect(screen.queryByText('nav.sections.warehouse')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.sections.system')).not.toBeInTheDocument()
  })
})
