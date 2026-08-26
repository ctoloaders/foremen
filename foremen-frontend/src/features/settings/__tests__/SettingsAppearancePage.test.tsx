import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'

// --- Mock state ---

let mockHasUnsavedChanges = false
let mockIsPending = false
const mockMutate = vi.fn()
const mockRevertToSaved = vi.fn()
const mockUpdateSavedPreferences = vi.fn()

// --- i18n Mock ---

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.appearance.themeMode.title': 'Theme Mode',
        'settings.appearance.themeMode.description': 'Choose your preferred theme mode',
        'settings.appearance.colorScheme.title': 'Color Scheme',
        'settings.appearance.colorScheme.description': 'Choose your preferred color scheme',
        'settings.appearance.fontSize.title': 'Font Size',
        'settings.appearance.fontSize.description': 'Choose your preferred font size',
        'settings.appearance.save': 'Save',
        'settings.appearance.reset': 'Reset',
        'settings.appearance.toast.success': 'Preferences saved',
        'settings.appearance.toast.error': 'Failed to save preferences',
        'common.loading': 'Loading',
      }
      return translations[key] ?? key
    },
  }),
}))

// --- Sonner mock ---

const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()

vi.mock('sonner', () => ({
  toast: {
    success: (...args: unknown[]) => mockToastSuccess(...args),
    error: (...args: unknown[]) => mockToastError(...args),
  },
}))

// --- Mock theme-store ---

vi.mock('@/stores/theme-store', () => ({
  useThemeStore: (selector: (state: unknown) => unknown) => {
    const state = {
      themeMode: 'dark',
      colorScheme: 'zinc',
      fontSize: 'default',
      hasUnsavedChanges: () => mockHasUnsavedChanges,
      updateSavedPreferences: mockUpdateSavedPreferences,
      revertToSaved: mockRevertToSaved,
    }
    return selector(state)
  },
}))

// --- Mock mutation hooks ---

vi.mock('../api/mutation-hooks', () => ({
  useSavePreferences: () => ({
    mutate: mockMutate,
    isPending: mockIsPending,
  }),
}))

// --- Mock child components to isolate page logic ---

vi.mock('../components/ThemeModeSelector', () => ({
  ThemeModeSelector: () => <div data-testid="theme-mode-selector">ThemeModeSelector</div>,
}))

vi.mock('../components/ColorSchemeSelector', () => ({
  ColorSchemeSelector: () => <div data-testid="color-scheme-selector">ColorSchemeSelector</div>,
}))

vi.mock('../components/FontSizeSelector', () => ({
  FontSizeSelector: () => <div data-testid="font-size-selector">FontSizeSelector</div>,
}))

// --- Import component after mocks ---

import SettingsAppearancePage from '../SettingsAppearancePage'

// --- Tests ---

