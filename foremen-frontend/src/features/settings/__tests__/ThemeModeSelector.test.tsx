import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockSetThemeMode = vi.fn()

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.appearance.themeMode.title': 'Theme Mode',
        'settings.appearance.themeMode.dark': 'Dark',
        'settings.appearance.themeMode.light': 'Light',
        'settings.appearance.themeMode.system': 'System',
      }
      return translations[key] ?? key
    },
  }),
}))

vi.mock('@/stores/theme-store', () => ({
  useThemeStore: (selector: (state: unknown) => unknown) => {
    const state = {
      themeMode: 'dark',
      setThemeMode: mockSetThemeMode,
    }
    return selector(state)
  },
}))

import { ThemeModeSelector } from '../components/ThemeModeSelector'

describe('ThemeModeSelector', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders exactly 3 radio options (dark, light, system)', () => {
    render(<ThemeModeSelector />)

    const options = screen.getAllByRole('radio')
    expect(options).toHaveLength(3)
  })

  it('active option has aria-checked="true"', () => {
    render(<ThemeModeSelector />)

    const options = screen.getAllByRole('radio')
    const darkOption = options.find((opt) => opt.textContent?.includes('Dark'))
    expect(darkOption).toHaveAttribute('aria-checked', 'true')
  })

  it('non-active options have aria-checked="false"', () => {
    render(<ThemeModeSelector />)

    const options = screen.getAllByRole('radio')
    const nonActive = options.filter(
      (opt) => !opt.textContent?.includes('Dark'),
    )
    expect(nonActive).toHaveLength(2)
    nonActive.forEach((opt) => {
      expect(opt).toHaveAttribute('aria-checked', 'false')
    })
  })

  it('clicking an option calls setThemeMode with correct mode', () => {
    render(<ThemeModeSelector />)

    const lightOption = screen.getByText('Light').closest('button')!
    fireEvent.click(lightOption)
    expect(mockSetThemeMode).toHaveBeenCalledWith('light')

    const systemOption = screen.getByText('System').closest('button')!
    fireEvent.click(systemOption)
    expect(mockSetThemeMode).toHaveBeenCalledWith('system')
  })

  it('each option has correct i18n label text', () => {
    render(<ThemeModeSelector />)

    expect(screen.getByText('Dark')).toBeInTheDocument()
    expect(screen.getByText('Light')).toBeInTheDocument()
    expect(screen.getByText('System')).toBeInTheDocument()
  })
})
