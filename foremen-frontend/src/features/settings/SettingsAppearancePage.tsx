import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'

import { useThemeStore } from '@/stores/theme-store'
import { ThemeModeSelector } from './components/ThemeModeSelector'
import { ColorSchemeSelector } from './components/ColorSchemeSelector'
import { FontSizeSelector } from './components/FontSizeSelector'
import { useSavePreferences } from './api/mutation-hooks'

/**
 * Placeholder user ID used until authentication is implemented.
 * Once a real auth provider is added, replace this with the actual session user ID.
 */
const USER_ID = 1

/**
 * Settings > Appearance page.
 * Composes theme mode, color scheme, and font size selectors with Save/Reset actions.
 */
export default function SettingsAppearancePage() {
  const { t } = useTranslation()
  const { mutate, isPending } = useSavePreferences(USER_ID)

  const themeMode = useThemeStore((s) => s.themeMode)
  const colorScheme = useThemeStore((s) => s.colorScheme)
  const fontSize = useThemeStore((s) => s.fontSize)
  const hasUnsavedChanges = useThemeStore((s) => s.hasUnsavedChanges)
  const updateSavedPreferences = useThemeStore((s) => s.updateSavedPreferences)
  const revertToSaved = useThemeStore((s) => s.revertToSaved)

  const handleSave = () => {
    mutate(
      { themeMode, colorScheme, fontSize },
      {
        onSuccess: () => {
          updateSavedPreferences({ themeMode, colorScheme, fontSize })
          toast.success(t('settings.appearance.toast.success'))
        },
        onError: () => {
          toast.error(t('settings.appearance.toast.error'))
        },
      },
    )
  }

  const handleReset = () => {
    revertToSaved()
  }

  const unsaved = hasUnsavedChanges()

  return (
    <div className="mx-auto w-full max-w-[960px] space-y-8">
      {/* Theme Mode Section */}
      <section>
        <h2 className="text-lg font-semibold text-foreground">
          {t('settings.appearance.themeMode.title')}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {t('settings.appearance.themeMode.description')}
        </p>
        <div className="mt-4">
          <ThemeModeSelector />
        </div>
      </section>

      {/* Color Scheme Section */}
      <section>
        <h2 className="text-lg font-semibold text-foreground">
          {t('settings.appearance.colorScheme.title')}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {t('settings.appearance.colorScheme.description')}
        </p>
        <div className="mt-4">
          <ColorSchemeSelector />
        </div>
      </section>

      {/* Font Size Section */}
      <section>
        <h2 className="text-lg font-semibold text-foreground">
          {t('settings.appearance.fontSize.title')}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {t('settings.appearance.fontSize.description')}
        </p>
        <div className="mt-4">
          <FontSizeSelector />
        </div>
      </section>

      {/* Actions */}
      <div className="flex gap-3 pt-4">
        <button
          type="button"
          onClick={handleSave}
          disabled={!unsaved || isPending}
          className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50"
        >
          {isPending && (
            <svg
              className="mr-2 h-4 w-4 animate-spin"
              xmlns="http://www.w3.org/2000/svg"
              fill="none"
              viewBox="0 0 24 24"
              role="img"
              aria-label={t('common.loading')}
            >
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          )}
          {t('settings.appearance.save')}
        </button>
        <button
          type="button"
          onClick={handleReset}
          disabled={!unsaved}
          className="inline-flex items-center justify-center rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50"
        >
          {t('settings.appearance.reset')}
        </button>
      </div>
    </div>
  )
}
