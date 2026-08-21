import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi } from 'vitest'
import { NavItem } from '@/app/layout/NavItem'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

function renderNavItem(props: Partial<React.ComponentProps<typeof NavItem>> = {}) {
  const defaultProps = {
    icon: 'layout-dashboard',
    labelKey: 'nav.dashboard',
    path: '/',
    active: false,
    ...props,
  }
  return render(
    <MemoryRouter>
      <NavItem {...defaultProps} />
    </MemoryRouter>
  )
}

describe('NavItem', () => {
  it('renders the label key text', () => {
    renderNavItem({ labelKey: 'nav.projects' })
    expect(screen.getByText('nav.projects')).toBeInTheDocument()
  })

  it('active=true applies active CSS classes (bg-secondary)', () => {
    renderNavItem({ active: true })
    const link = screen.getByRole('link')
    expect(link.className).toMatch(/bg-secondary/)
  })

  it('collapsed=true hides label text', () => {
    renderNavItem({ collapsed: true })
    expect(screen.queryByText('nav.dashboard')).not.toBeInTheDocument()
  })

  it('variant="bottom-nav" renders with min-h-[44px]', () => {
    renderNavItem({ variant: 'bottom-nav' })
    const link = screen.getByRole('link')
    expect(link.className).toMatch(/min-h-\[44px\]/)
  })
})
