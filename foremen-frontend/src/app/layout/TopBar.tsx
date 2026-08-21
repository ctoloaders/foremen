import { useTranslation } from 'react-i18next'
import { Menu } from 'lucide-react'

import { usePageMeta } from '@/hooks/usePageMeta'
import { useBreakpoint } from '@/hooks/useBreakpoint'
import { useUIStore } from '@/stores/ui-store'

export function TopBar() {
  const { t, i18n } = useTranslation()
  const { titleKey, action } = usePageMeta()
  const breakpoint = useBreakpoint()
  const locale = useUIStore((s) => s.locale)
  const setLocale = useUIStore((s) => s.setLocale)
  const toggleSidebar = useUIStore((s) => s.toggleSidebar)

  const handleLanguageToggle = () => {
    const newLocale = locale === 'pl' ? 'ru' : 'pl'
    setLocale(newLocale)
    void i18n.changeLanguage(newLocale)
  }

  return (
    <header className="flex h-14 items-center justify-between border-b border-border px-4">
      <div className="flex items-center gap-3 min-w-0">
        {breakpoint === 'mobile' && (
          <button
            type="button"
            onClick={toggleSidebar}
            className="shrink-0 p-2 text-muted-foreground hover:text-foreground"
            aria-label="Menu"
          >
            <Menu className="h-5 w-5" />
          </button>
        )}
        <h1 className="truncate text-sm font-medium text-foreground">
          {t(titleKey)}
        </h1>
      </div>

      <div className="flex items-center gap-2 shrink-0">
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          >
            {t(action.labelKey)}
          </button>
        )}
        <button
          type="button"
          onClick={handleLanguageToggle}
          className="rounded-md px-2 py-1 text-sm font-medium text-muted-foreground hover:bg-secondary hover:text-foreground"
        >
          {locale === 'pl' ? 'PL' : 'RU'}
        </button>
      </div>
    </header>
  )
}
