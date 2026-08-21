import { useTranslation } from 'react-i18next'

export default function FinancesPage() {
  const { t } = useTranslation()

  return (
    <div>
      <h1 className="text-2xl font-semibold text-foreground">{t('pages.finances.title')}</h1>
      <p className="mt-2 text-muted-foreground">{t('pages.finances.description')}</p>
    </div>
  )
}
