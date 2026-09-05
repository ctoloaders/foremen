import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { Drawer } from '@/app/layout/Drawer'
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

function renderDrawer() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Drawer open={true} onClose={() => {}} />
    </MemoryRouter>
  )
}

describe('Drawer permission filtering (FOR-03-07)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  it('ADMIN sees every nav item and every section header (permission bypass)', () => {
    seedUser('ADMIN', [])
    renderDrawer()

    ALL_ITEM_LABELS.forEach((labelKey) => {
      expect(screen.getByText(labelKey)).toBeInTheDocument()
    })
    ALL_SECTION_TITLE_KEYS.forEach((titleKey) => {
      expect(screen.getByText(titleKey)).toBeInTheDocument()
    })
  })

  it('hides items the user is not granted', () => {
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['READ'] }])
    renderDrawer()

    expect(screen.getByText('nav.users')).toBeInTheDocument()
    expect(screen.getByText('nav.dashboard')).toBeInTheDocument()
    expect(screen.getByText('nav.settings.appearance')).toBeInTheDocument()

    expect(screen.queryByText('nav.projects')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.materials')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.roles')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.audit')).not.toBeInTheDocument()
  })

  it('suppresses a section header when all of its items are filtered out', () => {
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['READ'] }])
    renderDrawer()

    expect(screen.getByText('nav.sections.system')).toBeInTheDocument()
    expect(screen.queryByText('nav.sections.warehouse')).not.toBeInTheDocument()
  })

  it('an authenticated role with no grants sees only the unrestricted items', () => {
    seedUser('CLIENT', [])
    renderDrawer()

    expect(screen.getByText('nav.dashboard')).toBeInTheDocument()
    expect(screen.getByText('nav.settings.appearance')).toBeInTheDocument()

    expect(screen.queryByText('nav.users')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.projects')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.sections.warehouse')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.sections.system')).not.toBeInTheDocument()
  })
})
