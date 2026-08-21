import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi } from 'vitest'
import { BottomNav } from '@/app/layout/BottomNav'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

describe('BottomNav', () => {
  it('renders exactly 5 nav items (those with bottomNav: true)', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <BottomNav />
      </MemoryRouter>
    )
    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(5)
  })

  it('each item is a link element', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <BottomNav />
      </MemoryRouter>
    )
    const links = screen.getAllByRole('link')
    links.forEach((link) => {
      expect(link.tagName).toBe('A')
    })
  })
})
