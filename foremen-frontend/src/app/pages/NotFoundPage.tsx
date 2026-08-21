import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

export default function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4 text-center">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('pages.notFound.title')}
      </h1>
      <Link to="/" className="text-primary hover:underline">
        {t('pages.notFound.backToDashboard')}
      </Link>
    </div>
  )
}
