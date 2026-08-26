import { useTranslation } from 'react-i18next'

import { cn } from '@/lib/utils'
import { FONT_SIZE_MAP } from '@/lib/theme-presets'
import { useThemeStore } from '@/stores/theme-store'
import type { FontSize } from '@/features/settings/types'

interface FontSizeOption {
  size: FontSize
  labelKey: string
}

const OPTIONS: FontSizeOption[] = [
  { size: 'sm', labelKey: 'settings.appearance.fontSize.sm' },
  { size: 'default', labelKey: 'settings.appearance.fontSize.default' },
  { size: 'lg', labelKey: 'settings.appearance.fontSize.lg' },
  { size: 'xl', labelKey: 'settings.appearance.fontSize.xl' },
]

export function FontSizeSelector() {
  const { t } = useTranslation()
  const fontSize = useThemeStore((s) => s.fontSize)
  const setFontSize = useThemeStore((s) => s.setFontSize)

  return (
    <div className="flex gap-3" role="radiogroup" aria-label={t('settings.appearance.fontSize.title')}>
      {OPTIONS.map(({ size, labelKey }) => {
        const isActive = fontSize === size

        return (
          <button
            key={size}
            type="button"
            role="radio"
            aria-checked={isActive}
            onClick={() => setFontSize(size)}
            className={cn(
              'flex min-h-[44px] min-w-[44px] flex-col items-center justify-center gap-1 rounded-lg border px-4 py-3 text-sm font-medium transition-colors',
              'hover:bg-accent hover:text-accent-foreground',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              isActive
                ? 'border-primary bg-primary/10 text-foreground'
                : 'border-border bg-background text-muted-foreground',
            )}
          >
            <span>{t(labelKey)}</span>
            <span className="text-xs text-muted-foreground">{FONT_SIZE_MAP[size]}</span>
          </button>
        )
      })}
    </div>
  )
}
