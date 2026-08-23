import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'users.badge.active': 'Active',
        'users.badge.inactive': 'Inactive',
      }
      return translations[key] ?? key
    },
  }),
}))

import { ActiveBadge } from '../components/ActiveBadge'

describe('ActiveBadge', () => {
  it('renders green badge with "Active" text when active=true', () => {
    render(<ActiveBadge active={true} />)

    const badge = screen.getByText('Active')
    expect(badge).toBeInTheDocument()
    expect(badge).toHaveClass('bg-[#22c55e]/15')
    expect(badge).toHaveClass('text-[#22c55e]')
  })

  it('renders muted badge with "Inactive" text when active=false', () => {
    render(<ActiveBadge active={false} />)

    const badge = screen.getByText('Inactive')
    expect(badge).toBeInTheDocument()
    // secondary variant uses muted/secondary styling — no green classes
    expect(badge).not.toHaveClass('bg-[#22c55e]/15')
    expect(badge).not.toHaveClass('text-[#22c55e]')
  })
})
