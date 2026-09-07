import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

describe('Sidebar Catalog & Dictionaries sections (FOR-04-15)', () => {
  // Every Catalog item label + its section header (Req 1.2, 1.5).
  const CATALOG_ITEM_LABELS = ['nav.workCatalog', 'nav.workPrices']
  // Every Dictionaries item label + its section header (Req 2.2, 2.5).
  const DICTIONARY_ITEM_LABELS = [
    'nav.measurementUnits',
    'nav.currencies',
    'nav.vatRates',
    'nav.roomTypes',
    'nav.workCategories',
    'nav.deliveryCategories',
    'nav.deliveryStatuses',
    'nav.materialCategories',
    'nav.offerPackages',
  ]

  beforeEach(() => {
    useAuthStore.setState({ user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  it('ADMIN sees both new section headers and all their items (Req 7.2)', () => {
    seedUser('ADMIN', [])
    renderSidebar()

    // Both new section headers render for the bypass role.
    expect(screen.getByText('nav.sections.catalog')).toBeInTheDocument()
    expect(screen.getByText('nav.sections.dictionaries')).toBeInTheDocument()

    // All Catalog + Dictionaries items are present.
    ;[...CATALOG_ITEM_LABELS, ...DICTIONARY_ITEM_LABELS].forEach((labelKey) => {
      expect(screen.getByText(labelKey)).toBeInTheDocument()
    })
  })

  it('a subset-granted role sees only granted items with the header shown (Req 1.5, 2.5, 7.2)', () => {
    // Granted only WORK_CATALOG:READ (of the Catalog pair) and a subset of
    // Dictionaries: CURRENCIES + ROOM_TYPES.
    seedUser('MANAGER', [
      { resource: 'WORK_CATALOG', operations: ['READ'] },
      { resource: 'CURRENCIES', operations: ['READ'] },
      { resource: 'ROOM_TYPES', operations: ['READ'] },
    ])
    renderSidebar()

    // Both headers still shown (each has at least one visible item).
    expect(screen.getByText('nav.sections.catalog')).toBeInTheDocument()
    expect(screen.getByText('nav.sections.dictionaries')).toBeInTheDocument()

    // Granted items are visible.
    expect(screen.getByText('nav.workCatalog')).toBeInTheDocument()
    expect(screen.getByText('nav.currencies')).toBeInTheDocument()
    expect(screen.getByText('nav.roomTypes')).toBeInTheDocument()

    // Non-granted items in the same sections are hidden.
    expect(screen.queryByText('nav.workPrices')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.measurementUnits')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.vatRates')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.workCategories')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.offerPackages')).not.toBeInTheDocument()
  })

  it('a role with no Catalog resource sees neither the Catalog header nor its items (Req 1.6, 7.3)', () => {
    // Granted a Dictionaries resource but nothing in the Catalog pair.
    seedUser('MANAGER', [{ resource: 'CURRENCIES', operations: ['READ'] }])
    renderSidebar()

    // Dictionaries still shows (has a visible item).
    expect(screen.getByText('nav.sections.dictionaries')).toBeInTheDocument()
    expect(screen.getByText('nav.currencies')).toBeInTheDocument()

    // Catalog section is fully suppressed: no header, no items.
    expect(screen.queryByText('nav.sections.catalog')).not.toBeInTheDocument()
    CATALOG_ITEM_LABELS.forEach((labelKey) => {
      expect(screen.queryByText(labelKey)).not.toBeInTheDocument()
    })
  })

  it('a role with no Dictionaries resource sees neither the Dictionaries header nor its items (Req 2.6, 7.3)', () => {
    // Granted a Catalog resource but none of the nine Dictionaries resources.
    seedUser('MANAGER', [{ resource: 'WORK_CATALOG', operations: ['READ'] }])
    renderSidebar()

    // Catalog still shows (has a visible item).
    expect(screen.getByText('nav.sections.catalog')).toBeInTheDocument()
    expect(screen.getByText('nav.workCatalog')).toBeInTheDocument()

    // Dictionaries section is fully suppressed: no header, no items.
    expect(screen.queryByText('nav.sections.dictionaries')).not.toBeInTheDocument()
    DICTIONARY_ITEM_LABELS.forEach((labelKey) => {
      expect(screen.queryByText(labelKey)).not.toBeInTheDocument()
    })
  })
})

describe('Sidebar collapsible Dictionaries section (FOR-04-15)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  it('renders the Dictionaries header as a focusable <button> with an accessible name (Req 3.1, 3.5, 7.4)', () => {
    seedUser('ADMIN', [])
    renderSidebar()

    // The toggle is a real <button> whose accessible name is the localized title.
    const toggle = screen.getByRole('button', { name: 'nav.sections.dictionaries' })
    expect(toggle.tagName).toBe('BUTTON')

    // Native <button> is focusable (keyboard operable via Enter/Space).
    toggle.focus()
    expect(toggle).toHaveFocus()
  })

  it('defaults to expanded with aria-expanded=true and its items visible (Req 3.1, 3.2)', () => {
    seedUser('ADMIN', [])
    renderSidebar()

    const toggle = screen.getByRole('button', { name: 'nav.sections.dictionaries' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('nav.measurementUnits')).toBeInTheDocument()
    expect(screen.getByText('nav.offerPackages')).toBeInTheDocument()
  })

  it('activating the header toggles the item list and flips aria-expanded (Req 3.2, 7.4)', async () => {
    const user = userEvent.setup()
    seedUser('ADMIN', [])
    renderSidebar()

    const toggle = screen.getByRole('button', { name: 'nav.sections.dictionaries' })

    // Collapse: items hidden, aria-expanded flips to false, header stays visible (Req 3.4).
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('nav.measurementUnits')).not.toBeInTheDocument()
    expect(screen.queryByText('nav.offerPackages')).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'nav.sections.dictionaries' })
    ).toBeInTheDocument()

    // Expand again: items reappear, aria-expanded back to true.
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('nav.measurementUnits')).toBeInTheDocument()
    expect(screen.getByText('nav.offerPackages')).toBeInTheDocument()
  })

  it('is keyboard operable — Enter and Space activate the toggle (Req 3.5)', async () => {
    const user = userEvent.setup()
    seedUser('ADMIN', [])
    renderSidebar()

    const toggle = screen.getByRole('button', { name: 'nav.sections.dictionaries' })
    toggle.focus()

    // Space collapses.
    await user.keyboard(' ')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('nav.measurementUnits')).not.toBeInTheDocument()

    // Enter expands.
    await user.keyboard('{Enter}')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('nav.measurementUnits')).toBeInTheDocument()
  })

  it('other section headers are NOT toggles (Req 3.3)', () => {
    seedUser('ADMIN', [])
    renderSidebar()

    // The other section headers render as plain (non-button) text.
    ;['nav.sections.catalog', 'nav.sections.warehouse', 'nav.sections.system', 'nav.sections.settings'].forEach(
      (titleKey) => {
        expect(
          screen.queryByRole('button', { name: titleKey })
        ).not.toBeInTheDocument()
        expect(screen.getByText(titleKey)).toBeInTheDocument()
      }
    )

    // Exactly one section header is a toggle button — the Dictionaries one.
    expect(
      screen.getByRole('button', { name: 'nav.sections.dictionaries' })
    ).toBeInTheDocument()
  })
})
