import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockSetColorScheme = vi.fn()

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.appearance.colorScheme.title': 'Color Scheme',
        'settings.appearance.colorScheme.zinc': 'Zinc',
        'settings.appearance.colorScheme.slate': 'Slate',
        'settings.appearance.colorScheme.stone': 'Stone',
        'settings.appearance.colorScheme.gray': 'Gray',
        'settings.appearance.colorScheme.neutral': 'Neutral',
        'settings.appearance.colorScheme.blue': 'Blue',
        'settings.appearance.colorScheme.green': 'Green',
        'settings.appearance.colorScheme.orange': 'Orange',
        'settings.appearance.colorScheme.red': 'Red',
      }
      return translations[key] ?? key
    },
  }),
}))

vi.mock('@/stores/theme-store', () => ({
  useThemeStore: (selector: (state: unknown) => unknown) => {
    const state = {
      colorScheme: 'zinc',
      setColorScheme: mockSetColorScheme,
    }
    return selector(state)
  },
}))

import { ColorSchemeSelector } from '../components/ColorSchemeSelector'

describe('ColorSchemeSelector', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders 9 swatch buttons (one for each preset)', () => {
    render(<ColorSchemeSelector />)

    const swatches = screen.getAllByRole('radio')
    expect(swatches).toHaveLength(9)
  })

  it('active swatch has aria-checked="true" and shows check icon', () => {
    render(<ColorSchemeSelector />)

    // "zinc" is the active preset
    const activeButton = screen.getByRole('radio', { name: 'Zinc' })
    expect(activeButton).toHaveAttribute('aria-checked', 'true')

    // The Check icon is rendered inside the active swatch (aria-hidden SVG)
    const svg = activeButton.querySelector('svg')
    expect(svg).toBeInTheDocument()
  })

  it('non-active swatches have aria-checked="false" and no check icon', () => {
    render(<ColorSchemeSelector />)

    const allSwatches = screen.getAllByRole('radio')
    const nonActive = allSwatches.filter(
      (s) => s.getAttribute('aria-checked') === 'false',
    )
    expect(nonActive).toHaveLength(8)

    nonActive.forEach((swatch) => {
      const svg = swatch.querySelector('svg')
      expect(svg).not.toBeInTheDocument()
    })
  })

  it('clicking a non-active swatch calls setColorScheme with correct name', () => {
    render(<ColorSchemeSelector />)

    const blueButton = screen.getByRole('radio', { name: 'Blue' })
    fireEvent.click(blueButton)
    expect(mockSetColorScheme).toHaveBeenCalledWith('blue')
  })

  it('clicking the already-active swatch does NOT call setColorScheme (requirement 3.8)', () => {
    render(<ColorSchemeSelector />)

    const activeButton = screen.getByRole('radio', { name: 'Zinc' })
    fireEvent.click(activeButton)
    expect(mockSetColorScheme).not.toHaveBeenCalled()
  })

  it('swatches have min 44x44px touch target via className', () => {
    render(<ColorSchemeSelector />)

    const swatches = screen.getAllByRole('radio')
    swatches.forEach((swatch) => {
      expect(swatch).toHaveClass('min-h-[44px]')
      expect(swatch).toHaveClass('min-w-[44px]')
    })
  })
})
