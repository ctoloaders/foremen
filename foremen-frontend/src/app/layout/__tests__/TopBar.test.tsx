import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { TopBar } from '@/app/layout/TopBar'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { changeLanguage: vi.fn() },
  }),
}))

const mockSetLocale = vi.fn()
const mockToggleSidebar = vi.fn()

vi.mock('@/stores/ui-store', () => ({
  useUIStore: vi.fn((selector: (state: unknown) => unknown) => {
    const state = {
      locale: 'pl',
      setLocale: mockSetLocale,
      toggleSidebar: mockToggleSidebar,
      sidebarOpen: true,
    }
    return selector(state)
  }),
}))

vi.mock('@/hooks/usePageMeta', () => ({
  usePageMeta: () => ({ titleKey: 'nav.dashboard', action: undefined }),
}))

let mockBreakpoint = 'desktop'
vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockBreakpoint,
}))

describe('TopBar', () => {
  beforeEach(() => {
    mockSetLocale.mockClear()
    mockToggleSidebar.mockClear()
    mockBreakpoint = 'desktop'
  })

  it('renders the page title', () => {
    render(
      <MemoryRouter>
        <TopBar />
      </MemoryRouter>
    )
    expect(screen.getByText('nav.dashboard')).toBeInTheDocument()
  })

  it('clicking language button calls setLocale with "ru" (opposite of "pl")', () => {
    render(
      <MemoryRouter>
        <TopBar />
      </MemoryRouter>
    )
    const langButton = screen.getByText('PL')
    fireEvent.click(langButton)
    expect(mockSetLocale).toHaveBeenCalledWith('ru')
  })

  it('on mobile, hamburger button is present', () => {
    mockBreakpoint = 'mobile'
    render(
      <MemoryRouter>
        <TopBar />
      </MemoryRouter>
    )
    expect(screen.getByLabelText('Menu')).toBeInTheDocument()
  })
})
