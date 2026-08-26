import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockSetFontSize = vi.fn()

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.appearance.fontSize.title': 'Font Size',
        'settings.appearance.fontSize.sm': 'Small',
        'settings.appearance.fontSize.default': 'Default',
        'settings.appearance.fontSize.lg': 'Large',
        'settings.appearance.fontSize.xl': 'Extra Large',
      }
      return translations[key] ?? key
    },
  }),
}))

vi.mock('@/stores/theme-store', () => ({
  useThemeStore: (selector: (state: unknown) => unknown) => {
    const state = {
      fontSize: 'default',
      setFontSize: mockSetFontSize,
    }
    return selector(state)
  },
}))

import { FontSizeSelector } from '../components/FontSizeSelector'

describe('FontSizeSelector', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders exactly 4 radio options (sm, default, lg, xl)', () => {
    render(<FontSizeSelector />)

    const options = screen.getAllByRole('radio')
    expect(options).toHaveLength(4)
  })

  it('active option has aria-checked="true"', () => {
    render(<FontSizeSelector />)

    // "default" is the active font size
    const options = screen.getAllByRole('radio')
    const activeOption = options.find((opt) =>
      opt.textContent?.includes('Default'),
    )
    expect(activeOption).toHaveAttribute('aria-checked', 'true')
  })

  it('non-active options have aria-checked="false"', () => {
    render(<FontSizeSelector />)

    const options = screen.getAllByRole('radio')
    const nonActive = options.filter(
      (opt) => !opt.textContent?.includes('Default'),
    )
    expect(nonActive).toHaveLength(3)
    nonActive.forEach((opt) => {
      expect(opt).toHaveAttribute('aria-checked', 'false')
    })
  })

  it('clicking an option calls setFontSize with correct size', () => {
    render(<FontSizeSelector />)

    const smOption = screen.getByText('Small').closest('button')!
    fireEvent.click(smOption)
    expect(mockSetFontSize).toHaveBeenCalledWith('sm')

    const lgOption = screen.getByText('Large').closest('button')!
    fireEvent.click(lgOption)
    expect(mockSetFontSize).toHaveBeenCalledWith('lg')

    const xlOption = screen.getByText('Extra Large').closest('button')!
    fireEvent.click(xlOption)
    expect(mockSetFontSize).toHaveBeenCalledWith('xl')
  })

  it('each option shows pixel value as secondary text (12px, 14px, 16px, 18px)', () => {
    render(<FontSizeSelector />)

    expect(screen.getByText('12px')).toBeInTheDocument()
    expect(screen.getByText('14px')).toBeInTheDocument()
    expect(screen.getByText('16px')).toBeInTheDocument()
    expect(screen.getByText('18px')).toBeInTheDocument()
  })
})
