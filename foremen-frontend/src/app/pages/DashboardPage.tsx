import { useTranslation } from 'react-i18next'

export default function DashboardPage() {
  const { t } = useTranslation()

  return (
    <div>
      <h1 className="text-2xl font-semibold text-foreground">{t('pages.dashboard.title')}</h1>
      <p className="mt-2 text-muted-foreground">{t('pages.dashboard.description')}</p>
    </div>
  )
}
