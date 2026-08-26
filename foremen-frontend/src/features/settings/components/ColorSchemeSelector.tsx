import { useTranslation } from 'react-i18next'
import { Check } from 'lucide-react'

import { cn } from '@/lib/utils'
import { COLOR_PRESETS } from '@/lib/theme-presets'
import { useThemeStore } from '@/stores/theme-store'

export function ColorSchemeSelector() {
  const { t } = useTranslation()
  const colorScheme = useThemeStore((s) => s.colorScheme)
  const setColorScheme = useThemeStore((s) => s.setColorScheme)

  return (
    <div
      className="grid grid-cols-5 gap-3"
      role="radiogroup"
      aria-label={t('settings.appearance.colorScheme.title')}
    >
      {COLOR_PRESETS.map((preset) => {
        const isActive = colorScheme === preset.name

        return (
          <button
            key={preset.name}
            type="button"
            role="radio"
            aria-checked={isActive}
            aria-label={t(preset.label)}
            title={t(preset.label)}
            className={cn(
              'relative flex items-center justify-center rounded-full',
              'min-h-[44px] min-w-[44px] h-11 w-11',
              'transition-all duration-150',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
              isActive
                ? 'ring-2 ring-primary ring-offset-2 ring-offset-background'
                : 'hover:scale-110 hover:ring-1 hover:ring-muted-foreground/50',
            )}
            style={{ backgroundColor: preset.swatchColor }}
            onClick={() => {
              if (!isActive) {
                setColorScheme(preset.name)
              }
            }}
          >
            {isActive && (
              <Check
                className="h-5 w-5 text-white drop-shadow-[0_1px_2px_rgba(0,0,0,0.5)]"
                aria-hidden="true"
              />
            )}
          </button>
        )
      })}
    </div>
  )
}