describe('SettingsAppearancePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockHasUnsavedChanges = false
    mockIsPending = false
  })

  describe('Section rendering', () => {
    it('renders Theme Mode section with heading and description', () => {
      render(<SettingsAppearancePage />)

      expect(screen.getByText('Theme Mode')).toBeInTheDocument()
      expect(screen.getByText('Choose your preferred theme mode')).toBeInTheDocument()
      expect(screen.getByTestId('theme-mode-selector')).toBeInTheDocument()
    })

    it('renders Color Scheme section with heading and description', () => {
      render(<SettingsAppearancePage />)

      expect(screen.getByText('Color Scheme')).toBeInTheDocument()
      expect(screen.getByText('Choose your preferred color scheme')).toBeInTheDocument()
      expect(screen.getByTestId('color-scheme-selector')).toBeInTheDocument()
    })

    it('renders Font Size section with heading and description', () => {
      render(<SettingsAppearancePage />)

      expect(screen.getByText('Font Size')).toBeInTheDocument()
      expect(screen.getByText('Choose your preferred font size')).toBeInTheDocument()
      expect(screen.getByTestId('font-size-selector')).toBeInTheDocument()
    })

    it('renders all three sections', () => {
      render(<SettingsAppearancePage />)

      const sections = screen.getAllByRole('heading', { level: 2 })
      expect(sections).toHaveLength(3)
      expect(sections[0]).toHaveTextContent('Theme Mode')
      expect(sections[1]).toHaveTextContent('Color Scheme')
      expect(sections[2]).toHaveTextContent('Font Size')
    })
  })

  describe('Save button', () => {
    it('is disabled when there are no unsaved changes', () => {
      mockHasUnsavedChanges = false
      render(<SettingsAppearancePage />)

      const saveButton = screen.getByText('Save')
      expect(saveButton).toBeDisabled()
    })

    it('is enabled when there are unsaved changes', () => {
      mockHasUnsavedChanges = true
      render(<SettingsAppearancePage />)

      const saveButton = screen.getByText('Save')
      expect(saveButton).not.toBeDisabled()
    })

    it('is disabled while PATCH is pending (loading state)', () => {
      mockHasUnsavedChanges = true
      mockIsPending = true
      render(<SettingsAppearancePage />)

      const saveButton = screen.getByText('Save')
      expect(saveButton).toBeDisabled()
    })

    it('shows loading spinner while PATCH is pending', () => {
      mockHasUnsavedChanges = true
      mockIsPending = true
      render(<SettingsAppearancePage />)

      // The spinner SVG should be present when isPending is true
      const spinner = screen.getByRole('img')
      expect(spinner).toBeInTheDocument()
    })

    it('calls mutate with current preferences on click', () => {
      mockHasUnsavedChanges = true
      render(<SettingsAppearancePage />)

      const saveButton = screen.getByText('Save')
      fireEvent.click(saveButton)

      expect(mockMutate).toHaveBeenCalledWith(
        { themeMode: 'dark', colorScheme: 'zinc', fontSize: 'default' },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        }),
      )
    })
  })

  describe('Save success', () => {
    it('shows success toast on save success', () => {
      mockHasUnsavedChanges = true
      mockMutate.mockImplementation(
        (_data: unknown, options: { onSuccess?: () => void }) => {
          options?.onSuccess?.()
        },
      )

      render(<SettingsAppearancePage />)

      const saveButton = screen.getByText('Save')
      fireEvent.click(saveButton)

      expect(mockToastSuccess).toHaveBeenCalledWith('Preferences saved')
    })

    it('updates saved preferences on save success', () => {
      mockHasUnsavedChanges = true
      mockMutate.mockImplementation(
        (_data: unknown, options: { onSuccess?: () => void }) => {
          options?.onSuccess?.()
        },
      )

      render(<SettingsAppearancePage />)

      const saveButton = screen.getByText('Save')
      fireEvent.click(saveButton)

      expect(mockUpdateSavedPreferences).toHaveBeenCalledWith({
        themeMode: 'dark',
        colorScheme: 'zinc',
        fontSize: 'default',
      })
    })
  })

  describe('Save failure', () => {
    it('shows error toast on save failure', () => {
      mockHasUnsavedChanges = true
      mockMutate.mockImplementation(
        (_data: unknown, options: { onError?: () => void }) => {
          options?.onError?.()
        },
      )

      render(<SettingsAppearancePage />)

      const saveButton = screen.getByText('Save')
      fireEvent.click(saveButton)

      expect(mockToastError).toHaveBeenCalledWith('Failed to save preferences')
    })
  })

  describe('Reset button', () => {
    it('is disabled when there are no unsaved changes', () => {
      mockHasUnsavedChanges = false
      render(<SettingsAppearancePage />)

      const resetButton = screen.getByText('Reset')
      expect(resetButton).toBeDisabled()
    })

    it('is enabled when there are unsaved changes', () => {
      mockHasUnsavedChanges = true
      render(<SettingsAppearancePage />)

      const resetButton = screen.getByText('Reset')
      expect(resetButton).not.toBeDisabled()
    })

    it('calls revertToSaved on click', () => {
      mockHasUnsavedChanges = true
      render(<SettingsAppearancePage />)

      const resetButton = screen.getByText('Reset')
      fireEvent.click(resetButton)

      expect(mockRevertToSaved).toHaveBeenCalled()
    })
  })
})
