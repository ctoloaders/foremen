import { Moon, Sun, Monitor } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { cn } from '@/lib/utils'
import { useThemeStore } from '@/stores/theme-store'
import type { ThemeMode } from '@/features/settings/types'

interface ThemeModeOption {
  mode: ThemeMode
  icon: React.ComponentType<{ className?: string }>
  labelKey: string
}

const OPTIONS: ThemeModeOption[] = [
  { mode: 'dark', icon: Moon, labelKey: 'settings.appearance.themeMode.dark' },
  { mode: 'light', icon: Sun, labelKey: 'settings.appearance.themeMode.light' },
  { mode: 'system', icon: Monitor, labelKey: 'settings.appearance.themeMode.system' },
]

export function ThemeModeSelector() {
  const { t } = useTranslation()
  const themeMode = useThemeStore((s) => s.themeMode)
  const setThemeMode = useThemeStore((s) => s.setThemeMode)

  return (
    <div className="flex gap-3" role="radiogroup" aria-label={t('settings.appearance.themeMode.title')}>
      {OPTIONS.map(({ mode, icon: Icon, labelKey }) => {
        const isActive = themeMode === mode

        return (
          <button
            key={mode}
            type="button"
            role="radio"
            aria-checked={isActive}
            onClick={() => setThemeMode(mode)}
            className={cn(
              'flex min-h-[44px] min-w-[44px] flex-col items-center justify-center gap-1.5 rounded-lg border px-4 py-3 text-sm font-medium transition-colors',
              'hover:bg-accent hover:text-accent-foreground',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              isActive
                ? 'border-primary bg-primary/10 text-foreground'
                : 'border-border bg-background text-muted-foreground',
            )}
          >
            <Icon className="h-6 w-6" />
            <span>{t(labelKey)}</span>
          </button>
        )
      })}
    </div>
  )
}
